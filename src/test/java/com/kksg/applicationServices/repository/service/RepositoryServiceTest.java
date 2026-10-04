package com.kksg.applicationServices.repository.service;

import com.kksg.applicationServices.common.response.PageResponse;
import com.kksg.applicationServices.identity.entity.User;
import com.kksg.applicationServices.repository.RepositoryManagementProperties;
import com.kksg.applicationServices.repository.dto.RepositoryResponse;
import com.kksg.applicationServices.repository.dto.RepositoryVisibility;
import com.kksg.applicationServices.repository.dto.ScmResourceProvider;
import com.kksg.applicationServices.scm.common.exception.ScmErrorCode;
import com.kksg.applicationServices.scm.common.exception.ScmException;
import com.kksg.applicationServices.scm.common.model.NormalizedRepository;
import com.kksg.applicationServices.scm.common.model.ScmOperationCode;
import com.kksg.applicationServices.scm.common.model.ScmOperationRequest;
import com.kksg.applicationServices.scm.connection.entity.ScmConnection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
 * Repository browsing: the application behaviour, and the whole error matrix a client has to handle.
 *
 * <p>The access gate is mocked and the real {@link ScmPageScanner} is used, so these tests exercise the
 * composition rather than restating the scanner's own assertions. The point of each case here is the
 * <b>outcome a client sees</b> - which code, at which status - because that is the published contract.
 *
 * <p><b>Authorization is asserted as a precondition, not as an afterthought:</b> several tests verify
 * that no provider call is attempted at all when the gate refuses. A service that checked ownership and
 * then called the provider anyway would still be a leak.
 */
@ExtendWith(MockitoExtension.class)
class RepositoryServiceTest {

    private static final ScmResourceProvider PROVIDER = new ScmResourceProvider("GITHUB", "GitHub");
    private static final int CONNECTION_ID = 5;

    /**
     * What a 404 on the <i>listing</i> means.
     *
     * <p>Not a missing repository - the request names none. It means the account scope the listing is
     * made within could not be found, which is the scope derived from the connection on a provider
     * with no cross-account listing endpoint. Bitbucket's removal of its cross-workspace APIs is what
     * made this distinction load-bearing.
     */
    private static final ScmErrorCode LISTING_NOT_FOUND = ScmErrorCode.SCM_REPOSITORY_SCOPE_NOT_FOUND;

    @Mock
    private ScmResourceAccessService accessService;

    @Mock
    private ScmOperationRunner runner;

    private RepositoryService service;
    private ScmResourceContext context;
    private User user;

    @BeforeEach
    void setUp() {
        RepositoryManagementProperties properties = new RepositoryManagementProperties();
        properties.getSearch().setMaxPages(3);
        properties.getSearch().setPageSize(10);

        service = new RepositoryService(accessService, new ScmPageScanner(runner, properties), runner);

        user = new User();
        user.setId(1);

        ScmConnection connection = new ScmConnection();
        connection.setId(CONNECTION_ID);
        context = new ScmResourceContext(connection, 1, PROVIDER);
    }

    private void accessGranted() {
        when(accessService.requireUsableConnection(user, CONNECTION_ID)).thenReturn(context);
    }

    private void accessRefusedWith(ScmErrorCode code) {
        when(accessService.requireUsableConnection(user, CONNECTION_ID))
                .thenThrow(new ScmException(code));
    }

    private NormalizedRepository repository(String name) {
        return NormalizedRepository.builder()
                .externalId("id-" + name)
                .name(name)
                .fullName("acme/" + name)
                .owner("acme")
                .isPrivate(true)
                .defaultBranch("main")
                .description(name + " description")
                .webUrl("https://example.invalid/acme/" + name)
                .updatedAt("2026-10-01T10:00:00Z")
                .build();
    }

    /* --------------------------------------------------------------------- *
     * List
     * --------------------------------------------------------------------- */

    @Test
    @DisplayName("lists repositories through the LIST_REPOSITORIES operation")
    void listsRepositories() {
        accessGranted();
        when(runner.run(eq(context), any(), eq(LISTING_NOT_FOUND))).thenReturn(ScmResponses.list(
                ScmOperationCode.LIST_REPOSITORIES,
                List.of(repository("api"), repository("web")), true));

        PageResponse<RepositoryResponse> page =
                service.listRepositories(user, CONNECTION_ID, PageQuery.of(0, 20, null));

        assertThat(page.getContent()).extracting(RepositoryResponse::getFullName)
                .containsExactly("acme/api", "acme/web");
        assertThat(page.getContent().get(0).getVisibility()).isEqualTo(RepositoryVisibility.PRIVATE);
        assertThat(page.getContent().get(0).getProvider()).isSameAs(PROVIDER);
        assertThat(page.isHasNext()).isTrue();

        ArgumentCaptor<ScmOperationRequest> request = ArgumentCaptor.forClass(ScmOperationRequest.class);
        verify(runner).run(eq(context), request.capture(), eq(LISTING_NOT_FOUND));
        assertThat(request.getValue().getOperation()).isEqualTo(ScmOperationCode.LIST_REPOSITORIES);
    }

