package com.kksg.applicationServices.scm.event;

import com.kksg.applicationServices.scm.common.model.NormalizedWebhookEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Hands a normalized event to every interested {@link ScmWebhookEventConsumer}, in isolation.
 *
 * <p><b>Isolation is the whole point.</b> Previously the pipeline called
 * {@code ApplicationEventPublisher.publishEvent} directly. Spring publishes synchronously, and the
 * first listener to throw aborts the remaining listeners and propagates. So a bug in one subscriber
 * meant every other subscriber silently stopped receiving events, and the only evidence was a delivery
 * marked {@code FAILED} with no indication of which subscriber was at fault. Here each consumer is
 * invoked in its own try/catch, every consumer runs regardless of its neighbours, and the failures are
 * returned named.
 *
 * <p><b>Why failures are returned rather than thrown.</b> The webhook pipeline has already persisted
 * and claimed the delivery by this point, and it must answer the provider with a 2xx either way - a
 * non-2xx would trigger a retry carrying the same delivery id, which the claim rejects as a duplicate,
 * so the retry could never succeed. The pipeline therefore needs the outcome as a <i>value</i> it can
 * record on the delivery row, not as an exception that would unwind past that record.
 *
 * <p><b>Consumers are discovered, not registered.</b> Spring injects every {@link
 * ScmWebhookEventConsumer} bean, so Module 4 adds one class and is wired in. No compile-time dependency
 * runs from this module to any consumer, which is what keeps orchestration replaceable.
 *
 * <p><b>Delivery semantics, stated plainly.</b> Dispatch is synchronous and in-process, so an event
 * being dispatched when the process dies is lost. That is survivable rather than ignored: the delivery
 * row persists in {@code scm_webhook_deliveries}, and a row left in {@code RECEIVED} or {@code
 * PROCESSING} is exactly the evidence needed to replay it. A durable outbox is the documented next step,
 * and this class is the single place it would be introduced.
 */
@Component
public class ScmWebhookEventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ScmWebhookEventDispatcher.class);

    /** Keeps an aggregated failure reason inside the delivery row's error column. */
    private static final int MAX_REASON_LENGTH = 500;

    private final List<ScmWebhookEventConsumer> consumers;

    /**
     * @param consumers every consumer bean in the context. Empty is a legitimate state, not a
     *                  misconfiguration - it is what a deployment without Review Orchestration looks
     *                  like, and the webhook pipeline must keep working there.
     */
    public ScmWebhookEventDispatcher(List<ScmWebhookEventConsumer> consumers) {
        this.consumers = List.copyOf(consumers);
    }

    public DispatchResult dispatch(NormalizedWebhookEvent event) {
        List<String> failures = new ArrayList<>();
        int interested = 0;
        int succeeded = 0;

        for (ScmWebhookEventConsumer consumer : consumers) {
            String name = safeName(consumer);

            // A consumer that cannot even answer supports() is broken, not uninterested. Counting it as
            // a failure keeps a silent misconfiguration from looking like "nobody subscribed".
            boolean wants;
            try {
                wants = consumer.supports(event.getEventType());
            } catch (RuntimeException ex) {
                failures.add(name + ":supports/" + ex.getClass().getSimpleName());
                log.error("SCM_EVENT_CONSUMER_SELECTION_FAILED: consumer={}, deliveryId={}, eventType={}",
                        name, event.getDeliveryId(), event.getEventType(), ex);
                continue;
            }

            if (!wants) {
                continue;
            }
            interested++;

            try {
                consumer.consume(event);
                succeeded++;
                log.debug("SCM_EVENT_CONSUMED: consumer={}, deliveryId={}, eventType={}",
                        name, event.getDeliveryId(), event.getEventType());

            } catch (RuntimeException ex) {
                // Named, so a failing delivery points at the subscriber responsible. The exception type
                // only - a consumer's message may quote payload content.
                failures.add(name + "/" + ex.getClass().getSimpleName());
                log.error("SCM_EVENT_CONSUMER_FAILED: consumer={}, deliveryId={}, eventType={}, "
                                + "repositoryFullName={}, pullRequestNumber={}",
                        name, event.getDeliveryId(), event.getEventType(),
                        event.getRepositoryFullName(), event.getPullRequestNumber(), ex);
            }
        }

        DispatchResult result = new DispatchResult(interested, succeeded, List.copyOf(failures));

        if (interested == 0) {
            // Not an error. It is the normal state before Review Orchestration exists, and it is worth a
            // log line because the alternative - looking identical to a successful dispatch - is how a
            // deployment quietly stops acting on pull requests.
            log.info("SCM_EVENT_NO_CONSUMER: deliveryId={}, eventType={}, providerCode={}, registered={}",
                    event.getDeliveryId(), event.getEventType(), event.getProviderCode(), consumers.size());
        } else {
            log.info("SCM_EVENT_DISPATCHED: deliveryId={}, eventType={}, providerCode={}, "
                            + "consumers={}, succeeded={}, failed={}",
                    event.getDeliveryId(), event.getEventType(), event.getProviderCode(),
                    interested, succeeded, failures.size());
        }

        return result;
    }

    /** A consumer whose own name accessor throws must not be able to abort the dispatch loop. */
    private String safeName(ScmWebhookEventConsumer consumer) {
        try {
            String name = consumer.consumerName();
            return name == null || name.isBlank() ? consumer.getClass().getName() : name;
        } catch (RuntimeException ex) {
            return consumer.getClass().getName();
        }
    }

    /**
     * @param consumersInvoked how many consumers declared interest in the event.
     * @param succeeded        how many of those returned normally.
     * @param failures         {@code consumerName/ExceptionType} for each failure, in invocation order.
     */
    public record DispatchResult(int consumersInvoked, int succeeded, List<String> failures) {

        public boolean hasFailures() {
            return !failures.isEmpty();
        }

        /** True when the event was dispatched but nothing was subscribed to it. */
        public boolean noConsumers() {
            return consumersInvoked == 0;
        }

        /**
         * A short, non-sensitive summary suitable for the delivery row's error column.
         */
        public String failureReason() {
            String joined = String.join(",", failures);
            return joined.length() <= MAX_REASON_LENGTH ? joined : joined.substring(0, MAX_REASON_LENGTH);
        }
    }
}
