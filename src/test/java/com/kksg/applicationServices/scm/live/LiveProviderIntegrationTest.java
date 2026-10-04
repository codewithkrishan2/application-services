package com.kksg.applicationServices.scm.live;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kksg.applicationServices.scm.common.http.ScmHttpClientConfig;
import com.kksg.applicationServices.scm.common.http.ScmHttpExecutor;
import com.kksg.applicationServices.scm.common.http.ScmHttpProperties;
import com.kksg.applicationServices.scm.common.model.ScmOperationCode;
import com.kksg.applicationServices.scm.common.model.ScmOperationRequest;
import com.kksg.applicationServices.scm.connection.entity.ScmConnection;
import com.kksg.applicationServices.scm.operation.engine.RequestConfiguration;
import com.kksg.applicationServices.scm.operation.engine.ResponseMapping;
import com.kksg.applicationServices.scm.operation.engine.ScmHttpRequest;
import com.kksg.applicationServices.scm.operation.engine.ScmHttpResponse;
import com.kksg.applicationServices.scm.operation.engine.ScmRequestBuilder;
import com.kksg.applicationServices.scm.operation.entity.ScmProviderOperation;
import com.kksg.applicationServices.scm.operation.service.ResolvedOperation;
import com.kksg.applicationServices.scm.provider.config.ConnectionParameterResolver;
import com.kksg.applicationServices.scm.provider.config.ProviderConfiguration;
import com.kksg.applicationServices.scm.seed.ScmProviderSeedDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in tests that talk to the real providers.
 *
 * <p><b>Disabled by default and skipped silently.</b> They need live credentials, they make outbound
 * network calls, and they can fail for reasons that have nothing to do with this codebase - a provider
 * outage, an expired token, a rate limit. None of that belongs in a build gate, so nothing here runs
 * unless it is asked for explicitly.
 *
 * <h2>Running them</h2>
 * <pre>{@code
 * # Bitbucket only
 * CODEREV_LIVE_SCM=1 \
 * CODEREV_LIVE_BITBUCKET_TOKEN=<access token> \
 * CODEREV_LIVE_BITBUCKET_WORKSPACE=<workspace slug> \
 *   ./mvnw test -Dtest='LiveProviderIntegrationTest'
 *
 * # GitHub only
 * CODEREV_LIVE_SCM=1 CODEREV_LIVE_GITHUB_TOKEN=<access token> \
 *   ./mvnw test -Dtest='LiveProviderIntegrationTest'
 * }</pre>
 *
 * <p>Each provider's group is additionally gated on its own token variable, so supplying one set of
 * credentials runs that provider's tests and skips the other rather than failing it. A read-only token
 * is sufficient; nothing here writes to a provider.
 *
 * <p><b>Credentials are read from the environment only.</b> No token, workspace or account name appears
 * in this file or in any committed fixture, and nothing is written to disk. The assertions are about
 * HTTP status and response shape, never about a particular user's repositories, so they pass for any
 * account - which is also what keeps them from quietly depending on one developer's setup.
 *
 * <h2>What they are actually for</h2>
 * <p>They exercise the production path end to end: the real {@code classpath:scm/seed/*.json}
 * configuration, the real {@link ScmRequestBuilder}, and the real {@link ScmHttpExecutor} with the same
 * {@code RestTemplate} the application builds. Unit tests prove the engine resolves whatever template it
 * is given; only these can prove the template still points at an endpoint that exists.
 *
 * <p>That distinction is not hypothetical. Bitbucket repository listing broke for every user because the
 * seeded operation targeted {@code GET /2.0/repositories}, the cross-workspace endpoint Atlassian removed
 * on 14 April 2026. Every offline test passed throughout. {@link Bitbucket#removedCrossWorkspaceEndpointIsStillGone()}
 * is the sentinel for that specific class of failure.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "CODEREV_LIVE_SCM", matches = "(?i)1|true|yes",
        disabledReason = "Live provider tests are opt-in; set CODEREV_LIVE_SCM=1 to enable")
class LiveProviderIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ScmRequestBuilder requestBuilder = new ScmRequestBuilder();
    private final ConnectionParameterResolver connectionParameters = new ConnectionParameterResolver();
    private final ScmHttpExecutor executor = liveExecutor();

    /**
     * The application's own HTTP stack, not a fresh {@code RestTemplate}.
     *
     * <p>Built through {@link ScmHttpClientConfig} on purpose: its non-throwing error handler and its
     * refusal to let {@code HttpURLConnection} follow redirects are both part of the behaviour under
     * test. A plain {@code RestTemplate} would follow a 302 itself and hide the redirect handling that
     * Bitbucket's diff endpoints depend on.
     */
    private static ScmHttpExecutor liveExecutor() {
        ScmHttpProperties properties = new ScmHttpProperties();
        return new ScmHttpExecutor(
                new ScmHttpClientConfig().scmRestTemplate(properties), MAPPER, properties);
    }

    /* --------------------------------------------------------------------- *
     * Seed loading - identical to SeededOperationRequestTest, so the live and
     * offline suites cannot drift onto different configuration.
     * --------------------------------------------------------------------- */

    private ScmProviderSeedDocument seed(String providerFile) throws Exception {
        try (InputStream input = new ClassPathResource("scm/seed/" + providerFile).getInputStream()) {
            return MAPPER.readValue(input, ScmProviderSeedDocument.class);
        }
    }

    private ProviderConfiguration configuration(ScmProviderSeedDocument document) {
        return MAPPER.convertValue(document.configuration(), ProviderConfiguration.class);
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

        return new ResolvedOperation(entity,
                seed.requestConfiguration() == null
                        ? RequestConfiguration.empty()
                        : MAPPER.convertValue(seed.requestConfiguration(), RequestConfiguration.class),
                seed.responseMapping() == null
                        ? ResponseMapping.raw()
                        : MAPPER.convertValue(seed.responseMapping(), ResponseMapping.class));
    }

    /** A connection shaped the way the OAuth connect flow leaves one. */
    private ScmConnection connection(String accountName, Map<String, Object> metadata) {
        ScmConnection connection = new ScmConnection();
        connection.setId(1);
        connection.setExternalAccountName(accountName);
        connection.setMetadata(metadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata));
        return connection;
    }

    /** Builds a seeded operation's request and sends it for real. */
    private ScmHttpResponse call(String providerFile,
                                 ScmOperationCode code,
                                 ScmOperationRequest request,
                                 ScmConnection connection,
                                 String accessToken) throws Exception {
        ScmProviderSeedDocument document = seed(providerFile);
        ProviderConfiguration configuration = configuration(document);

        ScmRequestBuilder.BuiltRequest built = requestBuilder.build(
                request, configuration, operation(document, code),
                configuration.apiOrEmpty().baseUrl(), accessToken,
                connectionParameters.resolve(configuration, connection));

        return executor.execute(built.request());
    }

    private ScmHttpResponse rawGet(String url, String accessToken) {
        return executor.execute(ScmHttpRequest.of(HttpMethod.GET, url,
                Map.of(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken,
                        HttpHeaders.ACCEPT, "application/json"),
                null, false));
    }

    private ScmHttpResponse anonymousGet(String url) {
        return executor.execute(ScmHttpRequest.of(HttpMethod.GET, url,
                Map.of(HttpHeaders.ACCEPT, "application/json"), null, false));
    }

    @Nested
    @DisplayName("Bitbucket")
    @EnabledIfEnvironmentVariable(named = "CODEREV_LIVE_BITBUCKET_TOKEN", matches = ".+",
            disabledReason = "Set CODEREV_LIVE_BITBUCKET_TOKEN to run the Bitbucket live tests")
    class Bitbucket {

        private static final String SEED_FILE = "bitbucket.json";
        private static final String BASE = "https://api.bitbucket.org/2.0";

        private String token() {
            return System.getenv("CODEREV_LIVE_BITBUCKET_TOKEN");
        }

        /**
         * @return the workspace slug to test against. Required, because Bitbucket no longer has any
         *         endpoint that discovers it - see {@link #workspaceCannotBeDiscovered()}.
         */
        private String workspace() {
            String workspace = System.getenv("CODEREV_LIVE_BITBUCKET_WORKSPACE");
            assertThat(workspace)
                    .as("CODEREV_LIVE_BITBUCKET_WORKSPACE must name a workspace the token can read")
                    .isNotBlank();
            return workspace;
        }

        @Test
        @DisplayName("the token is valid, so a later failure is not a credential problem")
        void tokenIsValid() throws Exception {
            // Runs first in spirit: if this fails, nothing else in this group means anything.
            ScmHttpResponse response = call(SEED_FILE, ScmOperationCode.GET_CURRENT_ACCOUNT,
                    ScmOperationRequest.of(ScmOperationCode.GET_CURRENT_ACCOUNT),
                    connection(null, null), token());

            assertThat(response.statusCode())
                    .as("GET_CURRENT_ACCOUNT should succeed with a valid token")
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("the seeded repository listing succeeds against a real workspace")
        void seededListingSucceeds() throws Exception {
            // The regression test for the reported bug, run against the provider rather than a fixture.
            ScmHttpResponse response = call(SEED_FILE, ScmOperationCode.LIST_REPOSITORIES,
                    ScmOperationRequest.of(ScmOperationCode.LIST_REPOSITORIES),
                    connection(null, Map.of("workspace", workspace())), token());

            assertThat(response.statusCode()).isEqualTo(200);
            // Shape, not content: asserting a particular repository would tie the suite to one account.
            assertThat(response.bodyJson()).isNotNull();
            assertThat(response.bodyJson().has("values")).isTrue();
        }

        @Test
        @DisplayName("the removed cross-workspace endpoint is still gone")
        void removedCrossWorkspaceEndpointIsStillGone() {
            // The sentinel. If Atlassian ever restores this route, the simpler account-wide listing
            // becomes possible again and the connectionParameters indirection could be reconsidered -
            // and this failing test is how we would find out.
            ScmHttpResponse response = rawGet(BASE + "/repositories?pagelen=1", token());

            assertThat(response.statusCode())
                    .as("GET /2.0/repositories was end-of-lifed on 2026-04-14")
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("that 404 is route-level, not the provider masking an auth failure")
        void removalIsRouteLevelNotAuthMasking() {
            // The distinction that made the original diagnosis trustworthy. An identical 404 without a
            // credential means the route does not exist; Bitbucket answers 401 for routes that do exist
            // but need authentication, so it is not using 404 to hide authorisation failures.
            ScmHttpResponse anonymous = anonymousGet(BASE + "/repositories?pagelen=1");
            ScmHttpResponse authenticatedButProtected =
                    anonymousGet(BASE + "/workspaces/" + workspace() + "/permissions");

            assertThat(anonymous.statusCode())
                    .as("the removed route 404s even unauthenticated")
                    .isEqualTo(404);
            assertThat(authenticatedButProtected.statusCode())
                    .as("an existing but protected route answers 401, not 404")
                    .isEqualTo(401);
        }

        @Test
        @DisplayName("the workspace-scoped replacement route exists")
        void workspaceScopedRouteExists() {
            ScmHttpResponse response = rawGet(BASE + "/repositories/" + workspace() + "?pagelen=1", token());

            assertThat(response.statusCode()).isEqualTo(200);
        }

        @Test
        @DisplayName("no endpoint discovers the workspace, which is why configuration supplies it")
        void workspaceCannotBeDiscovered() {
            // Documents the constraint behind the design rather than asserting a nicety. Every endpoint
            // that used to answer "which workspaces can this token see" was removed in the same
            // end-of-life, and Atlassian has said there will be no cross-workspace replacement. That is
            // what rules out a LIST_WORKSPACES discovery operation and leaves a declared
            // connectionParameter as the only honest option.
            assertThat(rawGet(BASE + "/workspaces", token()).statusCode()).isEqualTo(404);
            assertThat(rawGet(BASE + "/user/permissions/workspaces", token()).statusCode()).isEqualTo(404);
        }

        @Test
        @DisplayName("a pull request diff is reachable, which requires following a same-origin redirect")
        void diffRedirectIsFollowed() throws Exception {
            // Bitbucket answers 302 for pullrequests/{id}/diff, pointing at a commit-range URL on the
            // same host. Covered offline too, but only this proves the provider still behaves that way.
            ScmHttpResponse listing = call(SEED_FILE, ScmOperationCode.LIST_REPOSITORIES,
                    ScmOperationRequest.of(ScmOperationCode.LIST_REPOSITORIES),
                    connection(null, Map.of("workspace", workspace())), token());

            if (listing.statusCode() != 200 || !listing.bodyJson().path("values").elements().hasNext()) {
                // Nothing to diff against. Skipped by assertion rather than failed: an empty workspace
                // is a legitimate account state, not a defect.
                assertThat(listing.statusCode()).isEqualTo(200);
                return;
            }

            String fullName = listing.bodyJson().path("values").get(0).path("full_name").asText();
            ScmHttpResponse pullRequests =
                    rawGet(BASE + "/repositories/" + fullName + "/pullrequests?state=MERGED&pagelen=1",
                            token());
            if (pullRequests.statusCode() != 200
                    || !pullRequests.bodyJson().path("values").elements().hasNext()) {
                return;
            }

            int number = pullRequests.bodyJson().path("values").get(0).path("id").asInt();
            ScmHttpResponse diff = rawGet(
                    BASE + "/repositories/" + fullName + "/pullrequests/" + number + "/diff", token());

            assertThat(diff.statusCode())
                    .as("a 302 here means the executor stopped following same-origin redirects")
                    .isEqualTo(200);
        }
    }

    @Nested
    @DisplayName("GitHub")
    @EnabledIfEnvironmentVariable(named = "CODEREV_LIVE_GITHUB_TOKEN", matches = ".+",
            disabledReason = "Set CODEREV_LIVE_GITHUB_TOKEN to run the GitHub live tests")
    class GitHub {

        private static final String SEED_FILE = "github.json";

        private String token() {
            return System.getenv("CODEREV_LIVE_GITHUB_TOKEN");
        }

        @Test
        @DisplayName("the token is valid")
        void tokenIsValid() throws Exception {
            ScmHttpResponse response = call(SEED_FILE, ScmOperationCode.GET_CURRENT_ACCOUNT,
                    ScmOperationRequest.of(ScmOperationCode.GET_CURRENT_ACCOUNT),
                    connection(null, null), token());

            assertThat(response.statusCode()).isEqualTo(200);
        }

        @Test
        @DisplayName("the seeded repository listing succeeds with no connection parameters at all")
        void seededListingSucceeds() throws Exception {
            // GitHub declares no connectionParameters, and this is where that is proven rather than
            // assumed: the same engine call that needs a workspace for Bitbucket needs nothing here.
            ScmHttpResponse response = call(SEED_FILE, ScmOperationCode.LIST_REPOSITORIES,
                    ScmOperationRequest.of(ScmOperationCode.LIST_REPOSITORIES),
                    connection(null, null), token());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.bodyJson()).isNotNull();
            assertThat(response.bodyJson().isArray()).isTrue();
        }

        @Test
        @DisplayName("an unknown repository answers 404, which the engine maps to a resource error")
        void unknownRepositoryIs404() {
            // Confirms the provider still signals absence with 404 rather than, say, 403 - which is what
            // the engine's status classification is built on.
            ScmHttpResponse response = rawGet(
                    "https://api.github.com/repos/coderev-does-not-exist/nor-does-this", token());

            assertThat(response.statusCode()).isEqualTo(404);
        }

        @Test
        @DisplayName("rate-limit headers are present, which the 403 classification depends on")
        void rateLimitHeadersArePresent() throws Exception {
            // The engine tells a rate-limit 403 from an authorisation 403 by reading
            // x-ratelimit-remaining. If GitHub stopped sending it, that distinction would silently
            // degrade to "every 403 is an authorisation failure".
            ScmHttpResponse response = call(SEED_FILE, ScmOperationCode.GET_CURRENT_ACCOUNT,
                    ScmOperationRequest.of(ScmOperationCode.GET_CURRENT_ACCOUNT),
                    connection(null, null), token());

            assertThat(response.header("x-ratelimit-remaining")).isNotNull();
        }
    }
}
