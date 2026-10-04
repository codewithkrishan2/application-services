package com.kksg.applicationServices.repository.controller;

import com.kksg.applicationServices.common.exception.GlobalExceptionHandler;
import com.kksg.applicationServices.common.response.PageResponse;
import com.kksg.applicationServices.repository.dto.DiffFile;
import com.kksg.applicationServices.repository.dto.DiffHunk;
import com.kksg.applicationServices.repository.dto.DiffLine;
import com.kksg.applicationServices.repository.dto.DiffLineType;
import com.kksg.applicationServices.repository.dto.PullRequestAuthor;
import com.kksg.applicationServices.repository.dto.PullRequestDiffResponse;
import com.kksg.applicationServices.repository.dto.PullRequestFileResponse;
import com.kksg.applicationServices.repository.dto.PullRequestResponse;
import com.kksg.applicationServices.repository.dto.PullRequestStateFilter;
import com.kksg.applicationServices.repository.dto.RepositoryRefResponse;
import com.kksg.applicationServices.repository.dto.ScmResourceProvider;
import com.kksg.applicationServices.repository.service.PullRequestService;
import com.kksg.applicationServices.repository.service.RepositoryRef;
import com.kksg.applicationServices.scm.common.exception.ScmErrorCode;
import com.kksg.applicationServices.scm.common.exception.ScmException;
import com.kksg.applicationServices.scm.common.model.FileChangeType;
import com.kksg.applicationServices.scm.common.model.PullRequestState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The pull-request HTTP contract.
 *
 * <p>The routing assertions carry most of the weight: four nested routes under a four-segment prefix is
 * where a mapping mistake would be easy and quiet - {@code /{number}} shadowing {@code /{number}/files}
 * answers the wrong payload with a 200. See {@link RepositoryControllerTest} for why this is a
 * standalone setup.
 */
@ExtendWith(MockitoExtension.class)
class PullRequestControllerTest {

    private static final String BASE =
            "/api/v1/scm/connections/5/repositories/acme/api/pull-requests";

