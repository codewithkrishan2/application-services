package com.kksg.applicationServices.repository.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.kksg.applicationServices.scm.common.model.FileChangeType;
import lombok.Builder;
import lombok.Getter;

/**
 * One changed file in a pull request.
 *
 * <p>Per-file hunks are deliberately <b>not</b> included even where a provider volunteers them inline,
 * because only some do. Serving the patch when it happens to be there would make this endpoint's
 * response shape depend on which provider answered, and a client written against the generous provider
 * would silently render nothing for the other. The diff endpoint is the single place patch content
 * comes from.
 *
 * <p>{@code changes} is the sum of additions and deletions, computed rather than read: providers that
 * publish it compute it the same way, and providers that do not would otherwise leave a client adding
 * two nullable numbers itself.
 */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PullRequestFileResponse {

    /** Current path; for a deletion, the path the file had before it was removed. */
    private final String path;

    /** Set for a rename or move, so a client can show both ends of it. */
    private final String previousPath;

    private final FileChangeType status;

    private final Integer additions;

    private final Integer deletions;

    /** {@code additions + deletions}, or {@code null} when the provider reported neither. */
    private final Integer changes;

    /**
     * @return a file row with {@code changes} derived, treating a missing count as zero only when the
     *         other is present. When both are absent the total stays {@code null} rather than becoming
     *         a confident zero for a file that certainly did change.
     */
    public static PullRequestFileResponse of(String path, String previousPath, FileChangeType status,
                                             Integer additions, Integer deletions) {
        Integer changes = additions == null && deletions == null
                ? null
                : (additions == null ? 0 : additions) + (deletions == null ? 0 : deletions);

        return PullRequestFileResponse.builder()
                .path(path)
                .previousPath(previousPath)
                .status(status != null ? status : FileChangeType.UNKNOWN)
                .additions(additions)
                .deletions(deletions)
                .changes(changes)
                .build();
    }
}
