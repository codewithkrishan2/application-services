package com.kksg.applicationServices.repository.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.kksg.applicationServices.scm.common.model.FileChangeType;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * One file within a parsed diff, together with its hunks.
 *
 * <p>{@code status} is derived from the diff itself - from the {@code new file mode} /
 * {@code deleted file mode} markers and the {@code ---} / {@code +++} paths - rather than taken from
 * the changed-files endpoint. The diff is self-describing, and reading it this way means the diff
 * endpoint needs no second provider call to label its own content.
 *
 * <p>{@code binary} and {@code truncated} both mean "there are no hunks here", for different reasons,
 * and a client must distinguish them: a binary file has no textual diff to show and never will, while
 * a truncated file has one that was too large to include. Collapsing them into an empty hunk list
 * would render a size limit as if the file had not changed.
 */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DiffFile {

    /** Current path; for a deletion, the path before removal. */
    private final String path;

    /** Set for a rename or move. */
    private final String previousPath;

    private final FileChangeType status;

    private final int additions;

    private final int deletions;

    /** True when the provider reported a binary difference, so there is nothing textual to render. */
    private final boolean binary;

    /** True when this file's hunks were dropped because the parse budget was exhausted. */
    private final boolean truncated;

    private final List<DiffHunk> hunks;
}
