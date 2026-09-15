package com.kksg.applicationServices.scm.common.model;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * Canonical pull-request lifecycle state.
 *
 * <p>Providers use different words for the same state ("open"/"OPEN", "closed"/"DECLINED",
 * "merged"/"MERGED"). The translation is expressed declaratively through the
 * {@code valueMappings} block of a {@code response_mapping}, so this enum stays small and no
 * provider name appears in Java code.
 *
 * <p>{@link #UNKNOWN} exists so that an unmapped provider value degrades to a harmless value
 * rather than failing the whole operation. Losing one field is preferable to failing a review.
 */
public enum PullRequestState {

    OPEN,
    CLOSED,
    MERGED,
    UNKNOWN;

    /**
     * Lenient deserialization: any value the mapping layer failed to translate becomes
     * {@link #UNKNOWN} instead of raising a Jackson error.
     */
    @JsonCreator
    public static PullRequestState fromCode(String code) {
        if (code == null) {
            return UNKNOWN;
        }
        for (PullRequestState value : values()) {
            if (value.name().equalsIgnoreCase(code.trim())) {
                return value;
            }
        }
        return UNKNOWN;
    }
}
