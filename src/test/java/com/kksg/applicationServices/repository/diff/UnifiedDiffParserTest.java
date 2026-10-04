package com.kksg.applicationServices.repository.diff;

import com.kksg.applicationServices.repository.RepositoryManagementProperties;
import com.kksg.applicationServices.repository.dto.DiffFile;
import com.kksg.applicationServices.repository.dto.DiffHunk;
import com.kksg.applicationServices.repository.dto.DiffLine;
import com.kksg.applicationServices.repository.dto.DiffLineType;
import com.kksg.applicationServices.repository.dto.PullRequestDiffResponse;
import com.kksg.applicationServices.scm.common.model.FileChangeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Diff parsing: unified-diff text to the structure the UI renders.
 *
 * <p>The behaviours worth protecting here are the ones a reader would notice being wrong:
 * <ul>
 *   <li><b>line numbering</b> - an addition has no old number and a deletion no new one, and every
 *       line after a mistake is wrong, which makes this the highest-value assertion in the file;</li>
 *   <li><b>change type</b> - derived from the diff's own markers, so an added file must not report as
 *       modified;</li>
 *   <li><b>truncation is announced</b> - a bounded parse that silently returned fewer files would make
 *       a large pull request look small.</li>
 * </ul>
 */
class UnifiedDiffParserTest {

    private UnifiedDiffParser parserWith(int maxLines, int maxFiles) {
        RepositoryManagementProperties properties = new RepositoryManagementProperties();
        properties.getDiff().setMaxLines(maxLines);
        properties.getDiff().setMaxFiles(maxFiles);
        return new UnifiedDiffParser(properties);
    }

    private UnifiedDiffParser parser() {
        return parserWith(20_000, 300);
    }

    @Test
    @DisplayName("resolves both sets of line numbers across a mixed hunk")
    void resolvesLineNumbers() {
        String diff = """
                diff --git a/src/UserService.java b/src/UserService.java
                index 1111111..2222222 100644
                --- a/src/UserService.java
                +++ b/src/UserService.java
                @@ -10,4 +10,5 @@ public class UserService {
                     private final Repo repo;
                -    private int cacheSize;
                +    private int cacheSize = 10;
                +    private boolean enabled;
                     public UserService() {
                """;

        PullRequestDiffResponse response = parser().parse(diff, 42);

        assertThat(response.getPullRequestNumber()).isEqualTo(42);
        assertThat(response.getFiles()).hasSize(1);

        DiffFile file = response.getFiles().get(0);
        assertThat(file.getPath()).isEqualTo("src/UserService.java");
        assertThat(file.getStatus()).isEqualTo(FileChangeType.MODIFIED);
        assertThat(file.getAdditions()).isEqualTo(2);
        assertThat(file.getDeletions()).isEqualTo(1);
        assertThat(file.isBinary()).isFalse();
        assertThat(file.isTruncated()).isFalse();

        DiffHunk hunk = file.getHunks().get(0);
        assertThat(hunk.oldStart()).isEqualTo(10);
        assertThat(hunk.oldLines()).isEqualTo(4);
        assertThat(hunk.newStart()).isEqualTo(10);
        assertThat(hunk.newLines()).isEqualTo(5);
        assertThat(hunk.header()).contains("public class UserService");

        // Context advances both counters; an addition advances only the new side and a deletion only
        // the old side. Getting any of these wrong desynchronises every line below it.
        assertThat(hunk.lines()).containsExactly(
                new DiffLine(DiffLineType.CONTEXT, "    private final Repo repo;", 10, 10),
                new DiffLine(DiffLineType.REMOVED, "    private int cacheSize;", 11, null),
                new DiffLine(DiffLineType.ADDED, "    private int cacheSize = 10;", null, 11),
                new DiffLine(DiffLineType.ADDED, "    private boolean enabled;", null, 12),
                new DiffLine(DiffLineType.CONTEXT, "    public UserService() {", 12, 13));
    }

    @Test
    @DisplayName("strips the leading diff marker from line content")
    void stripsLineMarkers() {
        String diff = """
                diff --git a/a.txt b/a.txt
                --- a/a.txt
                +++ b/a.txt
                @@ -1 +1 @@
                -old
                +new
                """;

        DiffFile file = parser().parse(diff, 1).getFiles().get(0);

        // The marker is redundant once the line has a type, and leaving it in would corrupt every
        // client's indentation measurement and text matching.
        assertThat(file.getHunks().get(0).lines())
                .extracting(DiffLine::content)
                .containsExactly("old", "new");
    }