    @Test
    @DisplayName("an empty account is an empty page, not an error")
    void handlesEmptyRepositoryList() {
        accessGranted();
        when(runner.run(eq(context), any(), eq(LISTING_NOT_FOUND)))
                .thenReturn(ScmResponses.list(ScmOperationCode.LIST_REPOSITORIES, List.of(), false));

        PageResponse<RepositoryResponse> page =
                service.listRepositories(user, CONNECTION_ID, PageQuery.of(0, 20, null));

        assertThat(page.getContent()).isEmpty();
        assertThat(page.isHasNext()).isFalse();
        assertThat(page.isLast()).isTrue();
    }

    @Test
    @DisplayName("search narrows the listing")
    void searchNarrowsListing() {
        accessGranted();
        when(runner.run(eq(context), any(), eq(LISTING_NOT_FOUND), anyBoolean())).thenReturn(ScmResponses.list(
                ScmOperationCode.LIST_REPOSITORIES,
                List.of(repository("auth-service"), repository("billing"), repository("authz")), false));

        PageResponse<RepositoryResponse> page =
                service.listRepositories(user, CONNECTION_ID, PageQuery.of(0, 20, "auth"));

        assertThat(page.getContent()).extracting(RepositoryResponse::getName)
                .containsExactly("auth-service", "authz");
    }

    /* --------------------------------------------------------------------- *
     * Detail
     * --------------------------------------------------------------------- */

    @Test
    @DisplayName("reads one repository through GET_REPOSITORY with owner and repo")
    void getsRepository() {
        accessGranted();
        when(runner.run(eq(context), any(), eq(ScmErrorCode.SCM_REPOSITORY_NOT_FOUND)))
                .thenReturn(ScmResponses.object(ScmOperationCode.GET_REPOSITORY, repository("api")));

        RepositoryResponse response =
                service.getRepository(user, CONNECTION_ID, new RepositoryRef("acme", "api"));

        assertThat(response.getFullName()).isEqualTo("acme/api");
        assertThat(response.getDefaultBranch()).isEqualTo("main");

        ArgumentCaptor<ScmOperationRequest> request = ArgumentCaptor.forClass(ScmOperationRequest.class);
        verify(runner).run(eq(context), request.capture(), eq(ScmErrorCode.SCM_REPOSITORY_NOT_FOUND));

        // Addressed by owner-qualified name, which is what the provider operations accept - not by the
        // repository id, which no configured operation takes.
        assertThat(request.getValue().getParameters())
                .containsEntry("owner", "acme")
                .containsEntry("repo", "api");
    }

    @Test
    @DisplayName("a repository the credential cannot see is SCM_REPOSITORY_NOT_FOUND")
    void translatesProviderNotFound() {
        accessGranted();
        // The runner is what performs the translation; here it is already applied, and this asserts the
        // service asks for the right code rather than letting a generic 404 through.
        when(runner.run(eq(context), any(), eq(ScmErrorCode.SCM_REPOSITORY_NOT_FOUND)))
                .thenThrow(new ScmException(ScmErrorCode.SCM_REPOSITORY_NOT_FOUND));

        assertThatThrownBy(() ->
                service.getRepository(user, CONNECTION_ID, new RepositoryRef("acme", "secret")))
                .isInstanceOf(ScmException.class)
                .satisfies(thrown -> {
                    ScmErrorCode code = ((ScmException) thrown).getErrorCode();
                    assertThat(code).isEqualTo(ScmErrorCode.SCM_REPOSITORY_NOT_FOUND);
                    assertThat(code.getHttpStatus().value()).isEqualTo(404);
                });
    }

    @Test
    @DisplayName("a 2xx that normalizes to nothing is a mapping fault, not a missing repository")
    void emptyNormalizedResponseIsMappingFailure() {
        accessGranted();
        when(runner.run(eq(context), any(), eq(ScmErrorCode.SCM_REPOSITORY_NOT_FOUND)))
                .thenReturn(ScmResponses.object(ScmOperationCode.GET_REPOSITORY, null));

        // Reporting this as "not found" would send an operator looking at permissions for what is
        // actually a defective response_mapping.
        assertThatThrownBy(() ->
                service.getRepository(user, CONNECTION_ID, new RepositoryRef("acme", "api")))
                .isInstanceOf(ScmException.class)
                .satisfies(thrown -> assertThat(((ScmException) thrown).getErrorCode())
                        .isEqualTo(ScmErrorCode.SCM_RESPONSE_MAPPING_INVALID));
    }

    /* --------------------------------------------------------------------- *
     * Authorization - no provider call may be attempted
     * --------------------------------------------------------------------- */

