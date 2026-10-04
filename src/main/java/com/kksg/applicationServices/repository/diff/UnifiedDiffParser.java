package com.kksg.applicationServices.repository.diff;

import com.kksg.applicationServices.repository.RepositoryManagementProperties;
import com.kksg.applicationServices.repository.dto.DiffFile;
import com.kksg.applicationServices.repository.dto.DiffHunk;
import com.kksg.applicationServices.repository.dto.DiffLine;
import com.kksg.applicationServices.repository.dto.DiffLineType;
import com.kksg.applicationServices.repository.dto.PullRequestDiffResponse;
import com.kksg.applicationServices.scm.common.model.FileChangeType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses unified-diff text into the structure a diff viewer needs.
 *
 * <p><b>Why parse at all, rather than forward the text.</b> Both configured providers return a diff as
 * {@code text/plain}, and both return the <i>same</i> format - git's unified diff - because both are
 * git. So the parse is provider-independent, which makes it exactly the kind of work that belongs on
 * this side of the boundary: done once here, or reimplemented in every client. Doing it here also puts
 * the line-numbering arithmetic, which is where off-by-one bugs live, in one tested place, and puts the
 * size limit where the memory actually is.
 *
 * <p><b>What it reads and what it ignores.</b> A git diff carries more than hunks: mode changes, blob
 * indexes, similarity scores. Those say nothing about <i>what changed in the file</i>, so they are
 * skipped rather than modelled. The markers that do carry meaning are read:
 *
 * <pre>
 *   diff --git a/path b/path     file boundary
 *   new file mode / deleted file mode    change type
 *   rename from / rename to              change type and previous path
 *   --- a/path  /  +++ b/path            authoritative paths (and /dev/null for add or delete)
 *   Binary files ... differ              there is no text diff to show
 *   &#64;&#64; -a,b +c,d &#64;&#64; section        hunk bounds
 * </pre>
 *
 * <p><b>Paths come from the {@code ---}/{@code +++} lines in preference to the {@code diff --git}
 * line.</b> The {@code diff --git} line concatenates both paths separated by a space, so a file whose
 * name contains a space makes it genuinely ambiguous; the two single-path lines never are. The
 * {@code diff --git} line is still read first, as a fallback for the rare entry that has no
 * {@code ---}/{@code +++} pair - a pure mode change, or a binary file on some providers.
 *
 * <p><b>Tolerant by design.</b> A line it does not recognise is skipped, not rejected. A diff is
 * decoration around a pull request rather than a transaction: half a rendered diff is useful, and a
 * parse exception that blanked the page because a provider emitted an unexpected header would not be.
 * The one thing it will not do is silently drop content without saying so - see {@code truncated}.
 *
 * <p>Combined diffs ({@code @@@}, produced for merge-commit comparisons) are not parsed. They have a
 * different column structure, neither provider returns one for a pull-request diff, and guessing at it
 * would produce plausible-looking wrong line numbers.
 */
@Component
public class UnifiedDiffParser {

    /** {@code @@ -oldStart,oldLines +newStart,newLines @@ optional section heading} */
    private static final Pattern HUNK_HEADER =
            Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@(.*)$");

    /** {@code diff --git a/old b/new} - read only as a fallback; see the class note. */
    private static final Pattern GIT_FILE_HEADER = Pattern.compile("^diff --git a/(.+) b/(.+)$");

    private static final String NO_FILE = "/dev/null";

    private final RepositoryManagementProperties properties;

    public UnifiedDiffParser(RepositoryManagementProperties properties) {
        this.properties = properties;
    }

    /**
     * Parses a diff.
     *
     * @param diffText          raw provider response; may be {@code null} or empty, which is a valid
     *                          state for a pull request with no changes and yields an empty result
     *                          rather than an error.
     * @param pullRequestNumber echoed into the response so a cached or forwarded payload identifies
     *                          itself.
     */
    public PullRequestDiffResponse parse(String diffText, Integer pullRequestNumber) {
        int maxLines = Math.max(1, properties.getDiff().getMaxLines());
        int maxFiles = Math.max(1, properties.getDiff().getMaxFiles());

        List<DiffFile> files = new ArrayList<>();
        ParseState state = new ParseState(maxLines, maxFiles);

        if (diffText != null && !diffText.isBlank()) {
            // splitWithoutLimit drops trailing empty strings, which is what we want: a diff ends with a
            // newline and a final empty element is not a line of the file.
            for (String raw : diffText.split("\r\n|\n|\r")) {
                state.consume(raw, files);
            }
        }
        state.flush(files);

        int totalAdditions = files.stream().mapToInt(DiffFile::getAdditions).sum();
        int totalDeletions = files.stream().mapToInt(DiffFile::getDeletions).sum();

        return PullRequestDiffResponse.builder()
                .pullRequestNumber(pullRequestNumber)
                .files(files)
                .totalFiles(files.size())
                .totalAdditions(totalAdditions)
                .totalDeletions(totalDeletions)
                .truncated(state.truncated)
                .build();
    }

