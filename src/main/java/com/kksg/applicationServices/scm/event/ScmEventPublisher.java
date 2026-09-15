package com.kksg.applicationServices.scm.event;

import com.kksg.applicationServices.scm.common.model.NormalizedWebhookEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Publishes normalized SCM events to the rest of the application.
 *
 * <p><b>This is Module 2's outbound boundary.</b> Module 4 (Review Orchestration) subscribes with a
 * plain {@code @EventListener(NormalizedWebhookEvent.class)} and receives provider-agnostic events. No
 * compile-time dependency runs from this module to Module 4, so orchestration can be built, changed or
 * replaced without touching SCM integration.
 *
 * <p><b>Why a thin wrapper over {@link ApplicationEventPublisher}.</b> It gives one place to add
 * transactional-outbox persistence or a message broker later, and it keeps a single audited log line for
 * every event leaving the module. Both changes would otherwise have to be made at every publish site.
 *
 * <p><b>Delivery semantics, stated plainly.</b> Spring's default publication is synchronous and
 * in-process: listeners run on the webhook request thread, and an in-flight event is lost if the process
 * dies. That is acceptable for the MVP because the provider's own retry plus the delivery record in
 * {@code scm_webhook_deliveries} together allow recovery - a delivery stuck in {@code RECEIVED} or
 * {@code FAILED} can be replayed. A durable outbox is the documented next step, not a silent assumption.
 */
@Component
public class ScmEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(ScmEventPublisher.class);

    private final ApplicationEventPublisher applicationEventPublisher;

    public ScmEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        this.applicationEventPublisher = applicationEventPublisher;
    }

    public void publish(NormalizedWebhookEvent event) {
        log.info("SCM_EVENT_PUBLISHED: providerCode={}, eventType={}, deliveryId={}, connectionId={}, "
                        + "repositoryExternalId={}, pullRequestNumber={}",
                event.getProviderCode(), event.getEventType(), event.getDeliveryId(), event.getConnectionId(),
                event.getRepositoryExternalId(), event.getPullRequestNumber());

        applicationEventPublisher.publishEvent(event);
    }
}