    @Test
    @DisplayName("another user's connection is refused before any provider call")
    void refusesUnauthorizedConnection() {
        accessRefusedWith(ScmErrorCode.SCM_CONNECTION_NOT_FOUND);

        assertThatThrownBy(() ->
                service.listRepositories(user, CONNECTION_ID, PageQuery.of(0, 20, null)))
                .isInstanceOf(ScmException.class)
                .satisfies(thrown -> assertThat(((ScmException) thrown).getErrorCode())
                        .isEqualTo(ScmErrorCode.SCM_CONNECTION_NOT_FOUND));

        // The gate is a precondition, not a formality: nothing reached the provider.
        verify(runner, never()).run(any(), any(), any());
        verify(runner, never()).run(any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("a disconnected connection is refused before any provider call")
    void refusesInactiveConnection() {
        accessRefusedWith(ScmErrorCode.SCM_CONNECTION_NOT_ACTIVE);

        assertThatThrownBy(() ->
                service.listRepositories(user, CONNECTION_ID, PageQuery.of(0, 20, null)))
                .isInstanceOf(ScmException.class)
                .satisfies(thrown -> assertThat(((ScmException) thrown).getErrorCode())
                        .isEqualTo(ScmErrorCode.SCM_CONNECTION_NOT_ACTIVE));

        verify(runner, never()).run(any(), any(), any());
    }

    @Test
    @DisplayName("a deactivated provider is refused before any provider call")
    void refusesInactiveProvider() {
        accessRefusedWith(ScmErrorCode.SCM_PROVIDER_INACTIVE);

        assertThatThrownBy(() ->
                service.getRepository(user, CONNECTION_ID, new RepositoryRef("acme", "api")))
                .isInstanceOf(ScmException.class)
                .satisfies(thrown -> assertThat(((ScmException) thrown).getErrorCode())
                        .isEqualTo(ScmErrorCode.SCM_PROVIDER_INACTIVE));

        verify(runner, never()).run(any(), any(), any());
    }

    /* --------------------------------------------------------------------- *
     * Provider failures propagate with their own meaning intact
     * --------------------------------------------------------------------- */

    @Test
    @DisplayName("an unsupported operation surfaces as SCM_OPERATION_NOT_SUPPORTED, a 400")
    void propagatesUnsupportedOperation() {
        accessGranted();
        when(runner.run(eq(context), any(), eq(LISTING_NOT_FOUND)))
                .thenThrow(new ScmException(ScmErrorCode.SCM_OPERATION_NOT_SUPPORTED));

        assertThatThrownBy(() ->
                service.listRepositories(user, CONNECTION_ID, PageQuery.of(0, 20, null)))
                .isInstanceOf(ScmException.class)
                .satisfies(thrown -> {
                    ScmErrorCode code = ((ScmException) thrown).getErrorCode();
                    assertThat(code).isEqualTo(ScmErrorCode.SCM_OPERATION_NOT_SUPPORTED);
                    assertThat(code.getHttpStatus().value()).isEqualTo(400);
                });
    }

    @Test
    @DisplayName("a provider API failure surfaces as a 502, not a 500")
    void propagatesProviderApiError() {
        accessGranted();
        when(runner.run(eq(context), any(), eq(LISTING_NOT_FOUND)))
                .thenThrow(new ScmException(ScmErrorCode.SCM_PROVIDER_API_ERROR));

        assertThatThrownBy(() ->
                service.listRepositories(user, CONNECTION_ID, PageQuery.of(0, 20, null)))
                .isInstanceOf(ScmException.class)
                .satisfies(thrown -> {
                    ScmErrorCode code = ((ScmException) thrown).getErrorCode();
                    assertThat(code).isEqualTo(ScmErrorCode.SCM_PROVIDER_API_ERROR);
                    // The upstream failed, not us. A 500 would point an operator at the wrong system.
                    assertThat(code.getHttpStatus().value()).isEqualTo(502);
                });
    }

    @Test
    @DisplayName("provider rate limiting surfaces as 429 so the UI can say so")
    void propagatesRateLimiting() {
        accessGranted();
        when(runner.run(eq(context), any(), eq(LISTING_NOT_FOUND)))
                .thenThrow(new ScmException(ScmErrorCode.SCM_PROVIDER_RATE_LIMITED));

        assertThatThrownBy(() ->
                service.listRepositories(user, CONNECTION_ID, PageQuery.of(0, 20, null)))
                .isInstanceOf(ScmException.class)
                .satisfies(thrown -> {
                    ScmErrorCode code = ((ScmException) thrown).getErrorCode();
                    assertThat(code).isEqualTo(ScmErrorCode.SCM_PROVIDER_RATE_LIMITED);
                    // Distinct from a generic failure precisely so the UI can offer "try again shortly"
                    // instead of "internal server error".
                    assertThat(code.getHttpStatus().value()).isEqualTo(429);
                });
    }

    @Test
    @DisplayName("an expired credential surfaces as 401 so the client can offer reconnect")
    void propagatesExpiredCredential() {
        accessGranted();
        when(runner.run(eq(context), any(), eq(LISTING_NOT_FOUND)))
                .thenThrow(new ScmException(ScmErrorCode.SCM_CONNECTION_EXPIRED));

        assertThatThrownBy(() ->
                service.listRepositories(user, CONNECTION_ID, PageQuery.of(0, 20, null)))
                .isInstanceOf(ScmException.class)
                .satisfies(thrown -> assertThat(
                        ((ScmException) thrown).getErrorCode().getHttpStatus().value()).isEqualTo(401));
    }
}
