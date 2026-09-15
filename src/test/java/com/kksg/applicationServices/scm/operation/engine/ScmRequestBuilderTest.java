package com.kksg.applicationServices.scm.operation.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kksg.applicationServices.scm.common.exception.ScmErrorCode;
import com.kksg.applicationServices.scm.common.exception.ScmException;
import com.kksg.applicationServices.scm.common.model.ScmOperationCode;
import com.kksg.applicationServices.scm.common.model.ScmOperationRequest;
import com.kksg.applicationServices.scm.operation.entity.ScmProviderOperation;
import com.kksg.applicationServices.scm.operation.service.ResolvedOperation;
import com.kksg.applicationServices.scm.provider.config.ProviderConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Request building: how one normalized operation becomes a concrete, provider-specific HTTP request.
 *
 * <p>Two behaviours here are load-bearing and easy to get wrong:
 * <ul>
 *   <li>a query parameter whose placeholder is unresolved must be <b>dropped</b>, which is what makes
 *       optional filters work without extra configuration;</li>
 *   <li>path values must be URL-encoded <b>before</b> substitution, so a value containing a slash cannot
 *       inject an extra path segment and redirect the request to a different endpoint.</li>
 * </ul>
 */
class ScmRequestBuilderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ScmRequestBuilder builder = new ScmRequestBuilder();

    private ProviderConfiguration configuration(String json) throws Exception {
        return objectMapper.readValue(json, ProviderConfiguration.class);
    }

    private ProviderConfiguration pageBasedConfiguration() throws Exception {
        return configuration("""
                {
                  "api": {
                    "baseUrl": "https://api.github.com",
                    "defaultHeaders": { "Accept": "application/vnd.github+json" },
                    "authentication": { "scheme": "BEARER", "header": "Authorization", "valuePrefix": "Bearer " }
                  },
                  "pagination": {
                    "type": "PAGE", "pageParameter": "page", "sizeParameter": "per_page",
                    "defaultPageSize": 50, "maxPageSize": 100
                  }
                }
                """);
    }

    private ResolvedOperation operation(ScmOperationCode code, String method, String template,
                                        String requestConfigurationJson) throws Exception {
        ScmProviderOperation entity = new ScmProviderOperation();
        entity.setOperationCode(code);
        entity.setHttpMethod(method);
        entity.setEndpointTemplate(template);

        RequestConfiguration requestConfiguration = requestConfigurationJson == null
                ? RequestConfiguration.empty()
                : objectMapper.readValue(requestConfigurationJson, RequestConfiguration.class);

        return new ResolvedOperation(entity, requestConfiguration, ResponseMapping.raw());
    }

    @Test
    @DisplayName("resolves the endpoint template and attaches the bearer credential")
    void buildsAuthenticatedRequest() throws Exception {
        ResolvedOperation resolved = operation(ScmOperationCode.GET_REPOSITORY, "GET",
                "/repos/{{owner}}/{{repo}}", """
                { "requiredParameters": ["owner", "repo"] }
                """);

        ScmRequestBuilder.BuiltRequest built = builder.build(
                ScmOperationRequest.of(ScmOperationCode.GET_REPOSITORY)
                        .parameter("owner", "acme").parameter("repo", "api"),
                pageBasedConfiguration(), resolved, "https://api.github.com", "secret-token");

        ScmHttpRequest request = built.request();
        assertThat(request.getMethod()).isEqualTo(HttpMethod.GET);
        assertThat(request.getUri()).isEqualTo("https://api.github.com/repos/acme/api");
        assertThat(request.getHeaders()).containsEntry(HttpHeaders.AUTHORIZATION, "Bearer secret-token");
        assertThat(request.getHeaders()).containsEntry("Accept", "application/vnd.github+json");
        assertThat(request.isExpectTextResponse()).isFalse();
    }

    @Test
    @DisplayName("drops query parameters whose placeholders are unresolved")
    void unresolvedQueryParametersAreOmitted() throws Exception {
        ResolvedOperation resolved = operation(ScmOperationCode.LIST_PULL_REQUESTS, "GET",
                "/repos/{{owner}}/{{repo}}/pulls", """
                {
                  "paginated": true,
                  "queryParams": { "page": "{{page}}", "per_page": "{{pageSize}}", "state": "{{state}}" }
                }
                """);

        // `state` is deliberately not supplied.
        ScmRequestBuilder.BuiltRequest built = builder.build(
                ScmOperationRequest.of(ScmOperationCode.LIST_PULL_REQUESTS)
                        .parameter("owner", "acme").parameter("repo", "api").page(2, 25),
                pageBasedConfiguration(), resolved, "https://api.github.com", "token");

        assertThat(built.request().getUri())
                .contains("page=2")
                .contains("per_page=25")
                .doesNotContain("state");
    }

    @Test
    @DisplayName("applies the provider default page size when the caller omits one")
    void appliesPagingDefaults() throws Exception {
        ResolvedOperation resolved = operation(ScmOperationCode.LIST_REPOSITORIES, "GET", "/user/repos", """
                { "paginated": true, "queryParams": { "page": "{{page}}", "per_page": "{{pageSize}}" } }
                """);

        ScmRequestBuilder.BuiltRequest built = builder.build(
                ScmOperationRequest.of(ScmOperationCode.LIST_REPOSITORIES),
                pageBasedConfiguration(), resolved, "https://api.github.com", "token");

        assertThat(built.request().getUri()).contains("page=1").contains("per_page=50");
        assertThat(built.parameters()).containsEntry("page", 1).containsEntry("pageSize", 50);
    }

    @Test
    @DisplayName("clamps an over-large page size to the provider maximum")
    void clampsPageSizeToProviderMaximum() throws Exception {
        // Not politeness: an unclamped request gets silently truncated by the provider, which would make
        // the caller's "did I get a full page?" check wrong and stop pagination early.
        ResolvedOperation resolved = operation(ScmOperationCode.LIST_REPOSITORIES, "GET", "/user/repos", """
                { "paginated": true, "queryParams": { "per_page": "{{pageSize}}" } }
                """);

        ScmRequestBuilder.BuiltRequest built = builder.build(
                ScmOperationRequest.of(ScmOperationCode.LIST_REPOSITORIES).page(1, 5000),
                pageBasedConfiguration(), resolved, "https://api.github.com", "token");

        assertThat(built.request().getUri()).contains("per_page=100");
        assertThat(built.parameters()).containsEntry("pageSize", 100);
    }

    @Test
    @DisplayName("pathParams let a provider rename a caller parameter for its own URL vocabulary")
    void resolvesPathAliases() throws Exception {
        ResolvedOperation resolved = operation(ScmOperationCode.LIST_REPOSITORIES, "GET",
                "/repositories/{{workspace}}", """
                { "pathParams": { "workspace": "{{owner}}" }, "requiredParameters": ["owner"] }
                """);

        ScmRequestBuilder.BuiltRequest built = builder.build(
                ScmOperationRequest.of(ScmOperationCode.LIST_REPOSITORIES).parameter("owner", "acme"),
                pageBasedConfiguration(), resolved, "https://api.bitbucket.org/2.0", "token");

        assertThat(built.request().getUri()).isEqualTo("https://api.bitbucket.org/2.0/repositories/acme");
    }

    @Test
    @DisplayName("fails before any network call when a required parameter is absent")
    void failsFastOnMissingRequiredParameter() throws Exception {
        ResolvedOperation resolved = operation(ScmOperationCode.GET_REPOSITORY, "GET",
                "/repos/{{owner}}/{{repo}}", """
                { "requiredParameters": ["owner", "repo"] }
                """);

        assertThatThrownBy(() -> builder.build(
                ScmOperationRequest.of(ScmOperationCode.GET_REPOSITORY).parameter("owner", "acme"),
                pageBasedConfiguration(), resolved, "https://api.github.com", "token"))
                .isInstanceOf(ScmException.class)
                .satisfies(thrown -> assertThat(((ScmException) thrown).getErrorCode())
                        .isEqualTo(ScmErrorCode.SCM_OPERATION_PARAMETER_MISSING))
                .hasMessageContaining("repo");
    }

    @Test
    @DisplayName("encodes path values so a slash cannot inject a new path segment")
    void encodesPathSegments() throws Exception {
        ResolvedOperation resolved = operation(ScmOperationCode.GET_REPOSITORY, "GET",
                "/repos/{{owner}}/{{repo}}", null);

        ScmRequestBuilder.BuiltRequest built = builder.build(
                ScmOperationRequest.of(ScmOperationCode.GET_REPOSITORY)
                        .parameter("owner", "acme")
                        .parameter("repo", "weird/../name"),
                pageBasedConfiguration(), resolved, "https://api.github.com", "token");

        // The slash and dots survive as an encoded single segment rather than traversing the URL path.
        assertThat(built.request().getUri()).doesNotContain("weird/../name");
        assertThat(built.request().getUri()).contains("weird%2F..%2Fname");
    }

    @Test
    @DisplayName("bodyTemplate reshapes one normalized comment into each provider's payload")
    void bodyTemplateReshapesRequestPayload() throws Exception {
        // GitHub wants {"body": "..."}
        ResolvedOperation githubComment = operation(ScmOperationCode.CREATE_PR_COMMENT, "POST",
                "/repos/{{owner}}/{{repo}}/issues/{{pullRequestNumber}}/comments", """
                { "bodyTemplate": { "body": "{{comment}}" } }
                """);

        ScmRequestBuilder.BuiltRequest githubBuilt = builder.build(
                ScmOperationRequest.of(ScmOperationCode.CREATE_PR_COMMENT)
                        .parameter("owner", "acme").parameter("repo", "api")
                        .parameter("pullRequestNumber", 7).parameter("comment", "Looks good"),
                pageBasedConfiguration(), githubComment, "https://api.github.com", "token");

        assertThat(githubBuilt.request().getBody()).isEqualTo(Map.of("body", "Looks good"));

        // Bitbucket wants {"content": {"raw": "..."}} - same caller input, different shape, no Java branch.
        ResolvedOperation bitbucketComment = operation(ScmOperationCode.CREATE_PR_COMMENT, "POST",
                "/repositories/{{owner}}/{{repo}}/pullrequests/{{pullRequestNumber}}/comments", """
                { "bodyTemplate": { "content": { "raw": "{{comment}}" } } }
                """);

        ScmRequestBuilder.BuiltRequest bitbucketBuilt = builder.build(
                ScmOperationRequest.of(ScmOperationCode.CREATE_PR_COMMENT)
                        .parameter("owner", "acme").parameter("repo", "api")
                        .parameter("pullRequestNumber", 7).parameter("comment", "Looks good"),
                pageBasedConfiguration(), bitbucketComment, "https://api.bitbucket.org/2.0", "token");

        assertThat(bitbucketBuilt.request().getBody())
                .isEqualTo(Map.of("content", Map.of("raw", "Looks good")));
    }

    @Test
    @DisplayName("body template keys whose placeholders are unresolved are dropped")
    void optionalBodyFieldsAreDropped() throws Exception {
        ResolvedOperation resolved = operation(ScmOperationCode.CREATE_PR_REVIEW, "POST",
                "/repos/{{owner}}/{{repo}}/pulls/{{pullRequestNumber}}/reviews", """
                { "bodyTemplate": { "body": "{{comment}}", "event": "{{reviewEvent}}" } }
                """);

        ScmRequestBuilder.BuiltRequest built = builder.build(
                ScmOperationRequest.of(ScmOperationCode.CREATE_PR_REVIEW)
                        .parameter("owner", "a").parameter("repo", "b")
                        .parameter("pullRequestNumber", 1).parameter("comment", "text"),
                pageBasedConfiguration(), resolved, "https://api.github.com", "token");

        assertThat(built.request().getBody()).isEqualTo(Map.of("body", "text"));
    }

    @Test
    @DisplayName("literal body template values, including arrays, pass through unchanged")
    void literalBodyValuesArePreserved() throws Exception {
        ResolvedOperation resolved = operation(ScmOperationCode.CREATE_WEBHOOK, "POST",
                "/repos/{{owner}}/{{repo}}/hooks", """
                {
                  "bodyTemplate": {
                    "name": "web",
                    "active": true,
                    "events": ["pull_request"],
                    "config": { "url": "{{webhookUrl}}", "secret": "{{webhookSecret}}" }
                  }
                }
                """);

        ScmRequestBuilder.BuiltRequest built = builder.build(
                ScmOperationRequest.of(ScmOperationCode.CREATE_WEBHOOK)
                        .parameter("owner", "a").parameter("repo", "b")
                        .parameter("webhookUrl", "https://app.example.com/hook")
                        .parameter("webhookSecret", "s3cr3t"),
                pageBasedConfiguration(), resolved, "https://api.github.com", "token");

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) built.request().getBody();
        assertThat(body).containsEntry("name", "web").containsEntry("active", true);
        assertThat(body.get("events")).isEqualTo(java.util.List.of("pull_request"));
        assertThat(body.get("config")).isEqualTo(
                Map.of("url", "https://app.example.com/hook", "secret", "s3cr3t"));
    }

    @Test
    @DisplayName("operation headers override provider default headers")
    void operationHeadersWinOverProviderDefaults() throws Exception {
        ResolvedOperation resolved = operation(ScmOperationCode.GET_PULL_REQUEST_DIFF, "GET",
                "/repos/{{owner}}/{{repo}}/pulls/{{pullRequestNumber}}", """
                { "headers": { "Accept": "application/vnd.github.v3.diff" } }
                """);

        ScmRequestBuilder.BuiltRequest built = builder.build(
                ScmOperationRequest.of(ScmOperationCode.GET_PULL_REQUEST_DIFF)
                        .parameter("owner", "a").parameter("repo", "b").parameter("pullRequestNumber", 1),
                pageBasedConfiguration(), resolved, "https://api.github.com", "token");

        assertThat(built.request().getHeaders()).containsEntry("Accept", "application/vnd.github.v3.diff");
    }

    @Test
    @DisplayName("a TEXT response mapping marks the request as expecting text")
    void textMappingSetsTextExpectation() throws Exception {
        ScmProviderOperation entity = new ScmProviderOperation();
        entity.setOperationCode(ScmOperationCode.GET_PULL_REQUEST_DIFF);
        entity.setHttpMethod("GET");
        entity.setEndpointTemplate("/diff");

        ResolvedOperation resolved = new ResolvedOperation(entity, RequestConfiguration.empty(),
                new ResponseMapping(ResponseMapping.MappingType.TEXT, null, null, null, null, null));

        ScmRequestBuilder.BuiltRequest built = builder.build(
                ScmOperationRequest.of(ScmOperationCode.GET_PULL_REQUEST_DIFF),
                pageBasedConfiguration(), resolved, "https://api.github.com", "token");

        assertThat(built.request().isExpectTextResponse()).isTrue();
    }

    @Test
    @DisplayName("no credential header is written when authentication scheme is NONE")
    void authenticationSchemeNoneOmitsCredential() throws Exception {
        ProviderConfiguration unauthenticated = configuration("""
                {
                  "api": { "baseUrl": "https://api.example.com", "authentication": { "scheme": "NONE" } }
                }
                """);
        ResolvedOperation resolved = operation(ScmOperationCode.GET_CURRENT_ACCOUNT, "GET", "/user", null);

        ScmRequestBuilder.BuiltRequest built = builder.build(
                ScmOperationRequest.of(ScmOperationCode.GET_CURRENT_ACCOUNT),
                unauthenticated, resolved, "https://api.example.com", "token");

        assertThat(built.request().getHeaders()).doesNotContainKey(HttpHeaders.AUTHORIZATION);
    }

    @Test
    @DisplayName("a custom header scheme writes the token without a Bearer prefix")
    void headerAuthenticationSchemeUsesCustomHeader() throws Exception {
        ProviderConfiguration gitlabStyle = configuration("""
                {
                  "api": {
                    "baseUrl": "https://gitlab.example.com/api/v4",
                    "authentication": { "scheme": "HEADER", "header": "PRIVATE-TOKEN", "valuePrefix": "" }
                  }
                }
                """);
        ResolvedOperation resolved = operation(ScmOperationCode.GET_CURRENT_ACCOUNT, "GET", "/user", null);

        ScmRequestBuilder.BuiltRequest built = builder.build(
                ScmOperationRequest.of(ScmOperationCode.GET_CURRENT_ACCOUNT),
                gitlabStyle, resolved, "https://gitlab.example.com/api/v4", "glpat-xyz");

        assertThat(built.request().getHeaders()).containsEntry("PRIVATE-TOKEN", "glpat-xyz");
        assertThat(built.request().getHeaders()).doesNotContainKey(HttpHeaders.AUTHORIZATION);
    }

    @Test
    @DisplayName("an operation with no http_method is rejected as invalid configuration")
    void missingHttpMethodIsInvalidConfiguration() throws Exception {
        ScmProviderOperation entity = new ScmProviderOperation();
        entity.setOperationCode(ScmOperationCode.GET_REPOSITORY);
        entity.setEndpointTemplate("/repos");
        ResolvedOperation resolved =
                new ResolvedOperation(entity, RequestConfiguration.empty(), ResponseMapping.raw());

        assertThatThrownBy(() -> builder.build(
                ScmOperationRequest.of(ScmOperationCode.GET_REPOSITORY),
                pageBasedConfiguration(), resolved, "https://api.github.com", "token"))
                .isInstanceOf(ScmException.class)
                .satisfies(thrown -> assertThat(((ScmException) thrown).getErrorCode())
                        .isEqualTo(ScmErrorCode.SCM_PROVIDER_CONFIGURATION_INVALID));
    }

    @Test
    @DisplayName("a trailing slash on the base URL does not produce a doubled slash")
    void normalizesBaseUrlAndPathJoin() throws Exception {
        ResolvedOperation resolved = operation(ScmOperationCode.GET_CURRENT_ACCOUNT, "GET", "user", null);

        ScmRequestBuilder.BuiltRequest built = builder.build(
                ScmOperationRequest.of(ScmOperationCode.GET_CURRENT_ACCOUNT),
                pageBasedConfiguration(), resolved, "https://api.github.com/", "token");

        assertThat(built.request().getUri()).isEqualTo("https://api.github.com/user");
    }
}
