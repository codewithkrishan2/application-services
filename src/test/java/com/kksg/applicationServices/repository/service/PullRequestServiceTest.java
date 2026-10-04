package com.kksg.applicationServices.repository.service;

import com.kksg.applicationServices.common.response.PageResponse;
import com.kksg.applicationServices.identity.entity.User;
import com.kksg.applicationServices.repository.RepositoryManagementProperties;
import com.kksg.applicationServices.repository.diff.UnifiedDiffParser;
import com.kksg.applicationServices.repository.dto.PullRequestDiffResponse;
import com.kksg.applicationServices.repository.dto.PullRequestFileResponse;
import com.kksg.applicationServices.repository.dto.PullRequestResponse;
import com.kksg.applicationServices.repository.dto.PullRequestStateFilter;
import com.kksg.applicationServices.repository.dto.ScmResourceProvider;
import com.kksg.applicationServices.scm.common.exception.ScmErrorCode;
import com.kksg.applicationServices.scm.common.exception.ScmException;
import com.kksg.applicationServices.scm.common.model.FileChangeType;
import com.kksg.applicationServices.scm.common.model.NormalizedPullRequest;
import com.kksg.applicationServices.scm.common.model.NormalizedPullRequestFile;
import com.kksg.applicationServices.scm.common.model.PullRequestState;
import com.kksg.applicationServices.scm.common.model.ScmOperationCode;
import com.kksg.applicationServices.scm.common.model.ScmOperationRequest;
import com.kksg.applicationServices.scm.connection.entity.ScmConnection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pull-request browsing.
 *
 * <p>Two things here are worth more than the rest:
 * <ul>
 *   <li><b>The state filter reaches the provider.</b> If it were applied in memory instead, a
 *       repository with thousands of closed pull requests would be unusable, so the test asserts the
 *       canonical value is sent as a parameter rather than that the returned list happens to be
 *       filtered.</li>
 *   <li><b>A 404 is named for the resource that is actually missing.</b> On the listing it means the
 *       repository; on a detail call it means the pull request. Conflating them gives a client a
 *       message that sends the user to the wrong place.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class PullRequestServiceTest {

    private static final ScmResourceProvider PROVIDER = new ScmResourceProvider("GITHUB", "GitHub");
    private static final int CONNECTION_ID = 5;
    private static final RepositoryRef REF = new RepositoryRef("acme", "api");

    @Mock
    private ScmResourceAccessService accessService;

    @Mock
    private ScmOperationRunner runner;

    private PullRequestService service;
    private ScmResourceContext context;
    private User user;

    @BeforeEach
    void setUp() {
        RepositoryManagementProperties properties = new RepositoryManagementProperties();
        properties.getSearch().setMaxPages(3);
        properties.getSearch().setPageSize(10);

        service = new PullRequestService(accessService,
                new ScmPageScanner(runner, properties), runner, new UnifiedDiffParser(properties));

        user = new User();
        user.setId(1);

        ScmConnection connection = new ScmConnection();
        connection.setId(CONNECTION_ID);
        context = new ScmResourceContext(connection, 1, PROVIDER);
    }

    private void accessGranted() {
        when(accessService.requireUsableConnection(user, CONNECTION_ID)).thenReturn(context);
    }

    private NormalizedPullRequest pullRequest(int number, String title, PullRequestState state) {
        return NormalizedPullRequest.builder()
                .externalId("pr-" + number)
                .number(number)
                .title(title)
                .state(state)
                .sourceBranch("feature/" + number)
                .targetBranch("main")
                .authorUsername("octocat")
                .createdAt("2026-10-01T10:00:00Z")
                .updatedAt("2026-10-02T10:00:00Z")
                .webUrl("https://example.invalid/pull/" + number)
                .build();
    }

    /* --------------------------------------------------------------------- *
     * List
     * --------------------------------------------------------------------- */

    @Test
    @DisplayName("lists pull requests with owner, repo and state sent to the provider")
    void listsPullRequests() {
        accessGranted();
        when(runner.run(eq(context), any(), eq(ScmErrorCode.SCM_REPOSITORY_NOT_FOUND)))
                .thenReturn(ScmResponses.list(ScmOperationCode.LIST_PULL_REQUESTS, List.of(
                        pullRequest(123, "Fix authentication issue", PullRequestState.OPEN),
                        pullRequest(122, "Improve error handling", PullRequestState.OPEN)), false));

        PageResponse<PullRequestResponse> page = service.listPullRequests(
                user, CONNECTION_ID, REF, PullRequestStateFilter.OPEN, PageQuery.of(0, 20, null));

        assertThat(page.getContent()).extracting(PullRequestResponse::getNumber)
                .containsExactly(123, 122);
        assertThat(page.getContent()).extracting(PullRequestResponse::getState)
                .containsOnly(PullRequestState.OPEN);
        // List rows omit the repository reference - it is identical on every row and the client already
        // knows it from the path it requested.
        assertThat(page.getContent().get(0).getRepository()).isNull();

        ArgumentCaptor<ScmOperationRequest> request = ArgumentCaptor.forClass(ScmOperationRequest.class);
        verify(runner).run(eq(context), request.capture(), eq(ScmErrorCode.SCM_REPOSITORY_NOT_FOUND));
        assertThat(request.getValue().getOperation()).isEqualTo(ScmOperationCode.LIST_PULL_REQUESTS);
        assertThat(request.getValue().getParameters())
                .containsEntry("owner", "acme")
                .containsEntry("repo", "api");
    }

    @ParameterizedTest
    @CsvSource({"OPEN,OPEN", "CLOSED,CLOSED", "MERGED,MERGED", "ALL,ALL"})
    @DisplayName("the canonical state is passed to the provider, not applied in memory")
    void stateFilterIsPushedToProvider(PullRequestStateFilter filter, String expectedParameter) {
        accessGranted();
        when(runner.run(eq(context), any(), eq(ScmErrorCode.SCM_REPOSITORY_NOT_FOUND)))
                .thenReturn(ScmResponses.list(ScmOperationCode.LIST_PULL_REQUESTS, List.of(), false));

        service.listPullRequests(user, CONNECTION_ID, REF, filter, PageQuery.of(0, 20, null));

        ArgumentCaptor<ScmOperationRequest> request = ArgumentCaptor.forClass(ScmOperationRequest.class);
        verify(runner).run(eq(context), request.capture(), eq(ScmErrorCode.SCM_REPOSITORY_NOT_FOUND));

        // The canonical value goes out as-is; each provider's operation configuration declares how to
        // spell it, so no provider name appears in the service to make this work.
        assertThat(request.getValue().getParameters()).containsEntry("state", expectedParameter);
    }

    @Test
    @DisplayName("a 404 on the listing names the repository, not the pull requests")
    void listingNotFoundNamesRepository() {
        accessGranted();
        when(runner.run(eq(context), any(), eq(ScmErrorCode.SCM_REPOSITORY_NOT_FOUND)))
                .thenThrow(new ScmException(ScmErrorCode.SCM_REPOSITORY_NOT_FOUND));

        // There is no pull request in this request yet, so "pull request not found" would be wrong.
        assertThatThrownBy(() -> service.listPullRequests(
                user, CONNECTION_ID, REF, PullRequestStateFilter.OPEN, PageQuery.of(0, 20, null)))
                .isInstanceOf(ScmException.class)
                .satisfies(thrown -> assertThat(((ScmException) thrown).getErrorCode())
                        .isEqualTo(ScmErrorCode.SCM_REPOSITORY_NOT_FOUND));
    }

    @Test
    @DisplayName("search narrows the pull-request listing")
    void searchNarrowsListing() {
        accessGranted();
        when(runner.run(eq(context), any(), eq(ScmErrorCode.SCM_REPOSITORY_NOT_FOUND), anyBoolean()))
                .thenReturn(ScmResponses.list(ScmOperationCode.LIST_PULL_REQUESTS, List.of(
                        pullRequest(123, "Fix authentication issue", PullRequestState.OPEN),
                        pullRequest(122, "Bump dependencies", PullRequestState.OPEN)), false));

        PageResponse<PullRequestResponse> page = service.listPullRequests(
                user, CONNECTION_ID, REF, PullRequestStateFilter.OPEN, PageQuery.of(0, 20, "authentication"));

        assertThat(page.getContent()).extracting(PullRequestResponse::getNumber).containsExactly(123);
    }

    /* --------------------------------------------------------------------- *
     * Detail
     * --------------------------------------------------------------------- */

    @Test
    @DisplayName("reads one pull request and attaches the repository it belongs to")
    void getsPullRequest() {
        accessGranted();
        when(runner.run(eq(context), any(), eq(ScmErrorCode.SCM_PULL_REQUEST_NOT_FOUND)))
                .thenReturn(ScmResponses.object(ScmOperationCode.GET_PULL_REQUEST,
                        pullRequest(123, "Fix authentication issue", PullRequestState.OPEN)));

        PullRequestResponse response = service.getPullRequest(user, CONNECTION_ID, REF, 123);

        assertThat(response.getNumber()).isEqualTo(123);
        assertThat(response.getRepository().fullName()).isEqualTo("acme/api");
        assertThat(response.getRepository().provider()).isSameAs(PROVIDER);

        ArgumentCaptor<ScmOperationRequest> request = ArgumentCaptor.forClass(ScmOperationRequest.class);
        verify(runner).run(eq(context), request.capture(), eq(ScmErrorCode.SCM_PULL_REQUEST_NOT_FOUND));

        // Addressed by number inside the repository path. A number from another repository therefore
        // resolves to nothing rather than to someone else's pull request.
        assertThat(request.getValue().getParameters())
                .containsEntry("owner", "acme")
                .containsEntry("repo", "api")
                .containsEntry("pullRequestNumber", 123);
    }

    @Test
    @DisplayName("a missing pull request is SCM_PULL_REQUEST_NOT_FOUND, a 404")
    void pullRequestNotFound() {
        accessGranted();
        when(runner.run(eq(context), any(), eq(ScmErrorCode.SCM_PULL_REQUEST_NOT_FOUND)))
                .thenThrow(new ScmException(ScmErrorCode.SCM_PULL_REQUEST_NOT_FOUND));

        assertThatThrownBy(() -> service.getPullRequest(user, CONNECTION_ID, REF, 999))
                .isInstanceOf(ScmException.class)
                .satisfies(thrown -> {
                    ScmErrorCode code = ((ScmException) thrown).getErrorCode();
                    assertThat(code).isEqualTo(ScmErrorCode.SCM_PULL_REQUEST_NOT_FOUND);
                    assertThat(code.getHttpStatus().value()).isEqualTo(404);
                });
    }

    @Test
    @DisplayName("a 2xx that normalizes to nothing is a mapping fault")
    void emptyNormalizedResponseIsMappingFailure() {
        accessGranted();
        when(runner.run(eq(context), any(), eq(ScmErrorCode.SCM_PULL_REQUEST_NOT_FOUND)))
                .thenReturn(ScmResponses.object(ScmOperationCode.GET_PULL_REQUEST, null));

        assertThatThrownBy(() -> service.getPullRequest(user, CONNECTION_ID, REF, 123))
                .isInstanceOf(ScmException.class)
                .satisfies(thrown -> assertThat(((ScmException) thrown).getErrorCode())
                        .isEqualTo(ScmErrorCode.SCM_RESPONSE_MAPPING_INVALID));
    }

    /* --------------------------------------------------------------------- *
     * Changed files
     * --------------------------------------------------------------------- */

    @Test
    @DisplayName("lists changed files with the change total computed")
    void listsChangedFiles() {
        accessGranted();
        when(runner.run(eq(context), any(), eq(ScmErrorCode.SCM_PULL_REQUEST_NOT_FOUND)))
                .thenReturn(ScmResponses.list(ScmOperationCode.GET_PULL_REQUEST_FILES, List.of(
                        NormalizedPullRequestFile.builder()
                                .path("src/main/java/example/UserService.java")
                                .changeType(FileChangeType.MODIFIED)
                                .additions(20).deletions(5).build(),
                        NormalizedPullRequestFile.builder()
                                .path("src/main/java/example/New.java")
                                .changeType(FileChangeType.ADDED)
                                .additions(40).deletions(0).build()), true));

        PageResponse<PullRequestFileResponse> page =
                service.listChangedFiles(user, CONNECTION_ID, REF, 123, PageQuery.of(0, 20, null));

        assertThat(page.getContent()).extracting(PullRequestFileResponse::getPath)
                .containsExactly("src/main/java/example/UserService.java",
                        "src/main/java/example/New.java");
        assertThat(page.getContent().get(0).getChanges()).isEqualTo(25);
        assertThat(page.getContent().get(1).getStatus()).isEqualTo(FileChangeType.ADDED);
        assertThat(page.isHasNext()).isTrue();
    }

    @Test
    @DisplayName("changed files never trigger a search scan")
    void changedFilesDoNotScan() {
        accessGranted();
        when(runner.run(eq(context), any(), eq(ScmErrorCode.SCM_PULL_REQUEST_NOT_FOUND)))
                .thenReturn(ScmResponses.list(ScmOperationCode.GET_PULL_REQUEST_FILES, List.of(), false));

        service.listChangedFiles(user, CONNECTION_ID, REF, 123, PageQuery.of(0, 20, null));

        // Filtering one pull request's file list is something a client can do on data it already holds,
        // so spending provider calls on it would be waste.
        verify(runner, never()).run(any(), any(), any(), anyBoolean());
    }

    /* --------------------------------------------------------------------- *
     * Diff
     * --------------------------------------------------------------------- */

    @Test
    @DisplayName("parses the diff from the operation's text response")
    void getsDiff() {
        accessGranted();
        String diff = """
                diff --git a/src/Main.java b/src/Main.java
                --- a/src/Main.java
                +++ b/src/Main.java
                @@ -1,2 +1,3 @@
                 package example;
                -class Main {}
                +class Main {
                +}
                """;
        when(runner.run(eq(context), any(), eq(ScmErrorCode.SCM_PULL_REQUEST_NOT_FOUND)))
                .thenReturn(ScmResponses.text(ScmOperationCode.GET_PULL_REQUEST_DIFF, diff));

        PullRequestDiffResponse response = service.getDiff(user, CONNECTION_ID, REF, 123);

        assertThat(response.getPullRequestNumber()).isEqualTo(123);
        assertThat(response.getFiles()).hasSize(1);
        assertThat(response.getFiles().get(0).getPath()).isEqualTo("src/Main.java");
        assertThat(response.getTotalAdditions()).isEqualTo(2);
        assertThat(response.getTotalDeletions()).isEqualTo(1);

        ArgumentCaptor<ScmOperationRequest> request = ArgumentCaptor.forClass(ScmOperationRequest.class);
        verify(runner).run(eq(context), request.capture(), eq(ScmErrorCode.SCM_PULL_REQUEST_NOT_FOUND));
        assertThat(request.getValue().getOperation()).isEqualTo(ScmOperationCode.GET_PULL_REQUEST_DIFF);
    }

    @Test
    @DisplayName("an empty diff response is an empty diff, not a failure")
    void emptyDiffIsNotAFailure() {
        accessGranted();
        when(runner.run(eq(context), any(), eq(ScmErrorCode.SCM_PULL_REQUEST_NOT_FOUND)))
                .thenReturn(ScmResponses.text(ScmOperationCode.GET_PULL_REQUEST_DIFF, null));

        PullRequestDiffResponse response = service.getDiff(user, CONNECTION_ID, REF, 123);

        assertThat(response.getFiles()).isEmpty();
        assertThat(response.isTruncated()).isFalse();
    }

    /* --------------------------------------------------------------------- *
     * Validation and authorization
     * --------------------------------------------------------------------- */

    @ParameterizedTest
    @CsvSource(value = {"0", "-1"})
    @DisplayName("a non-positive pull-request number is rejected before any provider call")
    void rejectsInvalidPullRequestNumber(int number) {
        accessGranted();

        assertThatThrownBy(() -> service.getPullRequest(user, CONNECTION_ID, REF, number))
                .isInstanceOf(ScmException.class)
                .satisfies(thrown -> {
                    ScmErrorCode code = ((ScmException) thrown).getErrorCode();
                    assertThat(code).isEqualTo(ScmErrorCode.SCM_REQUEST_INVALID);
                    assertThat(code.getHttpStatus().value()).isEqualTo(400);
                });

        // Substituted into a URL it would come back as an opaque provider error, which reads to a client
        // like a fault on our side rather than a bad request on theirs.
        verify(runner, never()).run(any(), any(), any());
    }

    @Test
    @DisplayName("a null pull-request number is rejected")
    void rejectsNullPullRequestNumber() {
        accessGranted();

        assertThatThrownBy(() -> service.getDiff(user, CONNECTION_ID, REF, null))
                .isInstanceOf(ScmException.class)
                .satisfies(thrown -> assertThat(((ScmException) thrown).getErrorCode())
                        .isEqualTo(ScmErrorCode.SCM_REQUEST_INVALID));
    }

    @Test
    @DisplayName("every pull-request endpoint goes through the access gate first")
    void allEndpointsAreGated() {
        when(accessService.requireUsableConnection(user, CONNECTION_ID))
                .thenThrow(new ScmException(ScmErrorCode.SCM_CONNECTION_NOT_FOUND));

        assertThatThrownBy(() -> service.listPullRequests(user, CONNECTION_ID, REF,
                PullRequestStateFilter.ALL, PageQuery.of(0, 20, null)))
                .isInstanceOf(ScmException.class);
        assertThatThrownBy(() -> service.getPullRequest(user, CONNECTION_ID, REF, 1))
                .isInstanceOf(ScmException.class);
        assertThatThrownBy(() -> service.listChangedFiles(user, CONNECTION_ID, REF, 1,
                PageQuery.of(0, 20, null)))
                .isInstanceOf(ScmException.class);
        assertThatThrownBy(() -> service.getDiff(user, CONNECTION_ID, REF, 1))
                .isInstanceOf(ScmException.class);

        // Four endpoints, four refusals, zero provider calls.
        verify(runner, never()).run(any(), any(), any());
        verify(runner, never()).run(any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("provider rate limiting propagates from a pull-request read")
    void propagatesRateLimiting() {
        accessGranted();
        when(runner.run(eq(context), any(), eq(ScmErrorCode.SCM_PULL_REQUEST_NOT_FOUND)))
                .thenThrow(new ScmException(ScmErrorCode.SCM_PROVIDER_RATE_LIMITED));

        assertThatThrownBy(() -> service.getDiff(user, CONNECTION_ID, REF, 123))
                .isInstanceOf(ScmException.class)
                .satisfies(thrown -> assertThat(
                        ((ScmException) thrown).getErrorCode().getHttpStatus().value()).isEqualTo(429));
    }

    @Test
    @DisplayName("isNull is never passed as the not-found code on a pull-request read")
    void pullRequestReadsAlwaysNameTheResource() {
        accessGranted();
        when(runner.run(eq(context), any(), eq(ScmErrorCode.SCM_PULL_REQUEST_NOT_FOUND)))
                .thenReturn(ScmResponses.text(ScmOperationCode.GET_PULL_REQUEST_DIFF, ""));

        service.getDiff(user, CONNECTION_ID, REF, 123);

        // Leaving it null would let the engine's generic resource-not-found reach the client, which says
        // nothing about what the user actually asked for.
        verify(runner, never()).run(any(), any(), isNull());
    }
}
