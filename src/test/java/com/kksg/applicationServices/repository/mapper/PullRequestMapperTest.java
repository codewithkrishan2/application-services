package com.kksg.applicationServices.repository.mapper;

import com.kksg.applicationServices.repository.dto.PullRequestFileResponse;
import com.kksg.applicationServices.repository.dto.PullRequestResponse;
import com.kksg.applicationServices.repository.dto.RepositoryRefResponse;
import com.kksg.applicationServices.repository.dto.ScmResourceProvider;
import com.kksg.applicationServices.scm.common.model.FileChangeType;
import com.kksg.applicationServices.scm.common.model.NormalizedPullRequest;
import com.kksg.applicationServices.scm.common.model.NormalizedPullRequestFile;
import com.kksg.applicationServices.scm.common.model.PullRequestState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pull-request mapping, and the module's one real derivation.
 *
 * <p><b>Resolving "merged" is the interesting part.</b> Providers model it incompatibly - some report
 * {@code MERGED} as a state, others report {@code closed} plus a merge timestamp - so without this step
 * a merged pull request and an abandoned one are both {@code CLOSED}, and a user filtering by "merged"
 * sees results labelled "closed". The rule is expressed over normalized facts rather than over a
 * provider code, which is what makes it safe for a provider nobody has added yet.
 */
class PullRequestMapperTest {

    private static final ScmResourceProvider PROVIDER = new ScmResourceProvider("GITHUB", "GitHub");

    private NormalizedPullRequest pullRequest() {
        return NormalizedPullRequest.builder()
                .externalId("200101")
                .number(123)
                .title("Fix authentication issue")
                .description("Body text")
                .state(PullRequestState.OPEN)
                .sourceBranch("fix/auth")
                .targetBranch("main")
                .sourceCommitSha("aaaa111")
                .targetCommitSha("bbbb222")
                .authorExternalId("42")
                .authorUsername("octocat")
                .webUrl("https://example.invalid/pull/123")
                .createdAt("2026-10-01T10:00:00Z")
                .updatedAt("2026-10-02T11:30:00Z")
                .build();
    }

    @Test
    @DisplayName("maps every field a detail page needs")
    void mapsDetailFields() {
        RepositoryRefResponse ref = new RepositoryRefResponse("api", "acme/api", "acme", PROVIDER);

        PullRequestResponse response = PullRequestMapper.toResponse(pullRequest(), ref);

        assertThat(response.getId()).isEqualTo("200101");
        assertThat(response.getNumber()).isEqualTo(123);
        assertThat(response.getTitle()).isEqualTo("Fix authentication issue");
        assertThat(response.getDescription()).isEqualTo("Body text");
        assertThat(response.getState()).isEqualTo(PullRequestState.OPEN);
        assertThat(response.getSourceBranch()).isEqualTo("fix/auth");
        assertThat(response.getTargetBranch()).isEqualTo("main");
        assertThat(response.getSourceCommitSha()).isEqualTo("aaaa111");
        assertThat(response.getTargetCommitSha()).isEqualTo("bbbb222");
        assertThat(response.getAuthor().id()).isEqualTo("42");
        assertThat(response.getAuthor().username()).isEqualTo("octocat");
        assertThat(response.getCreatedAt()).isEqualTo(Instant.parse("2026-10-01T10:00:00Z"));
        assertThat(response.getUpdatedAt()).isEqualTo(Instant.parse("2026-10-02T11:30:00Z"));
        assertThat(response.getMergedAt()).isNull();
        assertThat(response.getWebUrl()).isEqualTo("https://example.invalid/pull/123");
        assertThat(response.getRepository()).isSameAs(ref);
    }

    @Test
    @DisplayName("reports a closed pull request with a merge timestamp as MERGED")
    void derivesMergedFromTimestamp() {
        NormalizedPullRequest merged = pullRequest();
        merged.setState(PullRequestState.CLOSED);
        merged.setMergedAt("2026-10-03T09:00:00Z");

        PullRequestResponse response = PullRequestMapper.toResponse(merged, null);

        assertThat(response.getState()).isEqualTo(PullRequestState.MERGED);
        assertThat(response.getMergedAt()).isEqualTo(Instant.parse("2026-10-03T09:00:00Z"));
    }

    @Test
    @DisplayName("leaves a closed pull request with no merge timestamp as CLOSED")
    void keepsClosedWithoutMergeTimestamp() {
        NormalizedPullRequest closed = pullRequest();
        closed.setState(PullRequestState.CLOSED);

        assertThat(PullRequestMapper.toResponse(closed, null).getState())
                .isEqualTo(PullRequestState.CLOSED);
    }

    @Test
    @DisplayName("does not reinterpret a state the provider already reported as MERGED")
    void preservesProviderReportedMerged() {
        // A provider that says MERGED has no merge timestamp mapped, so the rule must not depend on one.
        NormalizedPullRequest merged = pullRequest();
        merged.setState(PullRequestState.MERGED);

        assertThat(PullRequestMapper.toResponse(merged, null).getState())
                .isEqualTo(PullRequestState.MERGED);
    }

