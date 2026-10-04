package com.kksg.applicationServices.scm.event;

import com.kksg.applicationServices.scm.common.model.NormalizedEventType;
import com.kksg.applicationServices.scm.common.model.NormalizedWebhookEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Consumer isolation - the reason the dispatcher exists.
 *
 * <p><b>What it replaced.</b> The webhook pipeline called {@code ApplicationEventPublisher.publishEvent}
 * directly. Spring publishes synchronously and the first listener to throw aborts the remaining listeners
 * and propagates, so a bug in one subscriber silently stopped every other subscriber from receiving
 * events, and the only evidence was a delivery row marked {@code FAILED} with nothing identifying the
 * culprit. Three tests below are the ones that matter: a throwing consumer does not block its peers, the
 * failure comes back named, and it comes back as a value rather than an exception.
 *
 * <p><b>Why a value and not an exception.</b> By dispatch time the delivery has been persisted and
 * claimed, and the pipeline must answer the provider 2xx regardless - a non-2xx triggers a retry carrying
 * the same delivery id, which the claim rejects as a duplicate, so the retry could never succeed and
 * providers disable webhooks that keep failing. The pipeline therefore needs the outcome as something it
 * can record on the row, not as an exception that would unwind past that record.
 *
 * <p>Real consumers rather than mocks, because the behaviour under test is what happens when a consumer
 * misbehaves - a mock that throws on demand proves the stub works, not that the loop survives.
 */
class ScmWebhookEventDispatcherTest {

    private static NormalizedWebhookEvent event(NormalizedEventType type) {
        return NormalizedWebhookEvent.builder()
                .eventType(type)
                .providerCode("TESTHUB")
                .deliveryId("delivery-1")
                .deliveryRecordId(100)
                .connectionId(5)
                .repositoryFullName("acme/widgets")
                .pullRequestNumber(7)
                .rawPayload(Map.of())
                .build();
    }

    private static NormalizedWebhookEvent event() {
        return event(NormalizedEventType.PULL_REQUEST_OPENED);
    }

    /** Records what it saw, so "did every consumer run" is answerable without mock bookkeeping. */
    private static final class RecordingConsumer implements ScmWebhookEventConsumer {
        private final String name;
        private final List<String> seen = new ArrayList<>();

        private RecordingConsumer(String name) {
            this.name = name;
        }

        @Override
        public void consume(NormalizedWebhookEvent event) {
            seen.add(event.getDeliveryId());
        }

        @Override
        public String consumerName() {
            return name;
        }
    }

    private static final class ThrowingConsumer implements ScmWebhookEventConsumer {
        private final String name;
        private final RuntimeException failure;

        private ThrowingConsumer(String name, RuntimeException failure) {
            this.name = name;
            this.failure = failure;
        }

        @Override
        public void consume(NormalizedWebhookEvent event) {
            throw failure;
        }

        @Override
        public String consumerName() {
            return name;
        }
    }

    @Nested
    @DisplayName("a failing consumer cannot affect its peers")
    class Isolation {

        @Test
        @DisplayName("consumers after a throwing one still run")
        void laterConsumersStillRun() {
            RecordingConsumer first = new RecordingConsumer("first");
            RecordingConsumer last = new RecordingConsumer("last");
            ScmWebhookEventDispatcher dispatcher = new ScmWebhookEventDispatcher(
                    List.of(first, new ThrowingConsumer("broken", new IllegalStateException("boom")), last));

            dispatcher.dispatch(event());

            // Under raw Spring publication, "last" would never have been called.
            assertThat(first.seen).containsExactly("delivery-1");
            assertThat(last.seen).containsExactly("delivery-1");
        }