    /**
     * The parser's working state.
     *
     * <p>An inner class holding mutable fields rather than a stream pipeline, because a unified diff is
     * inherently sequential: a {@code +} line means nothing without the hunk header above it, and the
     * line numbers depend on every line already seen. Writing that as folds would obscure it.
     */
    private static final class ParseState {

        private final int maxLines;
        private final int maxFiles;

        private String path;
        private String previousPath;
        private FileChangeType changeType;
        private boolean binary;
        private boolean fileTruncated;
        private int additions;
        private int deletions;
        private List<DiffHunk> hunks = new ArrayList<>();

        private HunkState hunk;

        private int linesParsed;
        private boolean truncated;

        private ParseState(int maxLines, int maxFiles) {
            this.maxLines = maxLines;
            this.maxFiles = maxFiles;
        }

        private void consume(String raw, List<DiffFile> files) {
            Matcher fileHeader = GIT_FILE_HEADER.matcher(raw);
            if (fileHeader.matches()) {
                flush(files);
                // Both captures are best-effort: the space separator is ambiguous for paths containing
                // spaces, so these are overwritten by the ---/+++ lines whenever those appear.
                previousPath = null;
                path = fileHeader.group(2);
                return;
            }

            if (raw.startsWith("--- ")) {
                String candidate = stripPathPrefix(raw.substring(4));
                if (NO_FILE.equals(candidate)) {
                    changeType = FileChangeType.ADDED;
                } else if (path == null) {
                    // A plain unified diff with no "diff --git" preamble: the old path is all there is
                    // until the +++ line arrives.
                    path = candidate;
                }
                return;
            }

            if (raw.startsWith("+++ ")) {
                String candidate = stripPathPrefix(raw.substring(4));
                if (NO_FILE.equals(candidate)) {
                    changeType = FileChangeType.REMOVED;
                } else {
                    path = candidate;
                }
                return;
            }

            if (raw.startsWith("new file mode")) {
                changeType = FileChangeType.ADDED;
                return;
            }
            if (raw.startsWith("deleted file mode")) {
                changeType = FileChangeType.REMOVED;
                return;
            }
            if (raw.startsWith("rename from ")) {
                previousPath = stripPathPrefix(raw.substring("rename from ".length()));
                changeType = FileChangeType.RENAMED;
                return;
            }
            if (raw.startsWith("rename to ")) {
                path = stripPathPrefix(raw.substring("rename to ".length()));
                changeType = FileChangeType.RENAMED;
                return;
            }
            if (raw.startsWith("Binary files ") || raw.startsWith("GIT binary patch")) {
                binary = true;
                return;
            }

            Matcher hunkHeader = HUNK_HEADER.matcher(raw);
            if (hunkHeader.matches()) {
                closeHunk();
                hunk = HunkState.from(hunkHeader);
                return;
            }

            if (hunk == null || raw.isEmpty()) {
                // Outside a hunk: an index line, a mode line, a similarity score, or blank padding.
                // Nothing here describes file content.
                return;
            }

            // "\ No newline at end of file" annotates the line before it. It is not a line of either
            // version of the file, so rendering it as content would show text that is not in the file.
            if (raw.startsWith("\\")) {
                return;
            }

            if (linesParsed >= maxLines) {
                truncated = true;
                fileTruncated = true;
                return;
            }

            char marker = raw.charAt(0);
            String content = raw.substring(1);

            switch (marker) {
                case '+' -> {
                    hunk.lines.add(new DiffLine(DiffLineType.ADDED, content, null, hunk.newLine++));
                    additions++;
                    linesParsed++;
                }
                case '-' -> {
                    hunk.lines.add(new DiffLine(DiffLineType.REMOVED, content, hunk.oldLine++, null));
                    deletions++;
                    linesParsed++;
                }
                case ' ' -> {
                    hunk.lines.add(new DiffLine(DiffLineType.CONTEXT, content,
                            hunk.oldLine++, hunk.newLine++));
                    linesParsed++;
                }
                default -> {
                    // An unmarked line inside a hunk is malformed. Skipped rather than guessed at: both
                    // plausible guesses would desynchronise every line number below it.
                }
            }
        }

