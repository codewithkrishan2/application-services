package com.kksg.applicationServices.scm.event;

import com.kksg.applicationServices.scm.common.model.NormalizedWebhookEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Republishes every normalized event as a Spring application event.
 *
 * <p><b>Why this exists as a consumer rather than as a second publish call.</b> The documented way to
 * subscribe to this module has been {@code @EventListener(NormalizedWebhookEvent.class)}, and breaking
 * that to introduce {@link ScmWebhookEventConsumer} would be a gratuitous migration. Keeping the Spring
 * publication but doing it <i>through</i> the dispatcher means there is still exactly one dispatch path:
 * one place that logs, one place that isolates failures, one place a durable outbox would go. Calling
 * {@code publishEvent} alongside the dispatcher would have reintroduced two mechanisms with different
 * failure behaviour, which is the problem the dispatcher was added to solve.
 *
 * <p>Note what the isolation does and does not buy an {@code @EventListener}. This consumer is isolated
 * from the others, so a throwing listener can no longer stop a {@link ScmWebhookEventConsumer} from
 * running. Listeners are still not isolated from <i>each other</i>, because that is Spring's own
 * synchronous publication behaviour. A subscriber that wants to be independent of its peers should
 * implement {@link ScmWebhookEventConsumer}; the interface is the better route, and this one is
 * compatibility.
 */
@Component
public class ApplicationEventWebhookEventConsumer implements ScmWebhookEventConsumer {

    private final ApplicationEventPublisher applicationEventPublisher;

    public ApplicationEventWebhookEventConsumer(ApplicationEventPublisher applicationEventPublisher) {
        this.applicationEventPublisher = applicationEventPublisher;
    }

    @Override
    public void consume(NormalizedWebhookEvent event) {
        applicationEventPublisher.publishEvent(event);
    }

    @Override
    public String consumerName() {
        return "springApplicationEvent";
    }
}
