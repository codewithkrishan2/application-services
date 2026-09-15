package com.kksg.applicationServices.scm.webhook.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Acknowledgement returned to a provider after a webhook delivery.
 *
 * <p>Kept minimal deliberately. The recipient is an automated provider that only really cares about the
 * status code, and anything richer would be a disclosure channel to an endpoint reachable by anyone who
 * learns the URL. The delivery id is echoed because it is the provider's own value and makes correlating
 * their delivery log with ours straightforward during support.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScmWebhookAckResponse {

    /** One of {@code ACCEPTED}, {@code DUPLICATE}, {@code IGNORED}, {@code FAILED}. */
    private String outcome;

    /** The provider's delivery identifier, echoed for correlation. */
    private String deliveryId;

    /** Normalized event type, when the event was mapped. */
    private String eventType;
}
