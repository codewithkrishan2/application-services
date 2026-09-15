package com.kksg.applicationServices.scm.common.model;

/**
 * Platform-level webhook event vocabulary.
 *
 * <p>Providers describe the same real-world occurrence very differently. GitHub sends
 * {@code X-GitHub-Event: pull_request} with a body {@code {"action":"opened"}}; Bitbucket sends
 * {@code X-Event-Key: pullrequest:created} with no separate action. Both must arrive in Module 4
 * as {@link #PULL_REQUEST_OPENED}.
 *
 * <p>The mapping itself is data ({@code scm_provider_events}), so supporting a new provider's
 * event names is an insert, not a code change.
 */
public enum NormalizedEventType {

    PULL_REQUEST_OPENED,
    PULL_REQUEST_UPDATED,
    PULL_REQUEST_REOPENED,
    PULL_REQUEST_CLOSED;

    public static NormalizedEventType fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (NormalizedEventType value : values()) {
            if (value.name().equalsIgnoreCase(code.trim())) {
                return value;
            }
        }
        return null;
    }
}
