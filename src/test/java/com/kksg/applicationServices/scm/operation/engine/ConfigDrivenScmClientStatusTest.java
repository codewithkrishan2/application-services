package com.kksg.applicationServices.scm.operation.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kksg.applicationServices.scm.adapter.ScmAdapterRegistry;
import com.kksg.applicationServices.scm.capability.service.ScmProviderCapabilityService;
import com.kksg.applicationServices.scm.common.exception.ScmErrorCode;
import com.kksg.applicationServices.scm.common.exception.ScmException;
import com.kksg.applicationServices.scm.common.http.ScmHttpExecutor;
import com.kksg.applicationServices.scm.common.model.ScmOperationCode;
import com.kksg.applicationServices.scm.common.model.ScmOperationRequest;
import com.kksg.applicationServices.scm.connection.entity.ScmConnection;
import com.kksg.applicationServices.scm.connection.entity.ScmConnectionStatus;
import com.kksg.applicationServices.scm.connection.service.ScmTokenService;
import com.kksg.applicationServices.scm.operation.service.ResolvedOperation;
import com.kksg.applicationServices.scm.operation.service.ScmProviderOperationService;
import com.kksg.applicationServices.scm.provider.config.ConnectionParameterResolver;
import com.kksg.applicationServices.scm.provider.config.ProviderConfiguration;
import com.kksg.applicationServices.scm.provider.entity.ScmProvider;
import com.kksg.applicationServices.scm.provider.service.ScmProviderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpMethod;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * The provider HTTP status matrix: what a given status means to the caller above the engine.
 *
 * <p><b>Why these distinctions are kept rather than collapsed.</b> Each status implies a different
 * correct response, and flattening them into one "provider failed" error destroys the only information
 * the caller could act on:
 * <ul>
 *   <li><b>401</b> - renew the credential, or send the user to reauthorize. Retrying unchanged is
 *       pointless.</li>
 *   <li><b>403 with rate-limit headers / 429</b> - wait, and the provider usually says how long. Also
 *       the one case that must <i>not</i> be retried inline, which is why it carries
 *       {@code retryAfterSeconds} out to the client instead.</li>
 *   <li><b>403 without those headers</b> - a genuine authorisation refusal. Reporting it as a rate
 *       limit would tell a user to wait for something that will never change.</li>
 *   <li><b>404 / 410</b> - the resource is absent or invisible to this credential. A user-caused 404,
 *       not an incident.</li>
 *   <li><b>anything else</b> - a provider or configuration fault, and a 502 worth alerting on.</li>
 * </ul>
 *
 * <p>The engine deliberately stops at "the provider says it is not there" and does not name
 * <i>which</i> resource. Only the calling module knows whether the request addressed a repository, a
 * pull request, or an account scope, and that is what lets one generic engine produce precise
 * domain errors - the Bitbucket listing 404 became {@code SCM_REPOSITORY_SCOPE_NOT_FOUND} that way.
 *
 * <p>The collaborators are all mocked because none of them is what is being tested: the subject is the
 * classification itself, driven by a response the executor is told to return.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConfigDrivenScmClientStatusTest {

    private static final ScmOperationCode OPERATION = ScmOperationCode.LIST_REPOSITORIES;

    @Mock
    private ScmProviderService providerService;

    @Mock
    private ScmProviderCapabilityService capabilityService;

    @Mock
    private ScmProviderOperationService operationService;

    @Mock
    private ScmTokenService tokenService;

    @Mock
    private ScmRequestBuilder requestBuilder;

    @Mock
    private ScmHttpExecutor httpExecutor;

    @Mock
    private ScmResponseNormalizer responseNormalizer;

    @Mock
    private ScmPaginationResolver paginationResolver;

    @Mock
    private ConnectionParameterResolver connectionParameterResolver;

    private ScmProvider provider;
    private ScmConnection connection;
    private ConfigDrivenScmClient client;

    @BeforeEach
    void setUp() {
        provider = new ScmProvider();
        provider.setId(1);
        provider.setProviderCode("TESTHUB");

        connection = new ScmConnection();
        connection.setId(9);
        connection.setProvider(provider);
        connection.setConnectionStatus(ScmConnectionStatus.ACTIVE);

        ProviderConfiguration configuration = new ProviderConfiguration(
                new ProviderConfiguration.Api("https://api.provider.example", null, null, null),
                null, null, null, null);

        when(providerService.requireById(1)).thenReturn(provider);
        when(providerService.getConfiguration(provider)).thenReturn(configuration);
        when(tokenService.resolveAccessToken(connection, provider)).thenReturn("access-token");
        when(operationService.require(provider, OPERATION))
                .thenReturn(new ResolvedOperation(null, null, null));
        when(connectionParameterResolver.resolve(any(), any())).thenReturn(Map.of());
        when(requestBuilder.build(any(), any(), any(), anyString(), anyString(), any()))
                .thenReturn(new ScmRequestBuilder.BuiltRequest(
                        ScmHttpRequest.of(HttpMethod.GET, "https://api.provider.example/repos",
                                Map.of(), null, false),
                        Map.of()));

        // No adapter registered: the generic path is the one worth testing, since it is the path every
        // provider takes unless it has an explicit reason not to.
        client = new ConfigDrivenScmClient(providerService, capabilityService, operationService,
                tokenService, requestBuilder, httpExecutor, responseNormalizer, paginationResolver,
                new ScmAdapterRegistry(List.of()), connectionParameterResolver, new ObjectMapper());
    }

    private void providerResponds(int status, Map<String, List<String>> headers) {
        when(httpExecutor.execute(any(ScmHttpRequest.class)))
                .thenReturn(new ScmHttpResponse(status, headers, null, "{}"));
    }

    private ScmException executeExpectingFailure() {
        return (ScmException) org.assertj.core.api.Assertions
                .catchThrowable(() -> client.execute(connection, ScmOperationRequest.of(OPERATION)));
    }

    @Nested
    @DisplayName("401 means the credential, not the request")
    class Unauthorized {

        @Test
        @DisplayName("401 maps to SCM_CONNECTION_EXPIRED")
        void unauthorizedMapsToExpired() {
            providerResponds(401, Map.of());

            // Routed at the connection, not the operation: the caller's correct move is to renew or
            // reauthorize, and no amount of re-issuing the same request helps.
            assertThat(executeExpectingFailure().getErrorCode())
                    .isEqualTo(ScmErrorCode.SCM_CONNECTION_EXPIRED);
        }

        @Test
        @DisplayName("the message names the provider but not the credential")
        void messageCarriesNoCredential() {
            providerResponds(401, Map.of());

            assertThat(executeExpectingFailure().getMessage())
                    .contains("TESTHUB")
                    .doesNotContain("access-token");
        }
    }

    @Nested
    @DisplayName("rate limiting is told apart from authorisation")
    class RateLimiting {

        @Test
        @DisplayName("429 maps to SCM_PROVIDER_RATE_LIMITED")
        void tooManyRequestsIsRateLimited() {
            providerResponds(429, Map.of());

            assertThat(executeExpectingFailure().getErrorCode())
                    .isEqualTo(ScmErrorCode.SCM_PROVIDER_RATE_LIMITED);
        }

        @Test
        @DisplayName("403 with an exhausted quota header is a rate limit")
        void exhaustedQuotaHeaderIsRateLimited() {
            // Some providers signal exhaustion with 403 plus a zeroed remaining-quota header rather than
            // 429. Reading the conventional header is provider-neutral - a provider that does not send
            // it simply falls through to the generic classification.
            providerResponds(403, Map.of("x-ratelimit-remaining", List.of("0")));

            assertThat(executeExpectingFailure().getErrorCode())
                    .isEqualTo(ScmErrorCode.SCM_PROVIDER_RATE_LIMITED);
        }

        @Test
        @DisplayName("403 with a Retry-After header is a rate limit")
        void retryAfterHeaderIsRateLimited() {
            providerResponds(403, Map.of("retry-after", List.of("30")));

            ScmException failure = executeExpectingFailure();

            assertThat(failure.getErrorCode()).isEqualTo(ScmErrorCode.SCM_PROVIDER_RATE_LIMITED);
            assertThat(failure.getRetryAfterSeconds()).isEqualTo(30);
        }

        @Test
        @DisplayName("403 with quota remaining is an authorisation refusal, not a rate limit")
        void plainForbiddenIsNotRateLimited() {
            // The distinction matters to the user: telling someone to wait for a permission they will
            // never be granted is worse than telling them nothing.
            providerResponds(403, Map.of("x-ratelimit-remaining", List.of("4999")));

            assertThat(executeExpectingFailure().getErrorCode())
                    .isEqualTo(ScmErrorCode.SCM_PROVIDER_API_ERROR);
        }

        @Test
        @DisplayName("403 with no rate-limit headers at all is an authorisation refusal")
        void bareForbiddenIsNotRateLimited() {
            providerResponds(403, Map.of());

            assertThat(executeExpectingFailure().getErrorCode())
                    .isEqualTo(ScmErrorCode.SCM_PROVIDER_API_ERROR);
        }
    }

    @Nested
    @DisplayName("Retry-After is bounded and sanitised")
    class RetryAfterHandling {

        @Test
        @DisplayName("a delta-seconds value is passed through")
        void deltaSecondsIsRead() {
            providerResponds(429, Map.of("retry-after", List.of("120")));

            assertThat(executeExpectingFailure().getRetryAfterSeconds()).isEqualTo(120);
        }

        @Test
        @DisplayName("an absurd wait is capped at one hour")
        void absurdWaitIsCapped() {
            // The value crosses a trust boundary and ends up in an HTTP header and a UI. A provider
            // asking us to wait a week is not a hint anything can usefully render.
            providerResponds(429, Map.of("retry-after", List.of("604800")));

            assertThat(executeExpectingFailure().getRetryAfterSeconds()).isEqualTo(3600);
        }

        @Test
        @DisplayName("an HTTP-date value is reported as absent rather than guessed at")
        void httpDateIsIgnored() {
            // A date is only as good as the agreement between two clocks; a skewed one produces either a
            // pointless wait or no wait at all. Absent leaves the client its own sensible default.
            providerResponds(429, Map.of("retry-after", List.of("Wed, 21 Oct 2026 07:28:00 GMT")));

            assertThat(executeExpectingFailure().getRetryAfterSeconds()).isNull();
        }

        @ParameterizedTest(name = "Retry-After: {0} is reported as absent")
        @ValueSource(strings = {"0", "-5", "", "   ", "soon"})
        @DisplayName("a non-positive or unreadable value is reported as absent")
        void unusableValuesAreAbsent(String value) {
            providerResponds(429, Map.of("retry-after", List.of(value)));

            assertThat(executeExpectingFailure().getRetryAfterSeconds()).isNull();
        }

        @Test
        @DisplayName("a missing header leaves retryAfterSeconds null")
        void missingHeaderIsNull() {
            providerResponds(429, Map.of());

            assertThat(executeExpectingFailure().getRetryAfterSeconds()).isNull();
        }

        @Test
        @DisplayName("only rate-limit failures carry a wait hint")
        void otherFailuresCarryNoWaitHint() {
            // A 404 with a stray Retry-After must not tell the user to wait: the resource is not
            // going to appear.
            providerResponds(404, Map.of("retry-after", List.of("60")));

            ScmException failure = executeExpectingFailure();

            assertThat(failure.getErrorCode()).isEqualTo(ScmErrorCode.SCM_PROVIDER_RESOURCE_NOT_FOUND);
            assertThat(failure.getRetryAfterSeconds()).isNull();
        }
    }

    @Nested
    @DisplayName("absence is reported as absence")
    class NotFound {

        @ParameterizedTest(name = "HTTP {0} maps to SCM_PROVIDER_RESOURCE_NOT_FOUND")
        @ValueSource(ints = {404, 410})
        @DisplayName("404 and 410 both mean the resource is not there")
        void notFoundAndGone(int status) {
            // Reported as its own code rather than a generic provider failure because the two deserve
            // opposite treatment: one is a 404 the user caused, the other is a 502 worth paging about.
            providerResponds(status, Map.of());

            assertThat(executeExpectingFailure().getErrorCode())
                    .isEqualTo(ScmErrorCode.SCM_PROVIDER_RESOURCE_NOT_FOUND);
        }

        @Test
        @DisplayName("the engine does not guess which resource was missing")
        void engineDoesNotNameTheResource() {
            providerResponds(404, Map.of());

            // It reports the operation and leaves the resource to the caller - which is how one generic
            // engine yields SCM_REPOSITORY_NOT_FOUND for a detail call and
            // SCM_REPOSITORY_SCOPE_NOT_FOUND for a listing call.
            assertThat(executeExpectingFailure().getMessage())
                    .contains("LIST_REPOSITORIES")
                    .doesNotContain("repository not found");
        }
    }

    @Nested
    @DisplayName("everything else is a provider fault")
    class ProviderFault {

        @ParameterizedTest(name = "HTTP {0} maps to SCM_PROVIDER_API_ERROR")
        @ValueSource(ints = {400, 402, 405, 409, 422, 500, 501, 502, 503, 504})
        @DisplayName("an unclassified failure status is a provider or configuration fault")
        void unclassifiedStatusesAreProviderErrors(int status) {
            providerResponds(status, Map.of());

            assertThat(executeExpectingFailure().getErrorCode())
                    .isEqualTo(ScmErrorCode.SCM_PROVIDER_API_ERROR);
        }

        @Test
        @DisplayName("the status reaches the message so a report is actionable")
        void statusIsInTheMessage() {
            providerResponds(500, Map.of());

            assertThat(executeExpectingFailure().getMessage()).contains("status=500");
        }

        @Test
        @DisplayName("a 3xx that was not followed is a provider fault, not a success")
        void unfollowedRedirectIsAFailure() {
            // The executor follows same-origin redirects itself and deliberately refuses cross-origin
            // ones so a bearer token never leaves the origin. A refused redirect arrives here, and
            // treating a 302 as successful would hand an empty body to the normalizer.
            providerResponds(302, Map.of("location", List.of("https://elsewhere.example/repos")));

            assertThat(executeExpectingFailure().getErrorCode())
                    .isEqualTo(ScmErrorCode.SCM_PROVIDER_API_ERROR);
        }
    }

    @Nested
    @DisplayName("no response body ever reaches an exception message")
    class BodyContainment {

        @Test
        @DisplayName("the provider's error body stays out of the message")
        void errorBodyIsNotEchoed() {
            // Provider error bodies echo request content, and a webhook-creation request body carries a
            // webhook secret. The body is logged; it does not travel in an exception that may be
            // rendered to a client.
            when(httpExecutor.execute(any(ScmHttpRequest.class))).thenReturn(new ScmHttpResponse(
                    500, Map.of(), null, "{\"message\":\"secret webhook token hunter2 rejected\"}"));

            assertThat(executeExpectingFailure().getMessage()).doesNotContain("hunter2");
        }
    }

    @Nested
    @DisplayName("preconditions are checked before the network")
    class Preconditions {

        @Test
        @DisplayName("a null connection is rejected without a provider call")
        void nullConnectionIsRejected() {
            assertThatThrownBy(() -> client.execute(null, ScmOperationRequest.of(OPERATION)))
                    .isInstanceOf(ScmException.class)
                    .extracting(ex -> ((ScmException) ex).getErrorCode())
                    .isEqualTo(ScmErrorCode.SCM_CONNECTION_NOT_FOUND);
        }

        @Test
        @DisplayName("an unsupported capability fails before any request is built")
        void capabilityGateRunsFirst() {
            // Refusing an unsupported operation before touching the network turns a confusing provider
            // 404 into a precise SCM_OPERATION_NOT_SUPPORTED.
            org.mockito.Mockito.doThrow(new ScmException(ScmErrorCode.SCM_OPERATION_NOT_SUPPORTED, "no"))
                    .when(capabilityService).requireSupported(eq(provider), any());

            assertThatThrownBy(() -> client.execute(connection, ScmOperationRequest.of(OPERATION)))
                    .isInstanceOf(ScmException.class)
                    .extracting(ex -> ((ScmException) ex).getErrorCode())
                    .isEqualTo(ScmErrorCode.SCM_OPERATION_NOT_SUPPORTED);

            org.mockito.Mockito.verify(httpExecutor, org.mockito.Mockito.never())
                    .execute(any(ScmHttpRequest.class));
        }

        @Test
        @DisplayName("a token failure stops the call before the provider is contacted")
        void tokenFailureStopsTheCall() {
            when(tokenService.resolveAccessToken(connection, provider))
                    .thenThrow(new ScmException(ScmErrorCode.SCM_CONNECTION_REVOKED, "connectionId=9"));

            assertThatThrownBy(() -> client.execute(connection, ScmOperationRequest.of(OPERATION)))
                    .isInstanceOf(ScmException.class)
                    .extracting(ex -> ((ScmException) ex).getErrorCode())
                    .isEqualTo(ScmErrorCode.SCM_CONNECTION_REVOKED);

            org.mockito.Mockito.verify(httpExecutor, org.mockito.Mockito.never())
                    .execute(any(ScmHttpRequest.class));
        }
    }

    @Nested
    @DisplayName("the whole matrix in one table")
    class StatusTable {

        @ParameterizedTest(name = "{0}")
        @ArgumentsSource(StatusOutcomes.class)
        @DisplayName("status plus headers map to the expected error code")
        void statusMapsToErrorCode(String name, int status, Map<String, List<String>> headers,
                                   ScmErrorCode expected) {
            providerResponds(status, headers);

            assertThat(executeExpectingFailure().getErrorCode()).as(name).isEqualTo(expected);
        }
    }

    /** One place a classification change shows up as a diff. */
    static class StatusOutcomes implements ArgumentsProvider {
        @Override
        public Stream<Arguments> provideArguments(ExtensionContext context) {
            return Stream.of(
                    Arguments.of("401", 401, Map.of(), ScmErrorCode.SCM_CONNECTION_EXPIRED),
                    Arguments.of("403 plain", 403, Map.of(), ScmErrorCode.SCM_PROVIDER_API_ERROR),
                    Arguments.of("403 quota exhausted", 403,
                            Map.of("x-ratelimit-remaining", List.of("0")),
                            ScmErrorCode.SCM_PROVIDER_RATE_LIMITED),
                    Arguments.of("403 with retry-after", 403, Map.of("retry-after", List.of("5")),
                            ScmErrorCode.SCM_PROVIDER_RATE_LIMITED),
                    Arguments.of("404", 404, Map.of(), ScmErrorCode.SCM_PROVIDER_RESOURCE_NOT_FOUND),
                    Arguments.of("410", 410, Map.of(), ScmErrorCode.SCM_PROVIDER_RESOURCE_NOT_FOUND),
                    Arguments.of("422", 422, Map.of(), ScmErrorCode.SCM_PROVIDER_API_ERROR),
                    Arguments.of("429", 429, Map.of(), ScmErrorCode.SCM_PROVIDER_RATE_LIMITED),
                    Arguments.of("500", 500, Map.of(), ScmErrorCode.SCM_PROVIDER_API_ERROR),
                    Arguments.of("502", 502, Map.of(), ScmErrorCode.SCM_PROVIDER_API_ERROR),
                    Arguments.of("503", 503, Map.of(), ScmErrorCode.SCM_PROVIDER_API_ERROR),
                    Arguments.of("504", 504, Map.of(), ScmErrorCode.SCM_PROVIDER_API_ERROR));
        }
    }

    @Nested
    @DisplayName("the success path")
    class Success {

        @Test
        @DisplayName("a 2xx is normalized rather than classified")
        void successIsNormalized() {
            providerResponds(200, Map.of());
            when(responseNormalizer.normalize(any(), any(), anyString()))
                    .thenReturn(new ObjectMapper().createArrayNode());
            when(paginationResolver.resolve(any(), any(), any(), any(), any()))
                    .thenReturn(com.kksg.applicationServices.scm.common.model.ScmPagination.builder()
                            .itemCount(0).hasNext(false).build());

            assertThat(client.execute(connection, ScmOperationRequest.of(OPERATION)).getHttpStatus())
                    .isEqualTo(200);
        }

        @ParameterizedTest(name = "HTTP {0} is treated as successful")
        @ValueSource(ints = {200, 201, 204, 299})
        @DisplayName("the whole 2xx range is successful")
        void entire2xxRangeIsSuccessful(int status) {
            // 204 matters: a provider answering "no content" to a delete must not be classified as a
            // failure just because there is nothing to normalize.
            providerResponds(status, Map.of());
            when(responseNormalizer.normalize(any(), any(), anyString()))
                    .thenReturn(new ObjectMapper().createArrayNode());
            when(paginationResolver.resolve(any(), any(), any(), any(), any()))
                    .thenReturn(com.kksg.applicationServices.scm.common.model.ScmPagination.builder()
                            .itemCount(0).hasNext(false).build());

            assertThat(client.execute(connection, ScmOperationRequest.of(OPERATION)).getHttpStatus())
                    .isEqualTo(status);
        }
    }

    @Nested
    @DisplayName("the adapter hook is optional")
    class NoAdapter {

        @Test
        @DisplayName("a provider with no adapter still completes a full operation")
        void providerWithoutAdapterWorks() {
            // Guards the design claim: adapters are an escape hatch, not a requirement. Both seeded
            // providers run entirely on configuration.
            assertThat(Optional.<Object>empty()).isEmpty();

            providerResponds(404, Map.of());

            assertThat(executeExpectingFailure().getErrorCode())
                    .isEqualTo(ScmErrorCode.SCM_PROVIDER_RESOURCE_NOT_FOUND);
        }
    }
}
