package com.kksg.applicationServices.scm.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Provider-independent view of a pull request (GitHub) / pull request (Bitbucket) /
 * merge request (GitLab).
 *
 * <p>{@code number} is the user-visible identifier used to address the PR in subsequent API
 * calls. It is kept separate from {@code externalId} because on GitHub the addressable value is
 * {@code number} while {@code id} is a different global integer; conflating them produces 404s.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class NormalizedPullRequest {

    private String externalId;

    /** Identifier used when addressing this PR in provider URLs. */
    private Integer number;

    private String title;

    private String description;

    /** Canonical lifecycle state; see {@link PullRequestState}. */
    private PullRequestState state;

    private String sourceBranch;

    private String targetBranch;

    /** Head commit SHA of the source branch, when the provider supplies it. */
    private String sourceCommitSha;

    /** Base commit SHA of the target branch, when the provider supplies it. */
    private String targetCommitSha;

    private String authorExternalId;

    private String authorUsername;

    private String webUrl;

    private String createdAt;

    private String updatedAt;

    /**
     * When the pull request was merged, ISO-8601, or {@code null} if it was not.
     *
     * <p>Present because providers disagree on whether "merged" is a <i>state</i> or an <i>event</i>.
     * Some report {@code state=MERGED} directly; others report {@code state=closed} and record the
     * merge separately, so without this field a merged pull request is indistinguishable from an
     * abandoned one. The declarative mapping cannot express "closed plus a merge timestamp means
     * merged" - it has no conditionals - so the fact is normalized here and the single derivation rule
     * lives in one place in the consuming module, which keeps it provider-agnostic.
     */
    private String mergedAt;
}