    @Test
    @DisplayName("separates multiple files and totals their counts")
    void separatesFiles() {
        String diff = """
                diff --git a/one.txt b/one.txt
                --- a/one.txt
                +++ b/one.txt
                @@ -1,2 +1,2 @@
                 keep
                -gone
                +added
                diff --git a/two.txt b/two.txt
                --- a/two.txt
                +++ b/two.txt
                @@ -5,1 +5,2 @@
                 keep
                +extra
                """;

        PullRequestDiffResponse response = parser().parse(diff, 7);

        assertThat(response.getFiles()).extracting(DiffFile::getPath)
                .containsExactly("one.txt", "two.txt");
        assertThat(response.getTotalFiles()).isEqualTo(2);
        assertThat(response.getTotalAdditions()).isEqualTo(2);
        assertThat(response.getTotalDeletions()).isEqualTo(1);
        assertThat(response.isTruncated()).isFalse();
    }

    @Test
    @DisplayName("reads an added file from /dev/null on the old side")
    void detectsAddedFile() {
        String diff = """
                diff --git a/new.txt b/new.txt
                new file mode 100644
                index 0000000..e69de29
                --- /dev/null
                +++ b/new.txt
                @@ -0,0 +1,2 @@
                +first
                +second
                """;

        DiffFile file = parser().parse(diff, 1).getFiles().get(0);

        assertThat(file.getStatus()).isEqualTo(FileChangeType.ADDED);
        assertThat(file.getPath()).isEqualTo("new.txt");
        assertThat(file.getAdditions()).isEqualTo(2);
        assertThat(file.getHunks().get(0).lines())
                .extracting(DiffLine::oldLineNumber)
                .containsOnlyNulls();
    }

    @Test
    @DisplayName("reads a deleted file and keeps its pre-deletion path")
    void detectsDeletedFile() {
        String diff = """
                diff --git a/gone.txt b/gone.txt
                deleted file mode 100644
                --- a/gone.txt
                +++ /dev/null
                @@ -1,2 +0,0 @@
                -first
                -second
                """;

        DiffFile file = parser().parse(diff, 1).getFiles().get(0);

        assertThat(file.getStatus()).isEqualTo(FileChangeType.REMOVED);
        // The +++ line is /dev/null, so the path has to survive from the diff --git header. A parser
        // taking the new-side path unconditionally would report this file as "/dev/null".
        assertThat(file.getPath()).isEqualTo("gone.txt");
        assertThat(file.getDeletions()).isEqualTo(2);
    }

    @Test
    @DisplayName("reads a rename and reports both ends of it")
    void detectsRename() {
        String diff = """
                diff --git a/old/name.txt b/new/name.txt
                similarity index 95%
                rename from old/name.txt
                rename to new/name.txt
                index 1111111..2222222 100644
                --- a/old/name.txt
                +++ b/new/name.txt
                @@ -1 +1 @@
                -before
                +after
                """;

        DiffFile file = parser().parse(diff, 1).getFiles().get(0);

        assertThat(file.getStatus()).isEqualTo(FileChangeType.RENAMED);
        assertThat(file.getPath()).isEqualTo("new/name.txt");
        assertThat(file.getPreviousPath()).isEqualTo("old/name.txt");
    }

    @Test
    @DisplayName("marks a binary file rather than reporting it as unchanged")
    void detectsBinaryFile() {
        String diff = """
                diff --git a/logo.png b/logo.png
                index 1111111..2222222 100644
                Binary files a/logo.png and b/logo.png differ
                """;

        DiffFile file = parser().parse(diff, 1).getFiles().get(0);

        // No hunks either way, so without the flag this would be indistinguishable from a file that
        // did not change - and a viewer would render nothing with no explanation.
        assertThat(file.isBinary()).isTrue();
        assertThat(file.getHunks()).isEmpty();
        assertThat(file.getPath()).isEqualTo("logo.png");
    }

    @Test
    @DisplayName("drops the no-newline marker instead of rendering it as content")
    void ignoresNoNewlineMarker() {
        String diff = """
                diff --git a/a.txt b/a.txt
                --- a/a.txt
                +++ b/a.txt
                @@ -1 +1 @@
                -old
                \\ No newline at end of file
                +new
                """;

        DiffFile file = parser().parse(diff, 1).getFiles().get(0);

        // It annotates the line above it; it is not a line of either version of the file.
        assertThat(file.getHunks().get(0).lines())
                .extracting(DiffLine::content)
                .containsExactly("old", "new");
    }

    @Test
    @DisplayName("defaults an omitted hunk line count to one, not zero")
    void defaultsOmittedHunkCounts() {
        String diff = """
                diff --git a/a.txt b/a.txt
                --- a/a.txt
                +++ b/a.txt
                @@ -3 +3 @@
                -old
                +new
                """;

        DiffHunk hunk = parser().parse(diff, 1).getFiles().get(0).getHunks().get(0);

        // "@@ -3 +3 @@" means one line, not none. Defaulting to zero would say the hunk covers nothing.
        assertThat(hunk.oldLines()).isEqualTo(1);
        assertThat(hunk.newLines()).isEqualTo(1);
        assertThat(hunk.oldStart()).isEqualTo(3);
    }

