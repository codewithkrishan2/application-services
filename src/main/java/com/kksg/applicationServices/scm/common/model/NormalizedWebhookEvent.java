package com.kksg.applicationServices.scm.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Map;

/**
 * The output of this module's webhook pipeline and the input to Module 4 (Review Orchestration).
 *
 * <p>Published as a Spring application event by
 * {@code com.kksg.applicationServices.scm.event.ScmEventPublisher}. Module 4 can subscribe with a
 * plain {@code @EventListener} and never has to know which provider delivered the webhook.
 *
 * <p>{@code rawPayload} is carried along deliberately: orchestration occasionally needs a field
 * the MVP mapping does not extract yet. It is the one intentional escape hatch, and using it
 * couples the consumer to a provider - so it should be a last resort, and a new mapping entry is
 * the preferred fix.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NormalizedWebhookEvent {

    private NormalizedEventType eventType;

    /** Stable provider code, e.g. {@code GITHUB}. Useful for logging and metrics, not branching. */
    private String providerCode;

    /** Internal id of the persisted {@code scm_webhook_deliveries} row. */
    private Integer deliveryRecordId;

    /** Provider-supplied delivery identifier, used for idempotency and traceability. */
    private String deliveryId;

    /** Internal id of the {@code scm_connections} row this delivery was attributed to, if resolved. */
    private Integer connectionId;

    private String repositoryExternalId;

    private String repositoryFullName;

    /** Addressable pull request number, when the event concerns a pull request. */
    private Integer pullRequestNumber;

    /** Raw provider event name/action pair, retained for diagnostics. */
    private String providerEventName;

    private String providerAction;

    private Map<String, Object> rawPayload;
}
