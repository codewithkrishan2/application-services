package com.kksg.applicationServices.scm.event;

import com.kksg.applicationServices.scm.common.model.NormalizedWebhookEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Publishes normalized SCM events to the rest of the application.
 *
 * <p><b>This is Module 2's outbound boundary.</b> Everything downstream - Module 4 (Review
 * Orchestration) included - sees provider-agnostic events and nothing else. No compile-time dependency
 * runs from this module to any consumer, so orchestration can be built, changed or replaced without
 * touching SCM integration. There are two ways to subscribe:
 * <ul>
 *   <li>Implement {@link ScmWebhookEventConsumer} - preferred. The consumer is isolated from its peers,
 *       named in dispatch logs, and can declare which event types it wants.</li>
 *   <li>{@code @EventListener(NormalizedWebhookEvent.class)} - still supported, via
 *       {@link ApplicationEventWebhookEventConsumer}.</li>
 * </ul>
 *
 * <p><b>Why this stayed a thin wrapper instead of being replaced by the dispatcher.</b> It keeps one
 * audited log line for every event leaving the module, and it keeps callers - today only the webhook
 * pipeline - unaware of how dispatch happens. The isolation and fan-out moved into
 * {@link ScmWebhookEventDispatcher}, which is where a durable outbox would be introduced.
 *
 * <p><b>Delivery semantics, stated plainly.</b> Dispatch is synchronous and in-process: consumers run on
 * the webhook request thread, and an in-flight event is lost if the process dies. That is acceptable for
 * the MVP because the provider's own retry plus the delivery record in {@code scm_webhook_deliveries}
 * together allow recovery - a delivery stuck in {@code RECEIVED}, {@code PROCESSING} or {@code FAILED}
 * can be replayed. A durable outbox is the documented next step, not a silent assumption.
 */
@Component
public class ScmEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(ScmEventPublisher.class);

    private final ScmWebhookEventDispatcher dispatcher;

    public ScmEventPublisher(ScmWebhookEventDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    /**
     * @return the per-consumer outcome. Returned rather than thrown because the caller has already
     *         claimed the delivery and must record the result on it; see
     *         {@link ScmWebhookEventDispatcher}.
     */
    public ScmWebhookEventDispatcher.DispatchResult publish(NormalizedWebhookEvent event) {
        log.info("SCM_EVENT_PUBLISHED: providerCode={}, eventType={}, deliveryId={}, connectionId={}, "
                        + "repositoryExternalId={}, pullRequestNumber={}",
                event.getProviderCode(), event.getEventType(), event.getDeliveryId(), event.getConnectionId(),
                event.getRepositoryExternalId(), event.getPullRequestNumber());

        return dispatcher.dispatch(event);
    }
}