    @Test
    @DisplayName("announces truncation when the line budget is exhausted")
    void reportsLineTruncation() {
        StringBuilder diff = new StringBuilder("""
                diff --git a/big.txt b/big.txt
                --- a/big.txt
                +++ b/big.txt
                @@ -1,200 +1,200 @@
                """);
        for (int index = 0; index < 200; index++) {
            diff.append("+line ").append(index).append('\n');
        }

        PullRequestDiffResponse response = parserWith(10, 300).parse(diff.toString(), 1);

        assertThat(response.isTruncated()).isTrue();
        assertThat(response.getFiles().get(0).isTruncated()).isTrue();
        assertThat(response.getFiles().get(0).getHunks().get(0).lines()).hasSize(10);
    }

    @Test
    @DisplayName("announces truncation when the file budget is exhausted")
    void reportsFileTruncation() {
        StringBuilder diff = new StringBuilder();
        for (int index = 0; index < 5; index++) {
            diff.append("diff --git a/f").append(index).append(".txt b/f").append(index).append(".txt\n")
                    .append("--- a/f").append(index).append(".txt\n")
                    .append("+++ b/f").append(index).append(".txt\n")
                    .append("@@ -1 +1 @@\n")
                    .append("+changed\n");
        }

        PullRequestDiffResponse response = parserWith(20_000, 2).parse(diff.toString(), 1);

        assertThat(response.getFiles()).hasSize(2);
        assertThat(response.isTruncated()).isTrue();
    }

    @Test
    @DisplayName("an empty or absent diff is an empty result, not a failure")
    void toleratesEmptyInput() {
        // A pull request can legitimately have an empty diff, and a provider hiccup should degrade to an
        // empty diff view rather than to a failed page.
        for (String input : new String[]{null, "", "   \n  "}) {
            PullRequestDiffResponse response = parser().parse(input, 3);
            assertThat(response.getFiles()).isEmpty();
            assertThat(response.getTotalFiles()).isZero();
            assertThat(response.isTruncated()).isFalse();
            assertThat(response.getPullRequestNumber()).isEqualTo(3);
        }
    }

    @Test
    @DisplayName("parses a plain unified diff with no git preamble")
    void parsesDiffWithoutGitHeader() {
        String diff = """
                --- a/plain.txt
                +++ b/plain.txt
                @@ -1,2 +1,2 @@
                 same
                -before
                +after
                """;

        DiffFile file = parser().parse(diff, 1).getFiles().get(0);

        assertThat(file.getPath()).isEqualTo("plain.txt");
        assertThat(file.getAdditions()).isEqualTo(1);
        assertThat(file.getDeletions()).isEqualTo(1);
    }

    @Test
    @DisplayName("keeps hunks separate so the gap between them stays visible")
    void keepsHunksSeparate() {
        String diff = """
                diff --git a/a.txt b/a.txt
                --- a/a.txt
                +++ b/a.txt
                @@ -1,2 +1,2 @@
                 one
                +two
                @@ -40,2 +41,2 @@
                 forty
                -forty-one
                """;

        DiffFile file = parser().parse(diff, 1).getFiles().get(0);

        // Flattening these would lose the skipped region, which is exactly the "…" divider a diff
        // viewer needs to draw.
        assertThat(file.getHunks()).hasSize(2);
        assertThat(file.getHunks().get(1).oldStart()).isEqualTo(40);
        assertThat(file.getHunks().get(1).newStart()).isEqualTo(41);
    }

    @Test
    @DisplayName("strips a trailing timestamp from the path lines")
    void stripsTrailingTimestampFromPaths() {
        String diff = "--- a/with-date.txt\t2024-05-01 10:00:00.000000000 +0000\n"
                + "+++ b/with-date.txt\t2024-05-02 10:00:00.000000000 +0000\n"
                + "@@ -1 +1 @@\n"
                + "-a\n"
                + "+b\n";

        DiffFile file = parser().parse(diff, 1).getFiles().get(0);

        assertThat(file.getPath()).isEqualTo("with-date.txt");
    }

    @Test
    @DisplayName("handles CRLF line endings")
    void handlesCrlf() {
        String diff = "diff --git a/a.txt b/a.txt\r\n"
                + "--- a/a.txt\r\n"
                + "+++ b/a.txt\r\n"
                + "@@ -1 +1 @@\r\n"
                + "-old\r\n"
                + "+new\r\n";

        DiffFile file = parser().parse(diff, 1).getFiles().get(0);

        // A trailing \r surviving into the content would show as a stray character in every line.
        assertThat(file.getHunks().get(0).lines())
                .extracting(DiffLine::content)
                .containsExactly("old", "new");
    }
}