        @Test
        @DisplayName("the failure is returned, not thrown")
        void failureIsReturnedNotThrown() {
            ScmWebhookEventDispatcher dispatcher = new ScmWebhookEventDispatcher(
                    List.of(new ThrowingConsumer("broken", new IllegalStateException("boom"))));

            // Throwing here would unwind past the pipeline's markFailed call, losing the one record that
            // makes the delivery replayable.
            assertThatCode(() -> dispatcher.dispatch(event())).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the failing consumer is named in the result")
        void failureNamesTheConsumer() {
            ScmWebhookEventDispatcher dispatcher = new ScmWebhookEventDispatcher(
                    List.of(new RecordingConsumer("healthy"),
                            new ThrowingConsumer("orchestration", new IllegalStateException("boom"))));

            ScmWebhookEventDispatcher.DispatchResult result = dispatcher.dispatch(event());

            // Without the name, a FAILED delivery tells an operator only that something broke.
            assertThat(result.failures()).containsExactly("orchestration/IllegalStateException");
            assertThat(result.hasFailures()).isTrue();
            assertThat(result.consumersInvoked()).isEqualTo(2);
            assertThat(result.succeeded()).isEqualTo(1);
        }

        @Test
        @DisplayName("the failure reason carries the exception type, not its message")
        void failureReasonExcludesMessages() {
            ScmWebhookEventDispatcher dispatcher = new ScmWebhookEventDispatcher(
                    List.of(new ThrowingConsumer("orchestration",
                            new IllegalStateException("token ghp_secret rejected for acme/widgets"))));

            String reason = dispatcher.dispatch(event()).failureReason();

            // The reason is persisted on the delivery row. A consumer's message may quote payload or
            // credential content, so only the type crosses that boundary; the full stack trace stays in
            // the log.
            assertThat(reason).isEqualTo("orchestration/IllegalStateException");
            assertThat(reason).doesNotContain("ghp_secret");
        }

        @Test
        @DisplayName("every failure is recorded, not just the first")
        void allFailuresAreRecorded() {
            ScmWebhookEventDispatcher dispatcher = new ScmWebhookEventDispatcher(
                    List.of(new ThrowingConsumer("a", new IllegalStateException("x")),
                            new ThrowingConsumer("b", new IllegalArgumentException("y"))));

            ScmWebhookEventDispatcher.DispatchResult result = dispatcher.dispatch(event());

            assertThat(result.failures())
                    .containsExactly("a/IllegalStateException", "b/IllegalArgumentException");
            assertThat(result.succeeded()).isZero();
        }

        @Test
        @DisplayName("a consumer whose supports() throws is counted as broken, not as uninterested")
        void brokenSupportsIsAFailure() {
            // Silently skipping it would make a misconfigured consumer look like one that simply did not
            // want the event - the hardest kind of failure to notice.
            ScmWebhookEventDispatcher dispatcher = new ScmWebhookEventDispatcher(List.of(
                    new ScmWebhookEventConsumer() {
                        @Override
                        public boolean supports(NormalizedEventType eventType) {
                            throw new IllegalStateException("bad predicate");
                        }

                        @Override
                        public void consume(NormalizedWebhookEvent event) {
                            throw new AssertionError("must not be consulted");
                        }

                        @Override
                        public String consumerName() {
                            return "selective";
                        }
                    }));

            ScmWebhookEventDispatcher.DispatchResult result = dispatcher.dispatch(event());

            assertThat(result.failures()).containsExactly("selective:supports/IllegalStateException");
        }

        @Test
        @DisplayName("a consumer whose consumerName() throws does not abort the loop")
        void brokenNameDoesNotAbortTheLoop() {
            RecordingConsumer healthy = new RecordingConsumer("healthy");
            ScmWebhookEventDispatcher dispatcher = new ScmWebhookEventDispatcher(List.of(
                    new ScmWebhookEventConsumer() {
                        @Override
                        public void consume(NormalizedWebhookEvent event) {
                            // Succeeds; only its name accessor is broken.
                        }

                        @Override
                        public String consumerName() {
                            throw new IllegalStateException("no name");
                        }
                    }, healthy));

            ScmWebhookEventDispatcher.DispatchResult result = dispatcher.dispatch(event());

            // Failing to log a name must never cost a delivery.
            assertThat(healthy.seen).containsExactly("delivery-1");
            assertThat(result.hasFailures()).isFalse();
        }

        @Test
        @DisplayName("a blank consumer name falls back to the class name")
        void blankNameFallsBackToClassName() {
            ScmWebhookEventDispatcher dispatcher = new ScmWebhookEventDispatcher(List.of(
                    new ScmWebhookEventConsumer() {
                        @Override
                        public void consume(NormalizedWebhookEvent event) {
                            throw new IllegalStateException("boom");
                        }

                        @Override
                        public String consumerName() {
                            return "  ";
                        }
                    }));

            assertThat(dispatcher.dispatch(event()).failureReason())
                    .contains("ScmWebhookEventDispatcherTest")
                    .contains("IllegalStateException");
        }
    }

