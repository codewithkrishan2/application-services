package com.kksg.applicationServices.repository.controller;

import com.kksg.applicationServices.common.exception.GlobalExceptionHandler;
import com.kksg.applicationServices.common.response.PageResponse;
import com.kksg.applicationServices.identity.entity.User;
import com.kksg.applicationServices.repository.dto.RepositoryOwner;
import com.kksg.applicationServices.repository.dto.RepositoryResponse;
import com.kksg.applicationServices.repository.dto.RepositoryVisibility;
import com.kksg.applicationServices.repository.dto.ScmResourceProvider;
import com.kksg.applicationServices.repository.service.PageQuery;
import com.kksg.applicationServices.repository.service.RepositoryRef;
import com.kksg.applicationServices.repository.service.RepositoryService;
import com.kksg.applicationServices.scm.common.exception.ScmErrorCode;
import com.kksg.applicationServices.scm.common.exception.ScmException;
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
 * The repository HTTP contract: routing, parameter binding, response envelope and error status.
 *
 * <p><b>Standalone MockMvc rather than {@code @WebMvcTest}.</b> These assertions are about request
 * mapping and serialisation, neither of which needs a Spring context, a datasource or a security
 * filter chain - and a full context would make this file depend on a running Postgres via
 * Testcontainers, turning a millisecond test into a minute. {@code GlobalExceptionHandler} is
 * registered explicitly, because the mapping from {@code ScmErrorCode} to HTTP status is exactly the
 * part of the contract worth asserting.
 *
 * <p>Authentication is not exercised here. {@code @AuthenticationPrincipal} is resolved by Spring
 * Security, which is configured once, globally, with {@code anyRequest().authenticated()} - so
 * asserting it per controller would test the framework rather than this code. What <i>is</i> asserted
 * is that the resolved principal is passed to the service, since that is what makes the ownership check
 * possible at all.
 */
@ExtendWith(MockitoExtension.class)
class RepositoryControllerTest {

    private static final String BASE = "/api/v1/scm/connections/5/repositories";

