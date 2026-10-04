package com.kksg.applicationServices.repository.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.kksg.applicationServices.scm.common.model.PullRequestState;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

/**
 * A pull request, as this API reports it.
 *
 * <p><b>{@code number}, not {@code id}, is the address.</b> On at least one provider these are
 * different integers and the global {@code id} is not accepted in a pull-request URL, so every nested
 * route in this module is keyed by {@code number}. {@code id} is retained for correlation only.
 *
 * <p>{@code state} is the canonical {@link PullRequestState}, with "merged" resolved rather than left
 * to the client. Providers disagree on whether merged is a state or a closed pull request with a merge
 * timestamp; the mapper settles it from {@code mergedAt} so a client never has to, and so the state
 * shown always matches the state filtered on.
 *
 * <p>{@code repository} is populated on the detail response and omitted from list rows, where it would
 * repeat identically on every item.
 */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PullRequestResponse {

    /** Provider's global identifier. Correlation only; see the class note. */
    private final String id;

    /** The addressable, user-visible number. */
    private final Integer number;

    private final String title;

    /** Author-written body. May be long, may be {@code null}; never interpreted here. */
    private final String description;

    private final PullRequestState state;

    private final PullRequestAuthor author;

    private final String sourceBranch;

    private final String targetBranch;

    /** Head commit of the source branch, when the provider supplies it. */
    private final String sourceCommitSha;

    /** Base commit of the target branch, when the provider supplies it. */
    private final String targetCommitSha;

    private final Instant createdAt;

    private final Instant updatedAt;

    /** Set only for a merged pull request, and only where the provider reports it. */
    private final Instant mergedAt;

    private final String webUrl;

    /** Present on the detail response; omitted from list rows. */
    private final RepositoryRefResponse repository;
}
