package com.kksg.applicationServices.scm.webhook.entity;

/**
 * Processing state of a received webhook delivery.
 *
 * <p>{@link #IGNORED} is distinct from {@link #PROCESSED} on purpose. Providers send events nobody
 * subscribed to - GitHub sends a {@code ping} the moment a webhook is created - and recording those as
 * {@code PROCESSED} would make the table useless for answering "did we act on this?". Recording them as
 * {@code FAILED} would be worse: it implies something needs fixing and would pollute failure alerting.
 */
public enum ScmWebhookDeliveryStatus {

    /** Persisted and accepted; not yet acted upon. */
    RECEIVED,

    /** Being normalized and forwarded. */
    PROCESSING,

    /** Normalized and published successfully. */
    PROCESSED,

    /** Recognised but intentionally not acted upon, e.g. an unmapped event. */
    IGNORED,

    /** Processing raised an error; retained for diagnosis and possible replay. */
    FAILED
}