    @Nested
    @DisplayName("consumers declare which events they want")
    class Selection {

        @Test
        @DisplayName("a consumer that does not support the event is not invoked")
        void unsupportedEventIsNotDelivered() {
            ScmWebhookEventDispatcher dispatcher = new ScmWebhookEventDispatcher(List.of(
                    new ScmWebhookEventConsumer() {
                        @Override
                        public boolean supports(NormalizedEventType eventType) {
                            return eventType == NormalizedEventType.PULL_REQUEST_CLOSED;
                        }

                        @Override
                        public void consume(NormalizedWebhookEvent event) {
                            throw new AssertionError("must not be invoked");
                        }
                    }));

            ScmWebhookEventDispatcher.DispatchResult result =
                    dispatcher.dispatch(event(NormalizedEventType.PULL_REQUEST_OPENED));

            assertThat(result.consumersInvoked()).isZero();
            assertThat(result.hasFailures()).isFalse();
        }

        @Test
        @DisplayName("declining is distinct from succeeding")
        void decliningIsNotCountedAsSuccess() {
            // Declared via supports() rather than an early return inside consume() precisely so the
            // dispatcher can report "nobody wanted this" instead of it looking like a successful
            // dispatch.
            ScmWebhookEventDispatcher dispatcher = new ScmWebhookEventDispatcher(List.of(
                    (ScmWebhookEventConsumer) e -> {
                        throw new AssertionError("must not be invoked");
                    }));
            ScmWebhookEventDispatcher declining = new ScmWebhookEventDispatcher(List.of(
                    new ScmWebhookEventConsumer() {
                        @Override
                        public boolean supports(NormalizedEventType eventType) {
                            return false;
                        }

                        @Override
                        public void consume(NormalizedWebhookEvent event) {
                            throw new AssertionError("must not be invoked");
                        }
                    }));
            assertThat(dispatcher).isNotNull();

            ScmWebhookEventDispatcher.DispatchResult result = declining.dispatch(event());

            assertThat(result.noConsumers()).isTrue();
            assertThat(result.succeeded()).isZero();
        }

        @ParameterizedTest(name = "{0} reaches a default consumer")
        @EnumSource(NormalizedEventType.class)
        @DisplayName("the default supports() accepts every canonical event type")
        void defaultSupportsAcceptsEverything(NormalizedEventType type) {
            // So that adding a NormalizedEventType does not silently stop reaching existing consumers.
            RecordingConsumer consumer = new RecordingConsumer("default");
            ScmWebhookEventDispatcher dispatcher = new ScmWebhookEventDispatcher(List.of(consumer));

            dispatcher.dispatch(event(type));

            assertThat(consumer.seen).hasSize(1);
        }

        @Test
        @DisplayName("consumers are invoked in registration order")
        void invocationOrderIsStable() {
            List<String> order = new ArrayList<>();
            ScmWebhookEventDispatcher dispatcher = new ScmWebhookEventDispatcher(List.of(
                    (ScmWebhookEventConsumer) e -> order.add("first"),
                    (ScmWebhookEventConsumer) e -> order.add("second")));

            dispatcher.dispatch(event());

            assertThat(order).containsExactly("first", "second");
        }
    }

    @Nested
    @DisplayName("no consumers is a legitimate state")
    class NoConsumers {