    @Test
    @DisplayName("never promotes an open pull request to merged")
    void neverPromotesOpenToMerged() {
        NormalizedPullRequest open = pullRequest();
        open.setState(PullRequestState.OPEN);
        open.setMergedAt("2026-10-03T09:00:00Z");

        // Nonsensical input, but the rule is scoped to CLOSED precisely so a strange payload cannot
        // flip a live pull request into a merged one.
        assertThat(PullRequestMapper.toResponse(open, null).getState())
                .isEqualTo(PullRequestState.OPEN);
    }

    @Test
    @DisplayName("an absent state becomes UNKNOWN rather than null")
    void absentStateBecomesUnknown() {
        NormalizedPullRequest unstated = pullRequest();
        unstated.setState(null);

        assertThat(PullRequestMapper.toResponse(unstated, null).getState())
                .isEqualTo(PullRequestState.UNKNOWN);
    }

    @Test
    @DisplayName("omits the author entirely when the provider reported none")
    void omitsAuthorWhenAbsent() {
        // A pull request opened by a since-deleted account. Reported as no author rather than as an
        // author with no name.
        NormalizedPullRequest anonymous = pullRequest();
        anonymous.setAuthorExternalId(null);
        anonymous.setAuthorUsername(null);

        assertThat(PullRequestMapper.toResponse(anonymous, null).getAuthor()).isNull();
    }

    @Test
    @DisplayName("an unparseable timestamp becomes null instead of failing the mapping")
    void tolerantTimestampParsing() {
        NormalizedPullRequest odd = pullRequest();
        odd.setCreatedAt("not a date");
        odd.setUpdatedAt("2026-10-02T11:30:00.123456+00:00");

        PullRequestResponse response = PullRequestMapper.toResponse(odd, null);

        assertThat(response.getCreatedAt()).isNull();
        // The offset-with-microseconds form that Instant.parse refuses must still be read.
        assertThat(response.getUpdatedAt()).isEqualTo(Instant.parse("2026-10-02T11:30:00.123456Z"));
    }

    @Test
    @DisplayName("search matches title, author and either branch")
    void searchCoversTheFieldsAReviewerRemembers() {
        NormalizedPullRequest pr = pullRequest();

        assertThat(PullRequestMapper.matches(pr, "authentication")).isTrue();
        assertThat(PullRequestMapper.matches(pr, "octocat")).isTrue();
        assertThat(PullRequestMapper.matches(pr, "fix/auth")).isTrue();
        assertThat(PullRequestMapper.matches(pr, "main")).isTrue();
        assertThat(PullRequestMapper.matches(pr, "unrelated")).isFalse();
    }

    @Test
    @DisplayName("search excludes the description on purpose")
    void searchExcludesDescription() {
        NormalizedPullRequest pr = pullRequest();
        pr.setDescription("mentions kubernetes somewhere in a long body");

        // Long free text would make nearly any common word match nearly every pull request.
        assertThat(PullRequestMapper.matches(pr, "kubernetes")).isFalse();
    }

    @Test
    @DisplayName("a numeric search term matches the pull-request number exactly")
    void numericSearchIsExact() {
        NormalizedPullRequest pr = pullRequest();

        assertThat(PullRequestMapper.matches(pr, "123")).isTrue();
        assertThat(PullRequestMapper.matches(pr, "#123")).isTrue();
        // Substring matching here would answer "12" with #120, #124 and #1234.
        assertThat(PullRequestMapper.matches(pr, "12")).isFalse();
    }

    @Test
    @DisplayName("file mapping computes the change total")
    void mapsChangedFile() {
        NormalizedPullRequestFile file = NormalizedPullRequestFile.builder()
                .path("src/main/java/example/UserService.java")
                .changeType(FileChangeType.MODIFIED)
                .additions(20)
                .deletions(5)
                .build();

        PullRequestFileResponse response = PullRequestMapper.toFileResponse(file);

        assertThat(response.getPath()).isEqualTo("src/main/java/example/UserService.java");
        assertThat(response.getStatus()).isEqualTo(FileChangeType.MODIFIED);
        assertThat(response.getChanges()).isEqualTo(25);
    }

    @Test
    @DisplayName("a file with no reported counts keeps a null total rather than a confident zero")
    void fileWithoutCountsHasNullTotal() {
        NormalizedPullRequestFile file = NormalizedPullRequestFile.builder()
                .path("binary.png")
                .changeType(FileChangeType.ADDED)
                .build();

        // Zero would assert that nothing changed in a file that certainly did.
        assertThat(PullRequestMapper.toFileResponse(file).getChanges()).isNull();
    }

    @Test
    @DisplayName("an absent change type becomes UNKNOWN")
    void absentChangeTypeBecomesUnknown() {
        NormalizedPullRequestFile file = NormalizedPullRequestFile.builder().path("a.txt").build();

        assertThat(PullRequestMapper.toFileResponse(file).getStatus())
                .isEqualTo(FileChangeType.UNKNOWN);
    }
}