    @Mock
    private RepositoryService repositoryService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new RepositoryController(repositoryService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new TestPrincipalResolver())
                .build();
    }

    private RepositoryResponse repository() {
        return RepositoryResponse.builder()
                .id("987654")
                .name("my-service")
                .fullName("acme/my-service")
                .description("Service description")
                .defaultBranch("main")
                .visibility(RepositoryVisibility.PRIVATE)
                .webUrl("https://example.invalid/acme/my-service")
                .owner(new RepositoryOwner("1234", "acme", "https://example.invalid/a.png"))
                .provider(new ScmResourceProvider("GITHUB", "GitHub"))
                .updatedAt(Instant.parse("2026-10-01T10:00:00Z"))
                .build();
    }

    @Test
    @DisplayName("GET .../repositories returns a page inside the standard envelope")
    void listsRepositories() throws Exception {
        when(repositoryService.listRepositories(any(), eq(5), any()))
                .thenReturn(PageResponse.of(List.of(repository()), 0, 20, true));

        mockMvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.content[0].fullName").value("acme/my-service"))
                .andExpect(jsonPath("$.data.content[0].visibility").value("PRIVATE"))
                .andExpect(jsonPath("$.data.content[0].owner.name").value("acme"))
                .andExpect(jsonPath("$.data.content[0].provider.code").value("GITHUB"))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andExpect(jsonPath("$.data.first").value(true))
                .andExpect(jsonPath("$.data.last").value(false))
                // Absent rather than fabricated when the provider publishes no total.
                .andExpect(jsonPath("$.data.totalElements").doesNotExist())
                .andExpect(jsonPath("$.data.totalPages").doesNotExist());
    }

    @Test
    @DisplayName("no response field carries credential material")
    void responseCarriesNoCredentials() throws Exception {
        when(repositoryService.listRepositories(any(), eq(5), any()))
                .thenReturn(PageResponse.of(List.of(repository()), 0, 20, false));

        String body = mockMvc.perform(get(BASE)).andReturn().getResponse().getContentAsString();

        // The DTO has no such fields, so this is a guard against one being added later rather than a
        // test of present behaviour - which is precisely when it would matter.
        assertThat(body).doesNotContain("token", "Token", "secret", "Secret", "credential");
    }

    @Test
    @DisplayName("binds page, size and search")
    void bindsQueryParameters() throws Exception {
        when(repositoryService.listRepositories(any(), eq(5), any()))
                .thenReturn(PageResponse.of(List.of(), 2, 50, false));

        mockMvc.perform(get(BASE).param("page", "2").param("size", "50").param("search", "code"))
                .andExpect(status().isOk());

        ArgumentCaptor<PageQuery> query = ArgumentCaptor.forClass(PageQuery.class);
        verify(repositoryService).listRepositories(any(), eq(5), query.capture());

        assertThat(query.getValue().page()).isEqualTo(2);
        assertThat(query.getValue().size()).isEqualTo(50);
        assertThat(query.getValue().search()).isEqualTo("code");
    }

    @Test
    @DisplayName("forwards the authenticated principal to the service")
    void forwardsPrincipalToService() throws Exception {
        when(repositoryService.listRepositories(any(), eq(5), any()))
                .thenReturn(PageResponse.of(List.of(), 0, 20, false));

        mockMvc.perform(get(BASE)).andExpect(status().isOk());

        ArgumentCaptor<User> principal = ArgumentCaptor.forClass(User.class);
        verify(repositoryService).listRepositories(principal.capture(), eq(5), any());

        // The controller forwards the resolved principal rather than looking a user up itself, which is
        // what keeps the ownership check in one place and makes it impossible to omit.
        assertThat(principal.getValue().getId()).isEqualTo(TestPrincipalResolver.USER_ID);
    }

    @Test
    @DisplayName("GET .../repositories/{owner}/{repo} addresses by owner-qualified name")
    void getsRepositoryByOwnerAndName() throws Exception {
        when(repositoryService.getRepository(any(), eq(5), any())).thenReturn(repository());

        mockMvc.perform(get(BASE + "/acme/my-service"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fullName").value("acme/my-service"))
                .andExpect(jsonPath("$.data.defaultBranch").value("main"));

        ArgumentCaptor<RepositoryRef> ref = ArgumentCaptor.forClass(RepositoryRef.class);
        verify(repositoryService).getRepository(any(), eq(5), ref.capture());

        // Two path segments rather than one encoded segment, so the route does not depend on servlet
        // containers and proxies passing an encoded slash through intact.
        assertThat(ref.getValue().owner()).isEqualTo("acme");
        assertThat(ref.getValue().name()).isEqualTo("my-service");
    }

    @Test
    @DisplayName("a rejected size is 400 with the error code on the payload")
    void invalidSizeIsBadRequest() throws Exception {
        mockMvc.perform(get(BASE).param("size", "5000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.errors.code").value("SCM_REQUEST_INVALID"));
    }

    @Test
    @DisplayName("a non-numeric connection id is 400, not 404")
    void nonNumericConnectionIdIsBadRequest() throws Exception {
        // connectionId is an Integer path variable, so Spring's type mismatch handler answers first.
        mockMvc.perform(get("/api/v1/scm/connections/not-a-number/repositories"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("another user's connection is 404 with SCM_CONNECTION_NOT_FOUND")
    void unauthorizedConnectionIsNotFound() throws Exception {
        when(repositoryService.listRepositories(any(), eq(5), any()))
                .thenThrow(new ScmException(ScmErrorCode.SCM_CONNECTION_NOT_FOUND));

        // Not 403: a 403 would confirm the connection id exists.
        mockMvc.perform(get(BASE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errors.code").value("SCM_CONNECTION_NOT_FOUND"));
    }

    @Test
    @DisplayName("a disconnected connection is 409 with SCM_CONNECTION_NOT_ACTIVE")
    void inactiveConnectionIsConflict() throws Exception {
        when(repositoryService.listRepositories(any(), eq(5), any()))
                .thenThrow(new ScmException(ScmErrorCode.SCM_CONNECTION_NOT_ACTIVE));

        mockMvc.perform(get(BASE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors.code").value("SCM_CONNECTION_NOT_ACTIVE"));
    }

    @Test
    @DisplayName("an unseen repository is 404 with SCM_REPOSITORY_NOT_FOUND")
    void missingRepositoryIsNotFound() throws Exception {
        when(repositoryService.getRepository(any(), eq(5), any()))
                .thenThrow(new ScmException(ScmErrorCode.SCM_REPOSITORY_NOT_FOUND));

        mockMvc.perform(get(BASE + "/acme/secret"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errors.code").value("SCM_REPOSITORY_NOT_FOUND"));
    }

    @Test
    @DisplayName("an unsupported provider operation is 400 with SCM_OPERATION_NOT_SUPPORTED")
    void unsupportedOperationIsBadRequest() throws Exception {
        when(repositoryService.listRepositories(any(), eq(5), any()))
                .thenThrow(new ScmException(ScmErrorCode.SCM_OPERATION_NOT_SUPPORTED));

        mockMvc.perform(get(BASE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.code").value("SCM_OPERATION_NOT_SUPPORTED"));
    }

    @Test
    @DisplayName("a provider API failure is 502, so the UI does not report our fault")
    void providerFailureIsBadGateway() throws Exception {
        when(repositoryService.listRepositories(any(), eq(5), any()))
                .thenThrow(new ScmException(ScmErrorCode.SCM_PROVIDER_API_ERROR));

        mockMvc.perform(get(BASE))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.errors.code").value("SCM_PROVIDER_API_ERROR"));
    }

    @Test
    @DisplayName("provider rate limiting is 429, not 500")
    void rateLimitIsTooManyRequests() throws Exception {
        when(repositoryService.listRepositories(any(), eq(5), any()))
                .thenThrow(new ScmException(ScmErrorCode.SCM_PROVIDER_RATE_LIMITED));

        // The UI needs to be able to say "try again shortly" rather than "internal server error".
        mockMvc.perform(get(BASE))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errors.code").value("SCM_PROVIDER_RATE_LIMITED"));
    }

    @Test
    @DisplayName("an expired credential is 401 so the client can offer reconnect")
    void expiredCredentialIsUnauthorized() throws Exception {
        when(repositoryService.listRepositories(any(), eq(5), any()))
                .thenThrow(new ScmException(ScmErrorCode.SCM_CONNECTION_EXPIRED));

        mockMvc.perform(get(BASE))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errors.code").value("SCM_CONNECTION_EXPIRED"));
    }
}
