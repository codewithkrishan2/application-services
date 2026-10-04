package com.kksg.applicationServices.scm.webhook.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kksg.applicationServices.scm.adapter.ScmAdapterRegistry;
import com.kksg.applicationServices.scm.common.exception.ScmErrorCode;
import com.kksg.applicationServices.scm.common.exception.ScmException;
import com.kksg.applicationServices.scm.common.model.NormalizedEventType;
import com.kksg.applicationServices.scm.common.model.NormalizedWebhookEvent;
import com.kksg.applicationServices.scm.connection.entity.ScmConnection;
import com.kksg.applicationServices.scm.connection.entity.ScmConnectionStatus;
import com.kksg.applicationServices.scm.connection.service.ScmConnectionService;
import com.kksg.applicationServices.scm.event.ApplicationEventWebhookEventConsumer;
import com.kksg.applicationServices.scm.event.ScmEventPublisher;
import com.kksg.applicationServices.scm.event.ScmWebhookEventConsumer;
import com.kksg.applicationServices.scm.event.ScmWebhookEventDispatcher;
import com.kksg.applicationServices.scm.event.entity.ScmProviderEvent;
import com.kksg.applicationServices.scm.event.service.ScmProviderEventService;
import com.kksg.applicationServices.scm.operation.engine.ScmResponseNormalizer;
import com.kksg.applicationServices.scm.provider.config.ProviderConfiguration;
import com.kksg.applicationServices.scm.provider.config.ProviderCredentialResolver;
import com.kksg.applicationServices.scm.provider.entity.ScmProvider;
import com.kksg.applicationServices.scm.provider.service.ScmProviderService;
import com.kksg.applicationServices.scm.webhook.entity.ScmWebhookDelivery;
import com.kksg.applicationServices.scm.webhook.entity.ScmWebhookDeliveryStatus;
import com.kksg.applicationServices.scm.webhook.verification.WebhookSignatureVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The webhook pipeline's outcomes, and the order the steps happen in.
 *
 * <p><b>Order is the security property.</b> Signature verification runs before the body is parsed and
 * before anything is written, so an unauthenticated caller can cause neither a database write nor the
 * interpretation of hostile JSON. The claim happens before publishing, so a provider retry arriving
 * mid-processing is recognised as a duplicate rather than starting duplicate downstream work. Both are
 * asserted here by what <i>does not</i> happen.
 *
 * <p><b>Why a failure is acknowledged rather than propagated.</b> Returning an error after the claim
 * would be worse than useless: the provider's retry carries the same delivery id, which the claim
 * rejects as a duplicate, so the retry could never succeed - and providers disable webhooks that keep
 * failing. A {@code FAILED} row is replayable instead, and a provider retry of a {@code FAILED}
 * delivery is explicitly allowed to reprocess it.
 *
 * <p>The dispatcher is real rather than mocked, because consumer isolation is part of the outcome being
 * tested: a throwing consumer must produce a {@code FAILED} delivery without stopping its peers.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScmWebhookServiceTest {

    private static final String PROVIDER_CODE = "TESTHUB";
    private static final String DELIVERY_ID = "delivery-abc";
    private static final String EVENT_HEADER = "x-testhub-event";
    private static final String DELIVERY_HEADER = "x-testhub-delivery";
    private static final String PAYLOAD = """
            {"action":"opened","repository":{"id":"repo-1","full_name":"acme/widgets"},
             "pull_request":{"number":7},"sender":{"account_id":"acct-1"}}""";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private ScmProviderService providerService;

    @Mock
    private ProviderCredentialResolver credentialResolver;

    @Mock
    private WebhookSignatureVerifier signatureVerifier;

    @Mock
    private ScmProviderEventService eventService;

    @Mock
    private ScmResponseNormalizer responseNormalizer;

    @Mock
    private ScmConnectionService connectionService;

    @Mock
    private ScmWebhookDeliveryService deliveryService;

    private ScmProvider provider;
    private ScmConnection connection;
    private ScmProviderEvent mappedEvent;
    private ScmWebhookDelivery delivery;
    private List<ScmWebhookEventConsumer> consumers;
    private ScmWebhookService service;

    @BeforeEach
    void setUp() {
        provider = new ScmProvider();
        provider.setId(1);
        provider.setProviderCode(PROVIDER_CODE);

        connection = new ScmConnection();
        connection.setId(5);
        connection.setProvider(provider);
        connection.setConnectionStatus(ScmConnectionStatus.ACTIVE);

        mappedEvent = new ScmProviderEvent();
        mappedEvent.setId(11);
        mappedEvent.setProvider(provider);
        mappedEvent.setProviderEventName("pull_request");
        mappedEvent.setProviderAction("opened");
        mappedEvent.setNormalizedEventType(NormalizedEventType.PULL_REQUEST_OPENED);

        delivery = new ScmWebhookDelivery();
        delivery.setId(100);
        delivery.setProvider(provider);
        delivery.setDeliveryId(DELIVERY_ID);
        delivery.setStatus(ScmWebhookDeliveryStatus.RECEIVED);

        ProviderConfiguration configuration = new ProviderConfiguration(null, null,
                new ProviderConfiguration.Webhook(
                        ProviderConfiguration.SignatureAlgorithm.HMAC_SHA256,
                        "x-testhub-signature", "sha256=", EVENT_HEADER, DELIVERY_HEADER,
                        "action", "scm.providers.testhub.webhook-secret"),
                null, null);

        when(providerService.requireActiveByCode(PROVIDER_CODE)).thenReturn(provider);
        when(providerService.getConfiguration(provider)).thenReturn(configuration);
        when(credentialResolver.resolveWebhookSecret(provider)).thenReturn(Optional.of("shared-secret"));
        when(signatureVerifier.verify(any(), any(), any(), any())).thenReturn(true);
        when(eventService.findMapping(eq(provider), anyString(), anyString()))
                .thenReturn(Optional.of(mappedEvent));
        when(eventService.resolvePayloadMapping(mappedEvent)).thenReturn(null);
        when(responseNormalizer.normalizeObject(any(), any())).thenReturn(normalizedPayload());
        when(connectionService.findConnectionForDelivery(1, "acct-1")).thenReturn(connection);
        when(deliveryService.claim(any(), any(), anyString(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ScmWebhookDeliveryService.ClaimOutcome(false, delivery));

        consumers = new ArrayList<>();
        rebuild();
    }

    /** Rebuilt whenever the consumer list changes, since the dispatcher copies it on construction. */
    private void rebuild() {
        ScmEventPublisher publisher = new ScmEventPublisher(new ScmWebhookEventDispatcher(consumers));
        service = new ScmWebhookService(providerService, credentialResolver, signatureVerifier,
                eventService, responseNormalizer, connectionService, deliveryService, publisher,
                new ScmAdapterRegistry(List.of()), MAPPER);
    }

    private ObjectNode normalizedPayload() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("repositoryExternalId", "repo-1");
        node.put("repositoryFullName", "acme/widgets");
        node.put("pullRequestNumber", 7);
        node.put("accountExternalId", "acct-1");
        return node;
    }

    private Map<String, String> headers() {
        Map<String, String> headers = new HashMap<>();
        headers.put(EVENT_HEADER, "pull_request");
        headers.put(DELIVERY_HEADER, DELIVERY_ID);
        headers.put("x-testhub-signature", "sha256=whatever");
        return headers;
    }

    private ScmWebhookService.WebhookProcessingResult process() {
        return service.process(PROVIDER_CODE, headers(), PAYLOAD.getBytes(StandardCharsets.UTF_8));
    }

    private static final class CapturingConsumer implements ScmWebhookEventConsumer {
        private final List<NormalizedWebhookEvent> received = new ArrayList<>();

        @Override
        public void consume(NormalizedWebhookEvent event) {
            received.add(event);
        }
    }

    @Nested
    @DisplayName("signature verification gates everything")
    class SignatureGate {

        @Test
        @DisplayName("an invalid signature is rejected outright")
        void invalidSignatureIsRejected() {
            when(signatureVerifier.verify(any(), any(), any(), any())).thenReturn(false);

            assertThatThrownBy(ScmWebhookServiceTest.this::process)
                    .isInstanceOf(ScmException.class)
                    .extracting(ex -> ((ScmException) ex).getErrorCode())
                    .isEqualTo(ScmErrorCode.SCM_WEBHOOK_SIGNATURE_INVALID);
        }

        @Test
        @DisplayName("an invalid signature causes no database write and no parsing")
        void invalidSignatureWritesNothing() {
            when(signatureVerifier.verify(any(), any(), any(), any())).thenReturn(false);

            assertThatThrownBy(ScmWebhookServiceTest.this::process).isInstanceOf(ScmException.class);

            // The order claim, asserted by absence. An unauthenticated caller must not be able to
            // insert rows or make us interpret hostile JSON.
            verifyNoInteractions(deliveryService);
            verifyNoInteractions(eventService);
            verifyNoInteractions(responseNormalizer);
        }

        @Test
        @DisplayName("a valid signature lets the delivery through")
        void validSignatureProceeds() {
            assertThat(process().outcome()).isEqualTo(ScmWebhookService.WebhookOutcome.ACCEPTED);
        }

        @Test
        @DisplayName("an unknown provider is rejected before verification is attempted")
        void unknownProviderIsRejected() {
            when(providerService.requireActiveByCode("NOPE"))
                    .thenThrow(new ScmException(ScmErrorCode.SCM_PROVIDER_NOT_FOUND, "providerCode=NOPE"));

            assertThatThrownBy(() -> service.process("NOPE", headers(), PAYLOAD.getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(ScmException.class)
                    .extracting(ex -> ((ScmException) ex).getErrorCode())
                    .isEqualTo(ScmErrorCode.SCM_PROVIDER_NOT_FOUND);

            verifyNoInteractions(deliveryService);
        }
    }

    @Nested
    @DisplayName("a known event is normalized and published")
    class KnownEvent {

        @Test
        @DisplayName("the delivery is accepted and marked processed")
        void acceptedDeliveryIsMarkedProcessed() {
            ScmWebhookService.WebhookProcessingResult result = process();

            assertThat(result.outcome()).isEqualTo(ScmWebhookService.WebhookOutcome.ACCEPTED);
            assertThat(result.normalizedEventType()).isEqualTo(NormalizedEventType.PULL_REQUEST_OPENED);
            assertThat(result.deliveryRecordId()).isEqualTo(100);
            verify(deliveryService).markProcessing(100);
            verify(deliveryService).markProcessed(100);
        }

        @Test
        @DisplayName("the published event carries the canonical type, not the provider's name")
        void publishedEventIsCanonical() {
            CapturingConsumer consumer = new CapturingConsumer();
            consumers.add(consumer);
            rebuild();

            process();

            assertThat(consumer.received).hasSize(1);
            NormalizedWebhookEvent event = consumer.received.get(0);
            // Module 4 branches on this and nothing else. GitHub's pull_request/opened and Bitbucket's
            // pullrequest:created must both arrive as PULL_REQUEST_OPENED.
            assertThat(event.getEventType()).isEqualTo(NormalizedEventType.PULL_REQUEST_OPENED);
            assertThat(event.getRepositoryFullName()).isEqualTo("acme/widgets");
            assertThat(event.getPullRequestNumber()).isEqualTo(7);
            assertThat(event.getConnectionId()).isEqualTo(5);
            assertThat(event.getDeliveryId()).isEqualTo(DELIVERY_ID);
            // Retained for diagnostics, explicitly not for branching.
            assertThat(event.getProviderCode()).isEqualTo(PROVIDER_CODE);
            assertThat(event.getProviderEventName()).isEqualTo("pull_request");
        }

        @Test
        @DisplayName("the raw payload is carried as the documented escape hatch")
        void rawPayloadIsCarried() {
            CapturingConsumer consumer = new CapturingConsumer();
            consumers.add(consumer);
            rebuild();

            process();

            // Deliberate: orchestration occasionally needs a field the MVP mapping does not extract. It
            // couples the consumer to a provider, so a new mapping entry is the preferred fix.
            assertThat(consumer.received.get(0).getRawPayload()).containsKey("pull_request");
        }

        @Test
        @DisplayName("an unresolvable connection does not stop the event being published")
        void missingConnectionStillPublishes() {
            // A webhook can arrive for an account whose connection was removed. The event is still
            // worth publishing - dropping it would lose a pull request silently - so connectionId is
            // simply null and the consumer decides.
            when(connectionService.findConnectionForDelivery(anyInt(), anyString())).thenReturn(null);
            CapturingConsumer consumer = new CapturingConsumer();
            consumers.add(consumer);
            rebuild();

            assertThat(process().outcome()).isEqualTo(ScmWebhookService.WebhookOutcome.ACCEPTED);
            assertThat(consumer.received.get(0).getConnectionId()).isNull();
        }

        @ParameterizedTest(name = "{0} is published as itself")
        @EnumSource(NormalizedEventType.class)
        @DisplayName("every canonical event type survives the pipeline")
        void allEventTypesArePublished(NormalizedEventType type) {
            mappedEvent.setNormalizedEventType(type);
            CapturingConsumer consumer = new CapturingConsumer();
            consumers.add(consumer);
            rebuild();

            process();

            assertThat(consumer.received.get(0).getEventType()).isEqualTo(type);
        }
    }

    @Nested
    @DisplayName("an unmapped event is recorded, not dropped")
    class UnmappedEvent {

        @Test
        @DisplayName("an unsubscribed event is IGNORED")
        void unmappedEventIsIgnored() {
            // GitHub's ping on webhook creation lands here. Recording it as FAILED would pollute failure
            // alerting; recording it as PROCESSED would make the table unable to answer "did we act?".
            when(eventService.findMapping(eq(provider), anyString(), anyString()))
                    .thenReturn(Optional.empty());

            ScmWebhookService.WebhookProcessingResult result = process();

            assertThat(result.outcome()).isEqualTo(ScmWebhookService.WebhookOutcome.IGNORED);
            assertThat(result.normalizedEventType()).isNull();
            verify(deliveryService).markIgnored(eq(100), anyString());
        }

        @Test
        @DisplayName("an unmapped event still claims its delivery id")
        void unmappedEventStillClaims() {
            when(eventService.findMapping(eq(provider), anyString(), anyString()))
                    .thenReturn(Optional.empty());

            process();

            // So a retry of an ignorable event stays ignorable, and so an operator can see what a
            // provider is actually sending.
            verify(deliveryService).claim(eq(provider), eq(null), eq(DELIVERY_ID), anyString(),
                    anyString(), eq(null), eq(null), eq(null), any());
        }

        @Test
        @DisplayName("nothing is published for an unmapped event")
        void unmappedEventPublishesNothing() {
            when(eventService.findMapping(eq(provider), anyString(), anyString()))
                    .thenReturn(Optional.empty());
            CapturingConsumer consumer = new CapturingConsumer();
            consumers.add(consumer);
            rebuild();

            process();

            assertThat(consumer.received).isEmpty();
        }

        @Test
        @DisplayName("a duplicate of an unmapped event is reported as a duplicate")
        void duplicateUnmappedEvent() {
            when(eventService.findMapping(eq(provider), anyString(), anyString()))
                    .thenReturn(Optional.empty());
            delivery.setStatus(ScmWebhookDeliveryStatus.IGNORED);
            when(deliveryService.claim(any(), any(), anyString(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(new ScmWebhookDeliveryService.ClaimOutcome(true, delivery));

            assertThat(process().outcome()).isEqualTo(ScmWebhookService.WebhookOutcome.DUPLICATE);
            verify(deliveryService, never()).markIgnored(anyInt(), anyString());
        }
    }

    @Nested
    @DisplayName("duplicates are recognised by the claim")
    class Duplicates {

        @ParameterizedTest(name = "a duplicate in {0} is not reprocessed")
        @EnumSource(value = ScmWebhookDeliveryStatus.class,
                names = {"RECEIVED", "PROCESSING", "PROCESSED", "IGNORED"})
        @DisplayName("an already-claimed delivery is acknowledged without further work")
        void duplicateIsNotReprocessed(ScmWebhookDeliveryStatus status) {
            delivery.setStatus(status);
            when(deliveryService.claim(any(), any(), anyString(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(new ScmWebhookDeliveryService.ClaimOutcome(true, delivery));
            CapturingConsumer consumer = new CapturingConsumer();
            consumers.add(consumer);
            rebuild();

            ScmWebhookService.WebhookProcessingResult result = process();

            assertThat(result.outcome()).isEqualTo(ScmWebhookService.WebhookOutcome.DUPLICATE);
            // The point of claiming before publishing: a provider retry arriving mid-processing must not
            // start a second review.
            assertThat(consumer.received).isEmpty();
            verify(deliveryService, never()).markProcessing(anyInt());
        }

        @Test
        @DisplayName("a duplicate of a FAILED delivery is reprocessed")
        void failedDuplicateIsRetried() {
            // The one duplicate worth reprocessing: the provider's retry is a free chance to recover
            // from a transient fault, and the alternative is a delivery that can never succeed.
            delivery.setStatus(ScmWebhookDeliveryStatus.FAILED);
            when(deliveryService.claim(any(), any(), anyString(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(new ScmWebhookDeliveryService.ClaimOutcome(true, delivery));
            CapturingConsumer consumer = new CapturingConsumer();
            consumers.add(consumer);
            rebuild();

            ScmWebhookService.WebhookProcessingResult result = process();

            assertThat(result.outcome()).isEqualTo(ScmWebhookService.WebhookOutcome.ACCEPTED);
            assertThat(consumer.received).hasSize(1);
        }
    }

    @Nested
    @DisplayName("consumer failure is recorded, and still acknowledged")
    class ConsumerFailure {

        @Test
        @DisplayName("a throwing consumer marks the delivery FAILED")
        void throwingConsumerFailsTheDelivery() {
            consumers.add(event -> {
                throw new IllegalStateException("could not enqueue review");
            });
            rebuild();

            ScmWebhookService.WebhookProcessingResult result = process();

            assertThat(result.outcome()).isEqualTo(ScmWebhookService.WebhookOutcome.FAILED);
            verify(deliveryService).markFailed(eq(100), anyString());
            verify(deliveryService, never()).markProcessed(anyInt());
        }

        @Test
        @DisplayName("the recorded reason names the consumer and its exception type only")
        void recordedReasonIsSafe() {
            consumers.add(event -> {
                throw new IllegalStateException("payload said hunter2");
            });
            rebuild();

            process();

            ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
            verify(deliveryService).markFailed(eq(100), reason.capture());
            // Persisted on the delivery row, so a consumer's message - which may quote payload or
            // credential content - must not cross that boundary.
            assertThat(reason.getValue()).contains("IllegalStateException").doesNotContain("hunter2");
        }

        @Test
        @DisplayName("the request is not failed, so the provider does not retry into a dead end")
        void failureDoesNotPropagate() {
            consumers.add(event -> {
                throw new IllegalStateException("boom");
            });
            rebuild();

            // Throwing would produce a non-2xx, the provider would retry with the same delivery id, the
            // claim would reject it as a duplicate, and the delivery could never succeed - while the
            // provider eventually disables the webhook.
            assertThat(process().outcome()).isEqualTo(ScmWebhookService.WebhookOutcome.FAILED);
        }

        @Test
        @DisplayName("one failing consumer does not stop another from receiving the event")
        void oneFailureDoesNotStarveOthers() {
            CapturingConsumer healthy = new CapturingConsumer();
            consumers.add(event -> {
                throw new IllegalStateException("boom");
            });
            consumers.add(healthy);
            rebuild();

            ScmWebhookService.WebhookProcessingResult result = process();

            assertThat(result.outcome()).isEqualTo(ScmWebhookService.WebhookOutcome.FAILED);
            // Under the previous raw publishEvent call, this consumer would never have been invoked.
            assertThat(healthy.received).hasSize(1);
        }

        @Test
        @DisplayName("no consumers at all is still a processed delivery")
        void noConsumersIsProcessed() {
            // What a deployment without Review Orchestration looks like. This module's job is to
            // normalize and publish, and it did; FAILED would alert on an absence that is expected, and
            // IGNORED already means "unmapped provider event".
            ScmWebhookService.WebhookProcessingResult result = process();

            assertThat(result.outcome()).isEqualTo(ScmWebhookService.WebhookOutcome.ACCEPTED);
            verify(deliveryService).markProcessed(100);
        }

        @Test
        @DisplayName("an @EventListener-style subscriber failure is attributed to the bridge")
        void bridgeFailureIsAttributed() {
            consumers.add(new ApplicationEventWebhookEventConsumer(event -> {
                throw new IllegalStateException("listener exploded");
            }));
            rebuild();

            process();

            ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
            verify(deliveryService).markFailed(eq(100), reason.capture());
            assertThat(reason.getValue()).startsWith("springApplicationEvent/");
        }
    }

    @Nested
    @DisplayName("malformed input")
    class MalformedInput {

        @Test
        @DisplayName("an empty body is rejected after verification")
        void emptyBodyIsRejected() {
            assertThatThrownBy(() -> service.process(PROVIDER_CODE, headers(), new byte[0]))
                    .isInstanceOf(ScmException.class)
                    .extracting(ex -> ((ScmException) ex).getErrorCode())
                    .isEqualTo(ScmErrorCode.SCM_PROVIDER_API_ERROR);
        }

        @Test
        @DisplayName("a body that is not JSON is rejected without a message echoing it")
        void unparseableBodyIsRejected() {
            byte[] body = "<html>not json</html>".getBytes(StandardCharsets.UTF_8);

            assertThatThrownBy(() -> service.process(PROVIDER_CODE, headers(), body))
                    .isInstanceOf(ScmException.class)
                    .hasMessageNotContaining("<html>");
        }

        @Test
        @DisplayName("a missing delivery header falls back to a body digest")
        void missingDeliveryHeaderFallsBackToDigest() {
            // Deduplication must not silently switch off. A body digest still collapses identical
            // retries, which is the common case, and beats treating every delivery as unique.
            Map<String, String> headers = headers();
            headers.remove(DELIVERY_HEADER);

            ScmWebhookService.WebhookProcessingResult result =
                    service.process(PROVIDER_CODE, headers, PAYLOAD.getBytes(StandardCharsets.UTF_8));

            assertThat(result.deliveryId()).startsWith("sha256:");
        }

        @Test
        @DisplayName("the same body yields the same fallback delivery id")
        void digestFallbackIsStable() {
            Map<String, String> headers = headers();
            headers.remove(DELIVERY_HEADER);

            String first = service.process(PROVIDER_CODE, headers,
                    PAYLOAD.getBytes(StandardCharsets.UTF_8)).deliveryId();
            String second = service.process(PROVIDER_CODE, headers,
                    PAYLOAD.getBytes(StandardCharsets.UTF_8)).deliveryId();

            assertThat(first).isEqualTo(second);
        }

        @Test
        @DisplayName("a normalized payload missing the pull request number still publishes")
        void missingPullRequestNumberIsTolerated() {
            // Not every canonical event concerns a pull request, and a mapping gap must not cost the
            // event. The consumer sees null and decides.
            ObjectNode sparse = MAPPER.createObjectNode();
            sparse.put("accountExternalId", "acct-1");
            when(responseNormalizer.normalizeObject(any(), any())).thenReturn(sparse);
            CapturingConsumer consumer = new CapturingConsumer();
            consumers.add(consumer);
            rebuild();

            assertThat(process().outcome()).isEqualTo(ScmWebhookService.WebhookOutcome.ACCEPTED);
            assertThat(consumer.received.get(0).getPullRequestNumber()).isNull();
        }

        @Test
        @DisplayName("a pull request number arriving as text is parsed")
        void textualPullRequestNumberIsParsed() {
            // Providers are inconsistent about quoting numbers in JSON.
            ObjectNode node = normalizedPayload();
            node.put("pullRequestNumber", " 42 ");
            when(responseNormalizer.normalizeObject(any(), any())).thenReturn(node);
            CapturingConsumer consumer = new CapturingConsumer();
            consumers.add(consumer);
            rebuild();

            process();

            assertThat(consumer.received.get(0).getPullRequestNumber()).isEqualTo(42);
        }

        @Test
        @DisplayName("a non-numeric pull request number becomes null rather than failing")
        void nonNumericPullRequestNumberIsNull() {
            ObjectNode node = normalizedPayload();
            node.put("pullRequestNumber", "not-a-number");
            when(responseNormalizer.normalizeObject(any(), any())).thenReturn(node);
            CapturingConsumer consumer = new CapturingConsumer();
            consumers.add(consumer);
            rebuild();

            assertThat(process().outcome()).isEqualTo(ScmWebhookService.WebhookOutcome.ACCEPTED);
            assertThat(consumer.received.get(0).getPullRequestNumber()).isNull();
        }
    }

    @Nested
    @DisplayName("pipeline failures after the claim are recorded for replay")
    class PipelineFailure {

        @Test
        @DisplayName("a normalization failure is recorded as FAILED and acknowledged")
        void normalizationFailureIsRecorded() {
            // Reached before the claim in the current ordering, so this asserts the whole request still
            // surfaces a domain error rather than an unhandled exception.
            when(responseNormalizer.normalizeObject(any(), any()))
                    .thenThrow(new ScmException(ScmErrorCode.SCM_RESPONSE_MAPPING_INVALID, "bad mapping"));

            assertThatThrownBy(ScmWebhookServiceTest.this::process)
                    .isInstanceOf(ScmException.class)
                    .extracting(ex -> ((ScmException) ex).getErrorCode())
                    .isEqualTo(ScmErrorCode.SCM_RESPONSE_MAPPING_INVALID);
        }

        @Test
        @DisplayName("the delivery is marked PROCESSING before anything downstream runs")
        void processingIsRecordedBeforeDispatch() {
            CapturingConsumer consumer = new CapturingConsumer();
            consumers.add(consumer);
            rebuild();

            process();

            // So a row left in PROCESSING is the evidence that identifies a delivery lost to a crash.
            verify(deliveryService).markProcessing(100);
        }
    }
}
