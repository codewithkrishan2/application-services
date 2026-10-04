package com.kksg.applicationServices.repository.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * A pull request's diff, parsed into a structure a client can render directly.
 *
 * <p>The provider returns unified-diff <b>text</b>. Parsing it here rather than shipping the raw text
 * is a deliberate division of labour: the parse is identical for every provider and every client, it
 * is the part with the fiddly line-numbering arithmetic, and doing it server-side means a browser never
 * receives a multi-megabyte string it has to walk before it can show the first file. It also means the
 * size limit is enforced where the memory is, instead of being discovered by the slowest client.
 *
 * <p>This is <b>not</b> a review format. There are no findings, comments, severities or suggestions -
 * only what changed. A later module will consume this; nothing here anticipates its shape.
 *
 * @see com.kksg.applicationServices.repository.diff.UnifiedDiffParser
 */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PullRequestDiffResponse {

    private final Integer pullRequestNumber;

    private final List<DiffFile> files;

    private final int totalFiles;

    private final int totalAdditions;

    private final int totalDeletions;

    /**
     * True when the diff exceeded the parse budget and some files were dropped or left without hunks.
     *
     * <p>Reported rather than hidden because a diff viewer showing 40 of 900 changed files without
     * saying so is actively misleading - a reader would conclude the pull request is small.
     */
    private final boolean truncated;
}
