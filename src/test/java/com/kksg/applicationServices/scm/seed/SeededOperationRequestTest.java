package com.kksg.applicationServices.scm.seed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kksg.applicationServices.scm.common.exception.ScmErrorCode;
import com.kksg.applicationServices.scm.common.exception.ScmException;
import com.kksg.applicationServices.scm.common.model.ScmOperationCode;
import com.kksg.applicationServices.scm.common.model.ScmOperationRequest;
import com.kksg.applicationServices.scm.connection.entity.ScmConnection;
import com.kksg.applicationServices.scm.operation.engine.RequestConfiguration;
import com.kksg.applicationServices.scm.operation.engine.ResponseMapping;
import com.kksg.applicationServices.scm.operation.engine.ScmRequestBuilder;
import com.kksg.applicationServices.scm.operation.entity.ScmProviderOperation;
import com.kksg.applicationServices.scm.operation.service.ResolvedOperation;
import com.kksg.applicationServices.scm.provider.config.ConnectionParameterResolver;
import com.kksg.applicationServices.scm.provider.config.ProviderConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What URL each <b>seeded</b> provider operation actually generates.
 *
 * <p><b>This is the regression suite for a production outage.</b> Bitbucket repository listing
 * returned 404 for every user because the seeded operation targeted
 * {@code GET /2.0/repositories} - the cross-workspace endpoint Atlassian ended support for on
 * 14 April 2026. The replacement is workspace-scoped:
 * {@code GET /2.0/repositories/&#123;workspace&#125;}.
 *
 * <p>Every existing engine test built its configuration <i>inline</i>, which is why none of them
 * caught it: they proved the builder resolves whatever template it is given, and the template was
 * wrong. These tests read the real {@code classpath:scm/seed/*.json} files, so they assert the
 * thing that was actually broken - what we will send to the provider.
 *
 * <p>They are deliberately exact about the full URI rather than asserting "contains workspace". A
 * looser assertion would still pass if the path regressed to a removed endpoint that happened to
 * mention the parameter somewhere.
 */
class SeededOperationRequestTest {

    private static final String BITBUCKET_BASE = "https://api.bitbucket.org/2.0";
    private static final String GITHUB_BASE = "https://api.github.com";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ScmRequestBuilder builder = new ScmRequestBuilder();
    private final ConnectionParameterResolver connectionParameters = new ConnectionParameterResolver();

    /* --------------------------------------------------------------------- *
     * Seed loading
     * --------------------------------------------------------------------- */

    private ScmProviderSeedDocument seed(String providerFile) throws Exception {
        try (InputStream input = new ClassPathResource("scm/seed/" + providerFile).getInputStream()) {
            return objectMapper.readValue(input, ScmProviderSeedDocument.class);
        }
    }

    private ProviderConfiguration configuration(ScmProviderSeedDocument document) {
        return objectMapper.convertValue(document.configuration(), ProviderConfiguration.class);
    }

    private ResolvedOperation operation(ScmProviderSeedDocument document, ScmOperationCode code) {
        ScmProviderSeedDocument.OperationSeed seed = document.operationsOrEmpty().stream()
                .filter(candidate -> code.name().equals(candidate.operationCode()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "%s declares no %s operation".formatted(document.providerCode(), code)));

        ScmProviderOperation entity = new ScmProviderOperation();
        entity.setOperationCode(code);
        entity.setHttpMethod(seed.httpMethod());
        entity.setEndpointTemplate(seed.endpointTemplate());

        RequestConfiguration requestConfiguration = seed.requestConfiguration() == null
                ? RequestConfiguration.empty()
                : objectMapper.convertValue(seed.requestConfiguration(), RequestConfiguration.class);

        ResponseMapping responseMapping = seed.responseMapping() == null
                ? ResponseMapping.raw()
                : objectMapper.convertValue(seed.responseMapping(), ResponseMapping.class);

        return new ResolvedOperation(entity, requestConfiguration, responseMapping);
    }

    /**
     * A connection as the OAuth connect flow leaves it.
     *
     * <p>{@code accountName} is what {@code GET_CURRENT_ACCOUNT} discovered, and {@code metadata} is
     * where a per-connection override would live. Nothing here is specific to a particular user.
     */
    private ScmConnection connection(String accountId, String accountName, Map<String, Object> metadata) {
        ScmConnection connection = new ScmConnection();
        connection.setId(7);
        connection.setExternalAccountId(accountId);
        connection.setExternalAccountName(accountName);
        connection.setMetadata(metadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata));
        return connection;
    }

    private String uriFor(String providerFile,
                          ScmOperationCode code,
                          ScmOperationRequest request,
                          ScmConnection connection,
                          String baseUrl) throws Exception {
        ScmProviderSeedDocument document = seed(providerFile);
        ProviderConfiguration configuration = configuration(document);

        return builder.build(request, configuration, operation(document, code), baseUrl, "token",
                        connectionParameters.resolve(configuration, connection))
                .request().getUri();
    }

    /* ===================================================================== *
     * Bitbucket - the regression
     * ===================================================================== */

    @Nested
    @DisplayName("Bitbucket LIST_REPOSITORIES")
    class BitbucketRepositoryListing {

        @Test
        @DisplayName("is workspace-scoped, not the removed cross-workspace endpoint")
        void isWorkspaceScoped() throws Exception {
            String uri = uriFor("bitbucket.json", ScmOperationCode.LIST_REPOSITORIES,
                    ScmOperationRequest.of(ScmOperationCode.LIST_REPOSITORIES).page(1, 20),
                    connection("{02ad5881-37c9-459b-8789-6398aacf66ab}", "acme-workspace", null),
                    BITBUCKET_BASE);

            // The exact path matters. /2.0/repositories was removed on 2026-04-14 and answers 404
            // "There is no API hosted at this URL" for every caller, authenticated or not.
            assertThat(uri).startsWith(BITBUCKET_BASE + "/repositories/acme-workspace?");
            assertThat(uri).contains("page=1").contains("pagelen=20").contains("sort=-updated_on");
        }

        @Test
        @DisplayName("never generates the removed cross-workspace path")
        void neverGeneratesRemovedPath() throws Exception {
            String uri = uriFor("bitbucket.json", ScmOperationCode.LIST_REPOSITORIES,
                    ScmOperationRequest.of(ScmOperationCode.LIST_REPOSITORIES).page(1, 20),
                    connection("{uuid}", "acme-workspace", null),
                    BITBUCKET_BASE);

            // Written as its own assertion because this is the single fact the outage turned on: the
            // path must carry a workspace segment, so the bare collection can never be requested.
            assertThat(uri).doesNotContain(BITBUCKET_BASE + "/repositories?");
            assertThat(uri.substring(BITBUCKET_BASE.length())).startsWith("/repositories/");
        }

        @Test
        @DisplayName("drops the role filter that silently returned nothing")
        void omitsRoleFilter() throws Exception {
            String uri = uriFor("bitbucket.json", ScmOperationCode.LIST_REPOSITORIES,
                    ScmOperationRequest.of(ScmOperationCode.LIST_REPOSITORIES).page(1, 20),
                    connection("{uuid}", "acme-workspace", null),
                    BITBUCKET_BASE);

            // role filters by the caller's *explicit* membership of each repository. Once the path is
            // already scoped to one workspace it adds nothing, and it demonstrably hides repositories a
            // user can see through group or workspace-level permissions - the live API returns size=0
            // for a workspace the caller can read but is not an explicit member of.
            assertThat(uri).doesNotContain("role=");
        }

        @Test
        @DisplayName("prefers an explicit per-connection workspace over the account name")
        void metadataOverrideWins() throws Exception {
            String uri = uriFor("bitbucket.json", ScmOperationCode.LIST_REPOSITORIES,
                    ScmOperationRequest.of(ScmOperationCode.LIST_REPOSITORIES).page(1, 20),
                    connection("{uuid}", "login-name", Map.of("workspace", "chosen-workspace")),
                    BITBUCKET_BASE);

            // Atlassian removed every workspace-discovery endpoint and has said it will not replace
            // them, so an account whose workspace slug differs from its login cannot be resolved
            // automatically. The override is the supported way to state it, and it has to win.
            assertThat(uri).startsWith(BITBUCKET_BASE + "/repositories/chosen-workspace?");
        }

        @Test
        @DisplayName("falls back to the account discovered at connect time")
        void fallsBackToAccountName() throws Exception {
            String uri = uriFor("bitbucket.json", ScmOperationCode.LIST_REPOSITORIES,
                    ScmOperationRequest.of(ScmOperationCode.LIST_REPOSITORIES).page(1, 20),
                    connection("{uuid}", "login-name", Map.of("displayName", "Someone")),
                    BITBUCKET_BASE);

            // Unrelated metadata must not be mistaken for an override.
            assertThat(uri).startsWith(BITBUCKET_BASE + "/repositories/login-name?");
        }

        @Test
        @DisplayName("a caller-supplied workspace outranks both")
        void callerParameterWins() throws Exception {
            String uri = uriFor("bitbucket.json", ScmOperationCode.LIST_REPOSITORIES,
                    ScmOperationRequest.of(ScmOperationCode.LIST_REPOSITORIES)
                            .parameter("workspace", "explicit")
                            .page(1, 20),
                    connection("{uuid}", "login-name", Map.of("workspace", "metadata")),
                    BITBUCKET_BASE);

            // Connection values are defaults, not overrides: a request that deliberately addresses a
            // different workspace must still be able to.
            assertThat(uri).startsWith(BITBUCKET_BASE + "/repositories/explicit?");
        }

        @Test
        @DisplayName("an unresolvable workspace fails naming the parameter, before any network call")
        void unresolvableWorkspaceFailsFast() throws Exception {
            ScmProviderSeedDocument document = seed("bitbucket.json");
            ProviderConfiguration configuration = configuration(document);
            // A connection with no account name and no override - nothing to derive the workspace from.
            ScmConnection bare = connection(null, null, null);

            assertThatThrownBy(() -> builder.build(
                    ScmOperationRequest.of(ScmOperationCode.LIST_REPOSITORIES).page(1, 20),
                    configuration,
                    operation(document, ScmOperationCode.LIST_REPOSITORIES),
                    BITBUCKET_BASE, "token",
                    connectionParameters.resolve(configuration, bare)))
                    .isInstanceOf(ScmException.class)
                    .satisfies(thrown -> assertThat(((ScmException) thrown).getErrorCode())
                            .isEqualTo(ScmErrorCode.SCM_OPERATION_PARAMETER_MISSING))
                    // Naming the parameter is the point: the previous failure mode was an opaque
                    // provider 404 that said nothing about what was missing.
                    .hasMessageContaining("workspace");
        }

        @Test
        @DisplayName("encodes the workspace as a single path segment")
        void encodesWorkspace() throws Exception {
            String uri = uriFor("bitbucket.json", ScmOperationCode.LIST_REPOSITORIES,
                    ScmOperationRequest.of(ScmOperationCode.LIST_REPOSITORIES).page(1, 20),
                    connection("{uuid}", "{02ad5881-37c9-459b-8789-6398aacf66ab}", null),
                    BITBUCKET_BASE);

            // Bitbucket's own user payload reports a UUID-in-braces as the repository owner, and braces
            // are not valid in a URL path unencoded.
            assertThat(uri).startsWith(
                    BITBUCKET_BASE + "/repositories/%7B02ad5881-37c9-459b-8789-6398aacf66ab%7D?");
        }
    }

    /* ===================================================================== *
     * Bitbucket - the operations that were already correct
     * ===================================================================== */

    @Nested
    @DisplayName("Bitbucket repository-scoped operations")
    class BitbucketRepositoryScoped {

        private final ScmConnection connection =
                connection("{uuid}", "acme-workspace", null);

        @Test
        @DisplayName("GET_REPOSITORY addresses owner and repo from the caller")
        void getRepository() throws Exception {
            String uri = uriFor("bitbucket.json", ScmOperationCode.GET_REPOSITORY,
                    ScmOperationRequest.of(ScmOperationCode.GET_REPOSITORY)
                            .parameter("owner", "acme").parameter("repo", "api"),
                    connection, BITBUCKET_BASE);

            // Unaffected by the workspace default: these operations are addressed explicitly, which is
            // why repository detail and pull requests kept working while listing did not.
            assertThat(uri).isEqualTo(BITBUCKET_BASE + "/repositories/acme/api");
        }

        @Test
        @DisplayName("LIST_PULL_REQUESTS keeps its canonical state translation")
        void listPullRequests() throws Exception {
            String uri = uriFor("bitbucket.json", ScmOperationCode.LIST_PULL_REQUESTS,
                    ScmOperationRequest.of(ScmOperationCode.LIST_PULL_REQUESTS)
                            .parameter("owner", "acme").parameter("repo", "api")
                            .parameter("state", "ALL").page(1, 20),
                    connection, BITBUCKET_BASE);

            assertThat(uri).startsWith(BITBUCKET_BASE + "/repositories/acme/api/pullrequests?");
            assertThat(uri).contains("state=OPEN").contains("state=MERGED").contains("state=DECLINED");
        }

        @Test
        @DisplayName("GET_PULL_REQUEST_FILES uses diffstat")
        void pullRequestFiles() throws Exception {
            String uri = uriFor("bitbucket.json", ScmOperationCode.GET_PULL_REQUEST_FILES,
                    ScmOperationRequest.of(ScmOperationCode.GET_PULL_REQUEST_FILES)
                            .parameter("owner", "acme").parameter("repo", "api")
                            .parameter("pullRequestNumber", 12).page(1, 20),
                    connection, BITBUCKET_BASE);

            assertThat(uri).startsWith(
                    BITBUCKET_BASE + "/repositories/acme/api/pullrequests/12/diffstat?");
        }

        @Test
        @DisplayName("GET_PULL_REQUEST_DIFF uses the diff endpoint")
        void pullRequestDiff() throws Exception {
            String uri = uriFor("bitbucket.json", ScmOperationCode.GET_PULL_REQUEST_DIFF,
                    ScmOperationRequest.of(ScmOperationCode.GET_PULL_REQUEST_DIFF)
                            .parameter("owner", "acme").parameter("repo", "api")
                            .parameter("pullRequestNumber", 12),
                    connection, BITBUCKET_BASE);

            assertThat(uri).isEqualTo(BITBUCKET_BASE + "/repositories/acme/api/pullrequests/12/diff");
        }

        @Test
        @DisplayName("GET_CURRENT_ACCOUNT needs no connection")
        void currentAccount() throws Exception {
            // The call that discovers the account name the workspace default comes from, so by
            // definition it runs before any connection exists.
            String uri = uriFor("bitbucket.json", ScmOperationCode.GET_CURRENT_ACCOUNT,
                    ScmOperationRequest.of(ScmOperationCode.GET_CURRENT_ACCOUNT),
                    null, BITBUCKET_BASE);

            assertThat(uri).isEqualTo(BITBUCKET_BASE + "/user");
        }
    }

    /* ===================================================================== *
     * GitHub - must be untouched by the fix
     * ===================================================================== */

    @Nested
    @DisplayName("GitHub operations are unaffected")
    class GitHubUnaffected {

        private final ScmConnection connection = connection("217873729", "octocat", null);

        @Test
        @DisplayName("LIST_REPOSITORIES still uses the credential-scoped endpoint")
        void listRepositories() throws Exception {
            String uri = uriFor("github.json", ScmOperationCode.LIST_REPOSITORIES,
                    ScmOperationRequest.of(ScmOperationCode.LIST_REPOSITORIES).page(1, 20),
                    connection, GITHUB_BASE);

            // GitHub needs no owner scope - the credential identifies whose repositories to return -
            // so it declares no connectionParameters and nothing is injected into its path.
            assertThat(uri).startsWith(GITHUB_BASE + "/user/repos?");
            assertThat(uri).contains("page=1").contains("per_page=20")
                    .contains("affiliation=owner,collaborator,organization_member");
            assertThat(uri).doesNotContain("octocat");
        }

        @Test
        @DisplayName("declares no connection parameters at all")
        void declaresNoConnectionParameters() throws Exception {
            ProviderConfiguration configuration = configuration(seed("github.json"));

            assertThat(configuration.connectionParameterTemplates()).isEmpty();
            assertThat(connectionParameters.resolve(configuration, connection)).isEmpty();
        }

        @Test
        @DisplayName("GET_REPOSITORY and the pull-request operations are unchanged")
        void repositoryScopedOperations() throws Exception {
            assertThat(uriFor("github.json", ScmOperationCode.GET_REPOSITORY,
                    ScmOperationRequest.of(ScmOperationCode.GET_REPOSITORY)
                            .parameter("owner", "acme").parameter("repo", "api"),
                    connection, GITHUB_BASE))
                    .isEqualTo(GITHUB_BASE + "/repos/acme/api");

            assertThat(uriFor("github.json", ScmOperationCode.GET_PULL_REQUEST_FILES,
                    ScmOperationRequest.of(ScmOperationCode.GET_PULL_REQUEST_FILES)
                            .parameter("owner", "acme").parameter("repo", "api")
                            .parameter("pullRequestNumber", 12).page(1, 20),
                    connection, GITHUB_BASE))
                    .startsWith(GITHUB_BASE + "/repos/acme/api/pulls/12/files?");

            assertThat(uriFor("github.json", ScmOperationCode.GET_PULL_REQUEST_DIFF,
                    ScmOperationRequest.of(ScmOperationCode.GET_PULL_REQUEST_DIFF)
                            .parameter("owner", "acme").parameter("repo", "api")
                            .parameter("pullRequestNumber", 12),
                    connection, GITHUB_BASE))
                    .isEqualTo(GITHUB_BASE + "/repos/acme/api/pulls/12");
        }

        @Test
        @DisplayName("LIST_PULL_REQUESTS translates the canonical state to GitHub's spelling")
        void listPullRequests() throws Exception {
            String uri = uriFor("github.json", ScmOperationCode.LIST_PULL_REQUESTS,
                    ScmOperationRequest.of(ScmOperationCode.LIST_PULL_REQUESTS)
                            .parameter("owner", "acme").parameter("repo", "api")
                            .parameter("state", "ALL").page(1, 20),
                    connection, GITHUB_BASE);

            assertThat(uri).contains("state=all");
        }
    }

    /* ===================================================================== *
     * Cross-provider invariants
     *
     * Nested like the rest rather than left as bare methods on the outer class:
     * Surefire's class-name filter reports zero tests for an outer class whose
     * members are @Nested, so methods left out here are silently never run - the
     * worst possible outcome for an invariant guarding a production outage.
     * ===================================================================== */

    @Nested
    @DisplayName("Seed invariants")
    class SeedInvariants {

    @Test
    @DisplayName("no seeded operation targets a removed Bitbucket cross-workspace endpoint")
    void noSeededOperationUsesRemovedEndpoints() throws Exception {
        // Atlassian's end-of-life list. Asserted against the raw templates so a future edit that
        // reintroduces one fails here rather than in production, where the symptom is a bare 404.
        var removed = java.util.List.of(
                "/repositories",
                "/workspaces",
                "/user/permissions/repositories",
                "/user/permissions/workspaces");

        ScmProviderSeedDocument document = seed("bitbucket.json");

        for (var operation : document.operationsOrEmpty()) {
            String template = operation.endpointTemplate();
            assertThat(removed)
                    .as("operation %s targets removed cross-workspace endpoint %s",
                            operation.operationCode(), template)
                    .doesNotContain(template);
        }
    }

    @Test
    @DisplayName("every seeded provider declares a base URL the engine can use")
    void everyProviderHasABaseUrl() throws Exception {
        for (String file : java.util.List.of("github.json", "bitbucket.json")) {
            ProviderConfiguration configuration = configuration(seed(file));
            assertThat(configuration.apiOrEmpty().baseUrl())
                    .as("%s api.baseUrl", file)
                    .isNotBlank()
                    .startsWith("https://");
        }
    }
    }
}
