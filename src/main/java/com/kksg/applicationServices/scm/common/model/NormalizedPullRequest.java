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
}
