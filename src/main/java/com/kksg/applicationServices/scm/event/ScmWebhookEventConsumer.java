package com.kksg.applicationServices.scm.event;

import com.kksg.applicationServices.scm.common.model.NormalizedEventType;
import com.kksg.applicationServices.scm.common.model.NormalizedWebhookEvent;

/**
 * The extension point by which anything outside this module acts on a webhook delivery.
 *
 * <p><b>Why this exists when a Spring {@code @EventListener} would already work.</b> It would, and it
 * still does - {@link ApplicationEventWebhookEventConsumer} keeps that route open. But a bare listener
 * leaves three things undefined, and all three matter once a second subscriber exists:
 * <ul>
 *   <li><b>Who subscribed.</b> With {@code @EventListener} the set of subscribers is discoverable only
 *       by grepping. A declared interface means {@link ScmWebhookEventDispatcher} can log how many
 *       consumers saw a delivery and name the one that failed.</li>
 *   <li><b>Whose fault a failure is.</b> Spring's synchronous publication aborts the remaining
 *       listeners on the first exception, and the webhook pipeline then records the whole delivery as
 *       failed. One subscriber's bug silently suppresses every other subscriber.</li>
 *   <li><b>What a consumer is allowed to assume.</b> Nothing stated the idempotency requirement below,
 *       which is the one rule a consumer must follow to be safe to replay.</li>
 * </ul>
 *
 * <p><b>Required contract.</b>
 * <ul>
 *   <li><b>Be idempotent on {@link NormalizedWebhookEvent#getDeliveryId()}.</b> A delivery that any
 *       consumer fails is recorded {@code FAILED} and may be replayed by a provider retry or an
 *       operator, which re-invokes the consumers that already succeeded. Keying work on the delivery
 *       id is what makes that safe.</li>
 *   <li><b>Throw to request a replay, return normally otherwise.</b> An exception marks the delivery
 *       {@code FAILED} so it can be retried. A consumer whose failure should <i>not</i> hold up the
 *       delivery - a metrics sink, say - must catch its own errors.</li>
 *   <li><b>Do not block.</b> Consumers run on the webhook request thread, inside the window the
 *       provider is waiting on. Queue the work; do not perform it here.</li>
 *   <li><b>Do not branch on {@link NormalizedWebhookEvent#getProviderCode()}.</b> It is there for logs
 *       and metrics. Needing it to decide behaviour means the normalization is incomplete, and the fix
 *       belongs in the provider's event mapping.</li>
 * </ul>
 *
 * <p>This module publishes events. It does not decide what they mean, and it never starts a review.
 */
public interface ScmWebhookEventConsumer {

    /**
     * Whether this consumer wants the given event type.
     *
     * <p>Declaring interest here rather than returning early inside {@link #consume} is what lets the
     * dispatcher report "no consumer wanted this event" as a distinct, visible outcome instead of it
     * looking identical to a successful dispatch.
     *
     * @param eventType the canonical type; never null, because an unmapped provider event is recorded
     *                  {@code IGNORED} before dispatch is ever reached.
     */
    default boolean supports(NormalizedEventType eventType) {
        return true;
    }

    /**
     * Acts on the event.
     *
     * @throws RuntimeException to mark the delivery {@code FAILED} and make it replayable.
     */
    void consume(NormalizedWebhookEvent event);

    /**
     * Short stable name used in dispatch logs and failure reasons.
     *
     * <p>Defaults to the implementing class's simple name, which is almost always what you want. It is
     * overridable so a proxied or generated consumer can still identify itself usefully.
     */
    default String consumerName() {
        return getClass().getSimpleName();
    }
}