        /** Completes the file being parsed, if any, and appends it. */
        private void flush(List<DiffFile> files) {
            closeHunk();

            if (path == null) {
                reset();
                return;
            }
            if (files.size() >= maxFiles) {
                truncated = true;
                reset();
                return;
            }

            files.add(DiffFile.builder()
                    .path(path)
                    .previousPath(previousPath)
                    .status(resolveChangeType())
                    .additions(additions)
                    .deletions(deletions)
                    .binary(binary)
                    .truncated(fileTruncated)
                    .hunks(List.copyOf(hunks))
                    .build());
            reset();
        }

        /**
         * @return the change type, defaulting to {@code MODIFIED}.
         *
         * <p>A file with no explicit marker is a modification: git emits {@code new file mode} and
         * {@code deleted file mode} for the other two cases and nothing for a plain edit, so the absence
         * of a marker is itself the signal rather than missing information.
         */
        private FileChangeType resolveChangeType() {
            return changeType != null ? changeType : FileChangeType.MODIFIED;
        }

        private void closeHunk() {
            if (hunk != null) {
                hunks.add(hunk.toHunk());
                hunk = null;
            }
        }

        private void reset() {
            path = null;
            previousPath = null;
            changeType = null;
            binary = false;
            fileTruncated = false;
            additions = 0;
            deletions = 0;
            hunks = new ArrayList<>();
            hunk = null;
        }
    }

    /** One hunk mid-parse, carrying the running line counters. */
    private static final class HunkState {

        private final String header;
        private final int oldStart;
        private final int oldLines;
        private final int newStart;
        private final int newLines;
        private final List<DiffLine> lines = new ArrayList<>();

        private int oldLine;
        private int newLine;

        private HunkState(String header, int oldStart, int oldLines, int newStart, int newLines) {
            this.header = header;
            this.oldStart = oldStart;
            this.oldLines = oldLines;
            this.newStart = newStart;
            this.newLines = newLines;
            this.oldLine = oldStart;
            this.newLine = newStart;
        }

        /**
         * Reads a matched {@code @@} header.
         *
         * <p>The line counts are optional in the format: {@code @@ -1 +1 @@} means one line, and a
         * missing count defaults to 1 rather than 0. Defaulting to 0 would make a single-line hunk
         * report that it covers nothing.
         */
        private static HunkState from(Matcher matcher) {
            int oldStart = Integer.parseInt(matcher.group(1));
            int oldLines = matcher.group(2) != null ? Integer.parseInt(matcher.group(2)) : 1;
            int newStart = Integer.parseInt(matcher.group(3));
            int newLines = matcher.group(4) != null ? Integer.parseInt(matcher.group(4)) : 1;
            return new HunkState(matcher.group(0), oldStart, oldLines, newStart, newLines);
        }

        private DiffHunk toHunk() {
            return new DiffHunk(header, oldStart, oldLines, newStart, newLines, List.copyOf(lines));
        }
    }

    /**
     * Removes git's {@code a/} or {@code b/} source prefix and any trailing tab-separated metadata.
     *
     * <p>{@code /dev/null} is returned untouched, because it is a sentinel rather than a path and the
     * caller tests for it by value. The trailing-tab strip handles diffs that append a timestamp to the
     * {@code ---}/{@code +++} lines, which would otherwise become part of the filename.
     */
    private static String stripPathPrefix(String value) {
        String trimmed = value.trim();
        int tab = trimmed.indexOf('\t');
        if (tab >= 0) {
            trimmed = trimmed.substring(0, tab).trim();
        }
        if (NO_FILE.equals(trimmed)) {
            return trimmed;
        }
        if (trimmed.startsWith("a/") || trimmed.startsWith("b/")) {
            return trimmed.substring(2);
        }
        return trimmed;
    }
}
