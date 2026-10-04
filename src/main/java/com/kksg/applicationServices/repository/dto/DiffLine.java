package com.kksg.applicationServices.repository.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One rendered line of a diff.
 *
 * <p>Both line numbers are present and either may be {@code null}: an added line has no number in the
 * old file and a removed line has none in the new one. Computing them server-side rather than leaving
 * a client to count from the hunk header is what lets the same payload drive a unified view and a
 * side-by-side one, and it is where off-by-one bugs would otherwise be reinvented per client.
 *
 * <p>{@code content} is the line <b>without</b> its leading {@code +}, {@code -} or space marker. The
 * marker is redundant once {@link #type()} exists, and leaving it in would force every client to strip
 * it before measuring indentation or matching text.
 *
 * @param type          whether the line was added, removed or is unchanged context.
 * @param content       line text, marker stripped. Never {@code null}; an empty line is {@code ""}.
 * @param oldLineNumber 1-based line number in the old file, or {@code null} for an addition.
 * @param newLineNumber 1-based line number in the new file, or {@code null} for a deletion.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DiffLine(DiffLineType type, String content,
                       Integer oldLineNumber, Integer newLineNumber) {
}
