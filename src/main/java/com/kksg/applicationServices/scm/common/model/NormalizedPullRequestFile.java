package com.kksg.applicationServices.scm.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Provider-independent view of one changed file in a pull request.
 *
 * <p>Providers differ structurally here, which is why {@code response_mapping} supports
 * <b>fallback field paths</b>: GitHub returns a flat {@code filename}, while Bitbucket's
 * {@code diffstat} returns {@code new.path} that is {@code null} for deletions and
 * {@code old.path} that is {@code null} for additions. The mapping declares
 * {@code "path": ["new.path", "old.path"]} and the engine takes the first non-null match, so no
 * Java branch is needed for either provider.
 *
 * <p>{@code patch} is optional: some providers do not return per-file hunks in the file listing
 * and require the separate {@code GET_PULL_REQUEST_DIFF} operation.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class NormalizedPullRequestFile {

    /** Current path of the file; for a deletion this is the pre-deletion path. */
    private String path;

    /** Populated for renames/moves so findings can be correlated across the rename. */
    private String previousPath;

    private FileChangeType changeType;

    private Integer additions;

    private Integer deletions;

    /** Unified-diff hunk for this file, when the provider includes it inline. */
    private String patch;
}
