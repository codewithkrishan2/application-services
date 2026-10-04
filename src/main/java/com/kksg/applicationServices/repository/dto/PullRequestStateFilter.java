package com.kksg.applicationServices.repository.dto;

import com.kksg.applicationServices.scm.common.exception.ScmErrorCode;
import com.kksg.applicationServices.scm.common.exception.ScmException;

import java.util.Arrays;
import java.util.Locale;

/**
 * The canonical pull-request state a client may filter by.
 *
 * <p>Separate from {@code PullRequestState} even though three of the four values coincide, because the
 * two are different vocabularies. {@code PullRequestState} describes one pull request and therefore has
 * {@code UNKNOWN}; this describes a <i>query</i> and therefore has {@code ALL} and must not have
 * {@code UNKNOWN} - "show me the pull requests whose state we failed to recognise" is not a request
 * anyone makes, and accepting it would mean sending a meaningless filter to a provider.
 *
 * <p>The translation from these values to a provider's own spelling is configuration, not code: each
 * provider's {@code LIST_PULL_REQUESTS} operation declares {@code parameterValueMappings} for
 * {@code state}. That is what lets this enum be passed straight through to the operation engine.
 */
public enum PullRequestStateFilter {

    OPEN,
    CLOSED,
    MERGED,

    /** No state restriction. */
    ALL;

    /**
     * Parses the {@code state} query parameter.
     *
     * <p>An absent or blank value means {@link #OPEN} rather than {@link #ALL}: open pull requests are
     * what a reviewer arrives to look at, and defaulting to everything would make the first page of a
     * long-lived repository a list of history.
     *
     * @throws ScmException {@link ScmErrorCode#SCM_REQUEST_INVALID} for an unrecognised value. Rejected
     *         rather than silently defaulted, because a client that misspells a filter would otherwise
     *         be shown a plausible list that does not answer its question.
     */
    public static PullRequestStateFilter parse(String value) {
        if (value == null || value.isBlank()) {
            return OPEN;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(candidate -> candidate.name().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new ScmException(ScmErrorCode.SCM_REQUEST_INVALID,
                        "state must be one of %s".formatted(Arrays.toString(values()))));
    }
}