        @Test
        @DisplayName("an empty registry dispatches successfully")
        void emptyRegistryIsNotAFailure() {
            // What a deployment without Review Orchestration looks like. The webhook pipeline must keep
            // receiving, verifying and recording deliveries there.
            ScmWebhookEventDispatcher dispatcher = new ScmWebhookEventDispatcher(List.of());

            ScmWebhookEventDispatcher.DispatchResult result = dispatcher.dispatch(event());

            assertThat(result.noConsumers()).isTrue();
            assertThat(result.hasFailures()).isFalse();
            assertThat(result.failureReason()).isEmpty();
        }
    }

    @Nested
    @DisplayName("the reason stays within the delivery row's column")
    class ReasonBounding {

        @Test
        @DisplayName("an aggregated reason is truncated")
        void longReasonIsTruncated() {
            List<ScmWebhookEventConsumer> many = new ArrayList<>();
            AtomicInteger counter = new AtomicInteger();
            for (int i = 0; i < 40; i++) {
                String name = "consumer-with-a-rather-long-name-" + counter.incrementAndGet();
                many.add(new ThrowingConsumer(name, new IllegalStateException("x")));
            }

            String reason = new ScmWebhookEventDispatcher(many).dispatch(event()).failureReason();

            // An over-long diagnostic must not be what fails the write that records the failure.
            assertThat(reason).hasSizeLessThanOrEqualTo(500);
        }
    }

    @Nested
    @DisplayName("the Spring-event bridge")
    class ApplicationEventBridge {

        @Test
        @DisplayName("the bridge republishes the event unchanged")
        void bridgeRepublishes() {
            List<Object> published = new ArrayList<>();
            ApplicationEventPublisher publisher = published::add;
            ApplicationEventWebhookEventConsumer bridge =
                    new ApplicationEventWebhookEventConsumer(publisher);
            NormalizedWebhookEvent event = event();

            new ScmWebhookEventDispatcher(List.of(bridge)).dispatch(event);

            // Keeps @EventListener(NormalizedWebhookEvent.class) working, so introducing the consumer
            // interface did not force a migration on anything already subscribed.
            assertThat(published).containsExactly(event);
        }

        @Test
        @DisplayName("a throwing listener is reported against the bridge, not against a peer consumer")
        void bridgeFailureIsAttributedToTheBridge() {
            ApplicationEventPublisher exploding = e -> {
                throw new IllegalStateException("listener failed");
            };
            RecordingConsumer peer = new RecordingConsumer("peer");

            ScmWebhookEventDispatcher.DispatchResult result = new ScmWebhookEventDispatcher(
                    List.of(new ApplicationEventWebhookEventConsumer(exploding), peer)).dispatch(event());

            assertThat(result.failures()).containsExactly("springApplicationEvent/IllegalStateException");
            // And the peer still ran, which is exactly what raw publishEvent could not guarantee.
            assertThat(peer.seen).containsExactly("delivery-1");
        }
    }

    @Nested
    @DisplayName("the publisher delegates to the dispatcher")
    class PublisherDelegation {

        @Test
        @DisplayName("ScmEventPublisher returns the dispatch result")
        void publisherReturnsResult() {
            // The pipeline reads this result to decide PROCESSED versus FAILED, so the publisher must
            // pass it through rather than swallow it.
            ScmEventPublisher publisher = new ScmEventPublisher(new ScmWebhookEventDispatcher(
                    List.of(new ThrowingConsumer("broken", new IllegalStateException("boom")))));

            ScmWebhookEventDispatcher.DispatchResult result = publisher.publish(event());

            assertThat(result.hasFailures()).isTrue();
            assertThat(result.failures()).containsExactly("broken/IllegalStateException");
        }

        @Test
        @DisplayName("there is exactly one dispatch path")
        void singleDispatchPath() {
            // Publishing the Spring event alongside the dispatcher would have reintroduced two
            // mechanisms with different failure behaviour - the problem the dispatcher was added to
            // solve. The bridge being an ordinary consumer is what prevents that.
            List<Object> published = new ArrayList<>();
            ApplicationEventPublisher publisher = published::add;
            ScmEventPublisher eventPublisher = new ScmEventPublisher(new ScmWebhookEventDispatcher(
                    List.of(new ApplicationEventWebhookEventConsumer(publisher))));

            eventPublisher.publish(event());

            assertThat(published).hasSize(1);
        }
    }
}
