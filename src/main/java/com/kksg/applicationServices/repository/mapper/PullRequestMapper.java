package com.kksg.applicationServices.repository.mapper;

import com.kksg.applicationServices.repository.dto.PullRequestAuthor;
import com.kksg.applicationServices.repository.dto.PullRequestFileResponse;
import com.kksg.applicationServices.repository.dto.PullRequestResponse;
import com.kksg.applicationServices.repository.dto.RepositoryRefResponse;
import com.kksg.applicationServices.scm.common.model.NormalizedPullRequest;
import com.kksg.applicationServices.scm.common.model.NormalizedPullRequestFile;
import com.kksg.applicationServices.scm.common.model.PullRequestState;
import com.kksg.applicationServices.scm.common.util.ScmInstants;

import java.time.Instant;
import java.util.Locale;

/**
 * Normalized pull request to response DTO.
 *
 * <p>Holds the module's one genuine derivation - resolving "merged" - and nothing else provider-aware.
 */
public final class PullRequestMapper {

    private PullRequestMapper() {
    }

    public static PullRequestResponse toResponse(NormalizedPullRequest pullRequest,
                                                 RepositoryRefResponse repository) {
        Instant mergedAt = ScmInstants.parse(pullRequest.getMergedAt());

        return PullRequestResponse.builder()
                .id(pullRequest.getExternalId())
                .number(pullRequest.getNumber())
                .title(blankToNull(pullRequest.getTitle()))
                .description(blankToNull(pullRequest.getDescription()))
                .state(resolveState(pullRequest.getState(), mergedAt))
                .author(toAuthor(pullRequest))
                .sourceBranch(blankToNull(pullRequest.getSourceBranch()))
                .targetBranch(blankToNull(pullRequest.getTargetBranch()))
                .sourceCommitSha(blankToNull(pullRequest.getSourceCommitSha()))
                .targetCommitSha(blankToNull(pullRequest.getTargetCommitSha()))
                .createdAt(ScmInstants.parse(pullRequest.getCreatedAt()))
                .updatedAt(ScmInstants.parse(pullRequest.getUpdatedAt()))
                .mergedAt(mergedAt)
                .webUrl(blankToNull(pullRequest.getWebUrl()))
                .repository(repository)
                .build();
    }

    /**
     * Settles whether a pull request is merged.
     *
     * <p>Providers model this incompatibly. Some report {@code MERGED} as a state of its own; others
     * report only {@code closed} and record the merge as a timestamp, so without this step a merged
     * pull request and an abandoned one would both read as {@code CLOSED} - and a user filtering by
     * "merged" would be shown pull requests labelled "closed".
     *
     * <p>The rule is expressed over <b>normalized facts</b>, not over a provider code: "closed, and we
     * know when it was merged" means merged, wherever it came from. A provider that already says
     * {@code MERGED} has no merge timestamp mapped and falls through unchanged, and one that says
     * {@code OPEN} is never reinterpreted - so the rule is safe for a provider nobody has added yet.
     *
     * <p>It cannot live in the declarative response mapping: that layer renames and coerces fields and
     * has no conditionals, which is a property worth keeping rather than working around.
     */
    static PullRequestState resolveState(PullRequestState reported, Instant mergedAt) {
        if (reported == PullRequestState.CLOSED && mergedAt != null) {
            return PullRequestState.MERGED;
        }
        return reported != null ? reported : PullRequestState.UNKNOWN;
    }

    private static PullRequestAuthor toAuthor(NormalizedPullRequest pullRequest) {
        String id = blankToNull(pullRequest.getAuthorExternalId());
        String username = blankToNull(pullRequest.getAuthorUsername());

        // Both absent is a real case - a pull request opened by a since-deleted account - and is
        // reported as no author rather than as an author with no name.
        return id == null && username == null ? null : new PullRequestAuthor(id, username);
    }

    public static PullRequestFileResponse toFileResponse(NormalizedPullRequestFile file) {
        return PullRequestFileResponse.of(
                file.getPath(),
                blankToNull(file.getPreviousPath()),
                file.getChangeType(),
                file.getAdditions(),
                file.getDeletions());
    }

    /**
     * Whether a pull request matches a search term.
     *
     * <p>Covers title, author and both branch names. Branches are included because a reviewer looking
     * for work frequently remembers the branch rather than the title, and the author because "what did
     * this person open" is a question the list is otherwise unable to answer. The description is
     * deliberately excluded: it is long free text, so including it would make nearly any common word
     * match nearly every pull request.
     */
    public static boolean matches(NormalizedPullRequest pullRequest, String lowerCaseTerm) {
        return containsIgnoringCase(pullRequest.getTitle(), lowerCaseTerm)
                || containsIgnoringCase(pullRequest.getAuthorUsername(), lowerCaseTerm)
                || containsIgnoringCase(pullRequest.getSourceBranch(), lowerCaseTerm)
                || containsIgnoringCase(pullRequest.getTargetBranch(), lowerCaseTerm)
                || matchesNumber(pullRequest.getNumber(), lowerCaseTerm);
    }

    /**
     * Lets a search term that is just a number find that pull request.
     *
     * <p>Exact rather than substring: searching "12" should not return #120, #124 and #1234, which is
     * what a reader typing a number is least likely to want.
     */
    private static boolean matchesNumber(Integer number, String term) {
        return number != null && String.valueOf(number).equals(stripLeadingHash(term));
    }

    private static String stripLeadingHash(String term) {
        return term.startsWith("#") ? term.substring(1) : term;
    }

    private static boolean containsIgnoringCase(String candidate, String lowerCaseTerm) {
        return candidate != null && candidate.toLowerCase(Locale.ROOT).contains(lowerCaseTerm);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
