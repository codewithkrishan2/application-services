package com.kksg.applicationServices.repository.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * A contiguous changed region of one file, as delimited by an {@code @@} header.
 *
 * <p>Hunks are kept as a list rather than being flattened into one line sequence per file, because the
 * gap between two hunks is meaningful - it is skipped, unchanged code - and a flat list would either
 * lose that boundary or need a synthetic separator line. Keeping the structure lets a client draw the
 * "…" divider every diff viewer has.
 *
 * @param header     the raw {@code @@ -a,b +c,d @@ section} line, preserved because the trailing
 *                   section heading some providers emit is genuinely useful context and is not
 *                   derivable from anything else.
 * @param oldStart   first line number this hunk covers in the old file.
 * @param oldLines   number of old-file lines covered.
 * @param newStart   first line number this hunk covers in the new file.
 * @param newLines   number of new-file lines covered.
 * @param lines      the hunk's lines, in order.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DiffHunk(String header, int oldStart, int oldLines, int newStart, int newLines,
                       List<DiffLine> lines) {
}