    @Mock
    private PullRequestService pullRequestService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new PullRequestController(pullRequestService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new TestPrincipalResolver())
                .build();
    }

    private PullRequestResponse pullRequest(int number, PullRequestState state) {
        return PullRequestResponse.builder()
                .id("pr-" + number)
                .number(number)
                .title("Fix authentication issue")
                .description("Body")
                .state(state)
                .author(new PullRequestAuthor("1", "octocat"))
                .sourceBranch("fix/auth")
                .targetBranch("main")
                .createdAt(Instant.parse("2026-10-01T10:00:00Z"))
                .updatedAt(Instant.parse("2026-10-02T10:00:00Z"))
                .webUrl("https://example.invalid/pull/" + number)
                .build();
    }

    /* --------------------------------------------------------------------- *
     * List
     * --------------------------------------------------------------------- */

    @Test
    @DisplayName("GET .../pull-requests returns a page")
    void listsPullRequests() throws Exception {
        when(pullRequestService.listPullRequests(any(), eq(5), any(), any(), any()))
                .thenReturn(PageResponse.of(
                        List.of(pullRequest(123, PullRequestState.OPEN),
                                pullRequest(122, PullRequestState.MERGED)), 0, 20, false));

        mockMvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.content[0].number").value(123))
                .andExpect(jsonPath("$.data.content[0].state").value("OPEN"))
                .andExpect(jsonPath("$.data.content[0].author.username").value("octocat"))
                .andExpect(jsonPath("$.data.content[0].sourceBranch").value("fix/auth"))
                .andExpect(jsonPath("$.data.content[1].state").value("MERGED"))
                .andExpect(jsonPath("$.data.last").value(true));
    }

    @Test
    @DisplayName("binds owner and repo from the path")
    void bindsRepositoryPath() throws Exception {
        when(pullRequestService.listPullRequests(any(), eq(5), any(), any(), any()))
                .thenReturn(PageResponse.of(List.of(), 0, 20, false));

        mockMvc.perform(get(BASE)).andExpect(status().isOk());

        ArgumentCaptor<RepositoryRef> ref = ArgumentCaptor.forClass(RepositoryRef.class);
        verify(pullRequestService).listPullRequests(any(), eq(5), ref.capture(), any(), any());

        assertThat(ref.getValue().fullName()).isEqualTo("acme/api");
    }

    @Test
    @DisplayName("defaults the state filter to OPEN")
    void defaultsStateToOpen() throws Exception {
        when(pullRequestService.listPullRequests(any(), eq(5), any(), any(), any()))
                .thenReturn(PageResponse.of(List.of(), 0, 20, false));

        mockMvc.perform(get(BASE)).andExpect(status().isOk());

        ArgumentCaptor<PullRequestStateFilter> state =
                ArgumentCaptor.forClass(PullRequestStateFilter.class);
        verify(pullRequestService).listPullRequests(any(), eq(5), any(), state.capture(), any());

        assertThat(state.getValue()).isEqualTo(PullRequestStateFilter.OPEN);
    }

    @Test
    @DisplayName("binds the state filter case-insensitively")
    void bindsStateFilter() throws Exception {
        when(pullRequestService.listPullRequests(any(), eq(5), any(), any(), any()))
                .thenReturn(PageResponse.of(List.of(), 0, 20, false));

        mockMvc.perform(get(BASE).param("state", "merged")).andExpect(status().isOk());

        ArgumentCaptor<PullRequestStateFilter> state =
                ArgumentCaptor.forClass(PullRequestStateFilter.class);
        verify(pullRequestService).listPullRequests(any(), eq(5), any(), state.capture(), any());

        assertThat(state.getValue()).isEqualTo(PullRequestStateFilter.MERGED);
    }

    @Test
    @DisplayName("an unrecognised state is 400 rather than a quietly defaulted list")
    void unknownStateIsBadRequest() throws Exception {
        mockMvc.perform(get(BASE).param("state", "DECLINED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.code").value("SCM_REQUEST_INVALID"));
    }

    /* --------------------------------------------------------------------- *
     * Detail, files, diff - the nested routes
     * --------------------------------------------------------------------- */

    @Test
    @DisplayName("GET .../pull-requests/{number} returns the detail with its repository")
    void getsPullRequestDetail() throws Exception {
        PullRequestResponse detail = PullRequestResponse.builder()
                .id("pr-123")
                .number(123)
                .title("Fix authentication issue")
                .state(PullRequestState.OPEN)
                .repository(new RepositoryRefResponse("api", "acme/api", "acme",
                        new ScmResourceProvider("GITHUB", "GitHub")))
                .build();

        when(pullRequestService.getPullRequest(any(), eq(5), any(), eq(123))).thenReturn(detail);

        mockMvc.perform(get(BASE + "/123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.number").value(123))
                .andExpect(jsonPath("$.data.repository.fullName").value("acme/api"))
                .andExpect(jsonPath("$.data.repository.provider.name").value("GitHub"));
    }

    @Test
    @DisplayName("GET .../pull-requests/{number}/files is not shadowed by the detail route")
    void getsChangedFiles() throws Exception {
        when(pullRequestService.listChangedFiles(any(), eq(5), any(), eq(123), any()))
                .thenReturn(PageResponse.of(List.of(
                        PullRequestFileResponse.of("src/main/java/example/UserService.java", null,
                                FileChangeType.MODIFIED, 20, 5)), 0, 20, false));

        mockMvc.perform(get(BASE + "/123/files"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].path")
                        .value("src/main/java/example/UserService.java"))
                .andExpect(jsonPath("$.data.content[0].status").value("MODIFIED"))
                .andExpect(jsonPath("$.data.content[0].additions").value(20))
                .andExpect(jsonPath("$.data.content[0].deletions").value(5))
                .andExpect(jsonPath("$.data.content[0].changes").value(25));

        // Confirms the nested route reached the files method rather than the detail one, which would
        // have answered 200 with the wrong payload.
        verify(pullRequestService).listChangedFiles(any(), eq(5), any(), eq(123), any());
    }

    @Test
    @DisplayName("GET .../pull-requests/{number}/diff returns files, hunks and both line numbers")
    void getsDiff() throws Exception {
        PullRequestDiffResponse diff = PullRequestDiffResponse.builder()
                .pullRequestNumber(123)
                .totalFiles(1)
                .totalAdditions(1)
                .totalDeletions(1)
                .truncated(false)
                .files(List.of(DiffFile.builder()
                        .path("src/Main.java")
                        .status(FileChangeType.MODIFIED)
                        .additions(1)
                        .deletions(1)
                        .binary(false)
                        .truncated(false)
                        .hunks(List.of(new DiffHunk("@@ -1,2 +1,2 @@", 1, 2, 1, 2, List.of(
                                new DiffLine(DiffLineType.CONTEXT, "package example;", 1, 1),
                                new DiffLine(DiffLineType.REMOVED, "class Main {}", 2, null),
                                new DiffLine(DiffLineType.ADDED, "class Main { }", null, 2)))))
                        .build()))
                .build();

        when(pullRequestService.getDiff(any(), eq(5), any(), eq(123))).thenReturn(diff);

        mockMvc.perform(get(BASE + "/123/diff"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pullRequestNumber").value(123))
                .andExpect(jsonPath("$.data.truncated").value(false))
                .andExpect(jsonPath("$.data.files[0].path").value("src/Main.java"))
                .andExpect(jsonPath("$.data.files[0].hunks[0].oldStart").value(1))
                .andExpect(jsonPath("$.data.files[0].hunks[0].lines[0].type").value("CONTEXT"))
                .andExpect(jsonPath("$.data.files[0].hunks[0].lines[1].type").value("REMOVED"))
                .andExpect(jsonPath("$.data.files[0].hunks[0].lines[1].oldLineNumber").value(2))
                // An added line has no old number, and NON_NULL drops the key rather than sending null.
                .andExpect(jsonPath("$.data.files[0].hunks[0].lines[2].oldLineNumber").doesNotExist())
                .andExpect(jsonPath("$.data.files[0].hunks[0].lines[2].newLineNumber").value(2));
    }

    /* --------------------------------------------------------------------- *
     * Errors
     * --------------------------------------------------------------------- */

    @Test
    @DisplayName("a non-numeric pull-request number is 400")
    void nonNumericNumberIsBadRequest() throws Exception {
        mockMvc.perform(get(BASE + "/not-a-number"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a missing pull request is 404 with SCM_PULL_REQUEST_NOT_FOUND")
    void missingPullRequestIsNotFound() throws Exception {
        when(pullRequestService.getPullRequest(any(), eq(5), any(), eq(999)))
                .thenThrow(new ScmException(ScmErrorCode.SCM_PULL_REQUEST_NOT_FOUND));

        mockMvc.perform(get(BASE + "/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errors.code").value("SCM_PULL_REQUEST_NOT_FOUND"));
    }

    @Test
    @DisplayName("an unreachable repository on the listing is 404 with SCM_REPOSITORY_NOT_FOUND")
    void missingRepositoryOnListingIsNotFound() throws Exception {
        when(pullRequestService.listPullRequests(any(), eq(5), any(), any(), any()))
                .thenThrow(new ScmException(ScmErrorCode.SCM_REPOSITORY_NOT_FOUND));

        // The listing addresses no pull request, so naming one would send the user to the wrong place.
        mockMvc.perform(get(BASE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errors.code").value("SCM_REPOSITORY_NOT_FOUND"));
    }

    @Test
    @DisplayName("another user's connection is 404 on every nested route")
    void unauthorizedConnectionIsNotFoundEverywhere() throws Exception {
        ScmException refused = new ScmException(ScmErrorCode.SCM_CONNECTION_NOT_FOUND);
        when(pullRequestService.listPullRequests(any(), eq(5), any(), any(), any())).thenThrow(refused);
        when(pullRequestService.getPullRequest(any(), eq(5), any(), eq(1))).thenThrow(refused);
        when(pullRequestService.listChangedFiles(any(), eq(5), any(), eq(1), any())).thenThrow(refused);
        when(pullRequestService.getDiff(any(), eq(5), any(), eq(1))).thenThrow(refused);

        for (String path : new String[]{BASE, BASE + "/1", BASE + "/1/files", BASE + "/1/diff"}) {
            mockMvc.perform(get(path))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.errors.code").value("SCM_CONNECTION_NOT_FOUND"));
        }
    }

    @Test
    @DisplayName("provider rate limiting on the diff is 429")
    void rateLimitOnDiffIsTooManyRequests() throws Exception {
        when(pullRequestService.getDiff(any(), eq(5), any(), eq(123)))
                .thenThrow(new ScmException(ScmErrorCode.SCM_PROVIDER_RATE_LIMITED));

        mockMvc.perform(get(BASE + "/123/diff"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errors.code").value("SCM_PROVIDER_RATE_LIMITED"));
    }

    @Test
    @DisplayName("a provider failure on the diff is 502")
    void providerFailureOnDiffIsBadGateway() throws Exception {
        when(pullRequestService.getDiff(any(), eq(5), any(), eq(123)))
                .thenThrow(new ScmException(ScmErrorCode.SCM_PROVIDER_API_ERROR));

        mockMvc.perform(get(BASE + "/123/diff"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.errors.code").value("SCM_PROVIDER_API_ERROR"));
    }
}
