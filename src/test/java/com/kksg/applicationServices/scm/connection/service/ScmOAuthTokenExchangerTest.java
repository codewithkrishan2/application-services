package com.kksg.applicationServices.scm.connection.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kksg.applicationServices.scm.common.exception.ScmErrorCode;
import com.kksg.applicationServices.scm.common.exception.ScmException;
import com.kksg.applicationServices.scm.common.http.ScmHttpExecutor;
import com.kksg.applicationServices.scm.common.model.ScmTokenSet;
import com.kksg.applicationServices.scm.operation.engine.ScmHttpRequest;
import com.kksg.applicationServices.scm.operation.engine.ScmHttpResponse;
import com.kksg.applicationServices.scm.provider.config.ProviderConfiguration;
import com.kksg.applicationServices.scm.provider.config.ProviderConfigurationFactory;
import com.kksg.applicationServices.scm.provider.config.ProviderCredentialResolver;
import com.kksg.applicationServices.scm.provider.entity.ScmProvider;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The OAuth token endpoint's failure taxonomy.
 *
 * <p><b>The classification asserted here is load-bearing, not cosmetic.</b> Whether a token-endpoint
 * failure is terminal or transient decides what {@link ScmTokenRefresher} does with the connection, and
 * getting it wrong is expensive in both directions:
 * <ul>
 *   <li>Treating a <i>terminal</i> rejection as transient leaves the connection usable, so every
 *       subsequent API request re-attempts the same dead exchange. That is the unbounded retry loop
 *       this classification was introduced to stop - one token-endpoint call per user request, forever,
 *       and the user never told to reconnect.</li>
 *   <li>Treating a <i>transient</i> failure as terminal revokes a perfectly good connection because the
 *       provider was briefly unwell, and sends the user through consent for nothing.</li>
 * </ul>
 *
 * <p>So the tests below are organised by that single question, and the inputs are the real shapes
 * providers produce: an error status, and - the one that catches implementations out - HTTP 200 carrying
 * an error document.
 *
 * <p>A secondary group covers the wire format, which is the divergence that usually forces a
 * per-provider token service. Proving one implementation produces both packaging styles is what
 * justifies there being only one.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScmOAuthTokenExchangerTest {

    private static final String TOKEN_URL = "https://provider.example/oauth/token";
    private static final String CLIENT_ID = "client-abc";
    private static final String CLIENT_SECRET = "secret-xyz";
    private static final String REDIRECT_URI = "https://app.example/callback";
    private static final String REFRESH_TOKEN = "refresh-token-value";
    private static final String AUTH_CODE = "authorization-code-value";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private ScmHttpExecutor httpExecutor;

    @Mock
    private ProviderConfigurationFactory configurationFactory;

    @Mock
    private ProviderCredentialResolver credentialResolver;

    private ScmProvider provider;
    private ScmOAuthTokenExchanger exchanger;

    @BeforeEach
    void setUp() {
        provider = new ScmProvider();
        provider.setId(1);
        provider.setProviderCode("TESTHUB");

        when(credentialResolver.resolveClientId(provider)).thenReturn(CLIENT_ID);
        when(credentialResolver.resolveClientSecret(provider)).thenReturn(CLIENT_SECRET);
        when(credentialResolver.resolveRedirectUri(provider)).thenReturn(REDIRECT_URI);

        configure(null, null);

        exchanger = new ScmOAuthTokenExchanger(httpExecutor, configurationFactory, credentialResolver);
    }

    private void configure(ProviderConfiguration.ClientAuthStyle authStyle,
                           ProviderConfiguration.BodyEncoding encoding) {
        ProviderConfiguration.OAuth oauth = new ProviderConfiguration.OAuth(
                "https://provider.example/oauth/authorize", TOKEN_URL, List.of("repo"), " ",
                "scm.providers.testhub.client-id", "scm.providers.testhub.client-secret",
                "scm.providers.testhub.redirect-uri",
                new ProviderConfiguration.TokenRequest(authStyle, encoding, null), true);
        when(configurationFactory.get(provider))
                .thenReturn(new ProviderConfiguration(null, oauth, null, null, null));
    }

    private void respondWith(int status, String body) {
        try {
            exchangerResponds(new ScmHttpResponse(status, Map.of(),
                    body == null ? null : MAPPER.readTree(body), body));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void exchangerResponds(ScmHttpResponse response) {
        when(httpExecutor.execute(any(ScmHttpRequest.class))).thenReturn(response);
    }

    private ScmHttpRequest captureRequest() {
        ArgumentCaptor<ScmHttpRequest> captor = ArgumentCaptor.forClass(ScmHttpRequest.class);
        verify(httpExecutor).execute(captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("terminal rejections stop the retry loop")
    class TerminalRejections {

        /**
         * RFC 6749 section 5.2 names these, and they are the whole reason classification can be
         * precise rather than a guess at message text: they are an enumerated, provider-independent
         * vocabulary.
         */
        @ParameterizedTest(name = "error={0} is terminal")
        @ValueSource(strings = {"invalid_grant", "invalid_client", "unauthorized_client",
                "unsupported_grant_type", "invalid_scope"})
        @DisplayName("an RFC 6749 rejection code is terminal even on HTTP 200")
        void rfcRejectionCodesAreTerminal(String error) {
            // 200-with-an-error-document is the case a naive implementation misses entirely: it checks
            // the status, sees success, and then fails later on a missing access_token - as a transient
            // error, which is exactly backwards.
            respondWith(200, "{\"error\":\"%s\",\"error_description\":\"no\"}".formatted(error));

            assertThatThrownBy(() -> exchanger.refreshAccessToken(provider, REFRESH_TOKEN))
                    .isInstanceOf(ScmException.class)
                    .extracting(ex -> ((ScmException) ex).getErrorCode())
                    .isEqualTo(ScmErrorCode.SCM_OAUTH_REFRESH_REJECTED);
        }

        @ParameterizedTest(name = "HTTP {0} is terminal")
        @ValueSource(ints = {400, 401})
        @DisplayName("a 400 or 401 from the token endpoint is the grant being rejected")
        void rejectionStatusesAreTerminal(int status) {
            // Not an outage. A token endpoint answering 400/401 has understood the request and refused
            // the credential, so no retry can change the outcome.
            respondWith(status, "{\"error\":\"invalid_grant\"}");

            assertThatThrownBy(() -> exchanger.refreshAccessToken(provider, REFRESH_TOKEN))
                    .isInstanceOf(ScmException.class)
                    .extracting(ex -> ((ScmException) ex).getErrorCode())
                    .isEqualTo(ScmErrorCode.SCM_OAUTH_REFRESH_REJECTED);
        }

        @Test
        @DisplayName("rejection classification ignores case and surrounding whitespace")
        void classificationIsLenientAboutFormatting() {
            // A provider that sends " Invalid_Grant " still means invalid_grant. Failing to recognise
            // it would silently reinstate the retry loop for that provider only - the worst kind of bug
            // to find in production.
            respondWith(200, "{\"error\":\"  Invalid_Grant \"}");

            assertThatThrownBy(() -> exchanger.refreshAccessToken(provider, REFRESH_TOKEN))
                    .isInstanceOf(ScmException.class)
                    .extracting(ex -> ((ScmException) ex).getErrorCode())
                    .isEqualTo(ScmErrorCode.SCM_OAUTH_REFRESH_REJECTED);
        }

        @Test
        @DisplayName("an invalid authorization code is terminal too")
        void invalidAuthorizationCodeIsTerminal() {
            respondWith(400, "{\"error\":\"invalid_grant\"}");

            assertThatThrownBy(() -> exchanger.exchangeAuthorizationCode(provider, AUTH_CODE))
                    .isInstanceOf(ScmException.class)
                    .extracting(ex -> ((ScmException) ex).getErrorCode())
                    .isEqualTo(ScmErrorCode.SCM_OAUTH_REFRESH_REJECTED);
        }
    }

    @Nested
    @DisplayName("transient failures leave the connection recoverable")
    class TransientFailures {

        @ParameterizedTest(name = "HTTP {0} is transient")
        @ValueSource(ints = {500, 502, 503, 504, 429})
        @DisplayName("a server status or a rate limit is transient")
        void serverStatusesAreTransient(int status) {
            // 429 belongs here specifically: being told to slow down says nothing about the credential.
            respondWith(status, "{\"error\":\"server_error\"}");

            assertThatThrownBy(() -> exchanger.refreshAccessToken(provider, REFRESH_TOKEN))
                    .isInstanceOf(ScmException.class)
                    .extracting(ex -> ((ScmException) ex).getErrorCode())
                    .isEqualTo(ScmErrorCode.SCM_OAUTH_EXCHANGE_FAILED);
        }

        @ParameterizedTest(name = "error={0} is transient")
        @ValueSource(strings = {"server_error", "temporarily_unavailable", "something_new"})
        @DisplayName("an error code outside the RFC rejection set is transient")
        void unrecognisedErrorCodesAreTransient(String error) {
            // Default-to-transient is the deliberate choice. An unknown code might mean a dead
            // credential, but guessing wrong revokes a working connection, and the proactive sweep will
            // discover a genuinely dead one soon enough.
            respondWith(200, "{\"error\":\"%s\"}".formatted(error));

            assertThatThrownBy(() -> exchanger.refreshAccessToken(provider, REFRESH_TOKEN))
                    .isInstanceOf(ScmException.class)
                    .extracting(ex -> ((ScmException) ex).getErrorCode())
                    .isEqualTo(ScmErrorCode.SCM_OAUTH_EXCHANGE_FAILED);
        }

        @Test
        @DisplayName("a 200 with no parseable body is transient")
        void unparseableSuccessIsTransient() {
            exchangerResponds(new ScmHttpResponse(200, Map.of(), null, "<html>gateway</html>"));

            assertThatThrownBy(() -> exchanger.refreshAccessToken(provider, REFRESH_TOKEN))
                    .isInstanceOf(ScmException.class)
                    .extracting(ex -> ((ScmException) ex).getErrorCode())
                    .isEqualTo(ScmErrorCode.SCM_OAUTH_EXCHANGE_FAILED);
        }

        @Test
        @DisplayName("a 200 with neither error nor access_token is transient")
        void missingAccessTokenIsTransient() {
            respondWith(200, "{\"token_type\":\"bearer\"}");

            assertThatThrownBy(() -> exchanger.refreshAccessToken(provider, REFRESH_TOKEN))
                    .isInstanceOf(ScmException.class)
                    .hasMessageContaining("no access token")
                    .extracting(ex -> ((ScmException) ex).getErrorCode())
                    .isEqualTo(ScmErrorCode.SCM_OAUTH_EXCHANGE_FAILED);
        }

        @Test
        @DisplayName("a blank access_token is treated as missing")
        void blankAccessTokenIsRejected() {
            // Storing a blank token would produce a connection that looks healthy and fails every call.
            respondWith(200, "{\"access_token\":\"   \"}");

            assertThatThrownBy(() -> exchanger.refreshAccessToken(provider, REFRESH_TOKEN))
                    .isInstanceOf(ScmException.class)
                    .extracting(ex -> ((ScmException) ex).getErrorCode())
                    .isEqualTo(ScmErrorCode.SCM_OAUTH_EXCHANGE_FAILED);
        }

        @Test
        @DisplayName("a transport failure propagates unchanged")
        void transportFailurePropagates() {
            // The executor has already applied its own bounded retry. Reclassifying its verdict here
            // would either double the retries or discard the distinction it drew.
            when(httpExecutor.execute(any(ScmHttpRequest.class)))
                    .thenThrow(new ScmException(ScmErrorCode.SCM_PROVIDER_API_ERROR, "timeout"));

            assertThatThrownBy(() -> exchanger.refreshAccessToken(provider, REFRESH_TOKEN))
                    .isInstanceOf(ScmException.class)
                    .extracting(ex -> ((ScmException) ex).getErrorCode())
                    .isEqualTo(ScmErrorCode.SCM_PROVIDER_API_ERROR);
        }
    }

    @Nested
    @DisplayName("secrets never reach an exception message")
    class SecretContainment {

        /**
         * An exception message travels further than a log line - into API responses, error trackers and
         * support tickets. The token request's own content is credential material, so none of it may
         * appear there.
         */
        @Test
        @DisplayName("a rejection message names the provider and status, nothing else")
        void rejectionMessageCarriesNoSecrets() {
            respondWith(401, "{\"error\":\"invalid_client\",\"error_description\":\"bad secret-xyz\"}");

            assertThatThrownBy(() -> exchanger.refreshAccessToken(provider, REFRESH_TOKEN))
                    .isInstanceOf(ScmException.class)
                    .hasMessageContaining("TESTHUB")
                    .hasMessageNotContaining(CLIENT_SECRET)
                    .hasMessageNotContaining(REFRESH_TOKEN)
                    // error_description is excluded on purpose: providers have been seen echoing request
                    // content into it, and a token request's content is a credential.
                    .hasMessageNotContaining("bad secret");
        }

        @Test
        @DisplayName("an issued access token does not appear in any error path")
        void issuedTokenIsNotEchoed() {
            respondWith(200, "{\"access_token\":\"live-token\",\"expires_in\":7200}");

            ScmTokenSet tokens = exchanger.refreshAccessToken(provider, REFRESH_TOKEN);

            // ScmTokenSet is a class rather than a record precisely so its toString cannot leak.
            assertThat(tokens.getAccessToken()).isEqualTo("live-token");
            assertThat(tokens.toString()).doesNotContain("live-token");
        }
    }

    @Nested
    @DisplayName("configuration drives the wire format")
    class WireFormat {

        @Test
        @DisplayName("BODY auth style puts client credentials in the body")
        void bodyAuthStyle() {
            configure(ProviderConfiguration.ClientAuthStyle.BODY, ProviderConfiguration.BodyEncoding.FORM);
            respondWith(200, "{\"access_token\":\"t\"}");

            exchanger.refreshAccessToken(provider, REFRESH_TOKEN);

            ScmHttpRequest request = captureRequest();
            assertThat(request.getMethod()).isEqualTo(HttpMethod.POST);
            assertThat(request.getUri()).isEqualTo(TOKEN_URL);
            assertThat(request.getBody()).asString()
                    .contains("client_id=" + CLIENT_ID)
                    .contains("client_secret=" + CLIENT_SECRET)
                    .contains("grant_type=refresh_token");
            assertThat(request.getHeaders()).doesNotContainKey(HttpHeaders.AUTHORIZATION);
        }

        @Test
        @DisplayName("BASIC auth style puts client credentials in the Authorization header")
        void basicAuthStyle() {
            configure(ProviderConfiguration.ClientAuthStyle.BASIC, ProviderConfiguration.BodyEncoding.FORM);
            respondWith(200, "{\"access_token\":\"t\"}");

            exchanger.refreshAccessToken(provider, REFRESH_TOKEN);

            ScmHttpRequest request = captureRequest();
            String expected = "Basic " + Base64.getEncoder().encodeToString(
                    (CLIENT_ID + ":" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8));
            assertThat(request.getHeaders()).containsEntry(HttpHeaders.AUTHORIZATION, expected);
            // And not in both places: a provider rejecting duplicated credentials is a real failure mode.
            assertThat(request.getBody()).asString().doesNotContain("client_secret");
        }

        @Test
        @DisplayName("FORM encoding sends a url-encoded body")
        void formEncoding() {
            configure(ProviderConfiguration.ClientAuthStyle.BODY, ProviderConfiguration.BodyEncoding.FORM);
            respondWith(200, "{\"access_token\":\"t\"}");

            exchanger.exchangeAuthorizationCode(provider, "code with spaces");

            ScmHttpRequest request = captureRequest();
            assertThat(request.getHeaders())
                    .containsEntry(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE);
            assertThat(request.getBody()).asString().contains("code=code+with+spaces");
        }

        @Test
        @DisplayName("JSON encoding sends the parameters as a map")
        void jsonEncoding() {
            configure(ProviderConfiguration.ClientAuthStyle.BODY, ProviderConfiguration.BodyEncoding.JSON);
            respondWith(200, "{\"access_token\":\"t\"}");

            exchanger.refreshAccessToken(provider, REFRESH_TOKEN);

            ScmHttpRequest request = captureRequest();
            assertThat(request.getHeaders())
                    .containsEntry(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
            assertThat(request.getBody()).isInstanceOf(Map.class);
            @SuppressWarnings("unchecked")
            Map<String, String> jsonBody = (Map<String, String>) request.getBody();
            assertThat(jsonBody).containsEntry("grant_type", "refresh_token");
        }

        @Test
        @DisplayName("the authorization-code grant sends the configured redirect uri")
        void authorizationCodeGrantSendsRedirectUri() {
            respondWith(200, "{\"access_token\":\"t\"}");

            exchanger.exchangeAuthorizationCode(provider, AUTH_CODE);

            assertThat(captureRequest().getBody()).asString()
                    .contains("grant_type=authorization_code")
                    .contains("code=" + AUTH_CODE)
                    // A mismatched redirect_uri is rejected by every provider, so it must come from the
                    // same configuration that built the authorization URL.
                    .contains("redirect_uri=" + REDIRECT_URI.replace(":", "%3A").replace("/", "%2F"));
        }

        @Test
        @DisplayName("the token request is a POST, which the executor never retries")
        void tokenRequestIsNotRetryable() {
            respondWith(200, "{\"access_token\":\"t\"}");

            exchanger.refreshAccessToken(provider, REFRESH_TOKEN);

            // Asserted because the executor decides retryability from the method: only GET/HEAD/DELETE
            // are replayed. A token POST must not be, since providers invalidate a refresh token when it
            // is redeemed and a retry after a lost response would destroy a working credential.
            assertThat(captureRequest().getMethod()).isEqualTo(HttpMethod.POST);
        }
    }

    @Nested
    @DisplayName("response parsing")
    class ResponseParsing {

        @Test
        @DisplayName("expires_in becomes an absolute expiry")
        void expiresInBecomesInstant() {
            respondWith(200, "{\"access_token\":\"t\",\"expires_in\":7200}");

            Instant before = Instant.now();
            ScmTokenSet tokens = exchanger.refreshAccessToken(provider, REFRESH_TOKEN);

            // Stored absolute, because a relative lifetime is meaningless once persisted.
            assertThat(tokens.getExpiresAt())
                    .isAfterOrEqualTo(before.plusSeconds(7199))
                    .isBeforeOrEqualTo(Instant.now().plusSeconds(7201));
        }

        @ParameterizedTest(name = "expires_in={0} yields a null expiry")
        @ValueSource(ints = {0, -1})
        @DisplayName("a non-positive expires_in is treated as no expiry rather than an instant one")
        void nonPositiveExpiresInIsIgnored(int expiresIn) {
            // An immediate or negative expiry would make the connection permanently "expiring" and
            // refresh on every single request.
            respondWith(200, "{\"access_token\":\"t\",\"expires_in\":%d}".formatted(expiresIn));

            assertThat(exchanger.refreshAccessToken(provider, REFRESH_TOKEN).getExpiresAt()).isNull();
        }

        @Test
        @DisplayName("an absent expires_in means a non-expiring token")
        void absentExpiresInMeansNoExpiry() {
            respondWith(200, "{\"access_token\":\"t\"}");

            ScmTokenSet tokens = exchanger.refreshAccessToken(provider, REFRESH_TOKEN);

            // Normal for GitHub OAuth Apps, and the reason the readiness resolver treats a null expiry
            // as READY rather than as a problem.
            assertThat(tokens.getExpiresAt()).isNull();
        }

        @Test
        @DisplayName("an omitted refresh_token is reported as absent, not as empty")
        void omittedRefreshTokenIsAbsent() {
            // Several providers omit refresh_token from a refresh response and expect the original to
            // remain valid. ScmConnectionTokens relies on hasRefreshToken() to avoid overwriting it.
            respondWith(200, "{\"access_token\":\"t\"}");

            ScmTokenSet tokens = exchanger.refreshAccessToken(provider, REFRESH_TOKEN);

            assertThat(tokens.hasRefreshToken()).isFalse();
        }

        @Test
        @DisplayName("a rotated refresh_token is returned")
        void rotatedRefreshTokenIsReturned() {
            respondWith(200, "{\"access_token\":\"t\",\"refresh_token\":\"rotated\"}");

            ScmTokenSet tokens = exchanger.refreshAccessToken(provider, REFRESH_TOKEN);

            assertThat(tokens.hasRefreshToken()).isTrue();
            assertThat(tokens.getRefreshToken()).isEqualTo("rotated");
        }

        @Test
        @DisplayName("granted scope is carried through")
        void grantedScopeIsCarried() {
            // Granted scope can be narrower than requested; keeping it lets a later permission failure
            // be explained without another provider round trip.
            respondWith(200, "{\"access_token\":\"t\",\"scope\":\"repo read:user\"}");

            assertThat(exchanger.refreshAccessToken(provider, REFRESH_TOKEN).getScope())
                    .isEqualTo("repo read:user");
        }
    }

    @Nested
    @DisplayName("misconfiguration is reported as misconfiguration")
    class Misconfiguration {

        @Test
        @DisplayName("a provider with no oauth block fails with a configuration error")
        void missingOAuthBlockIsAConfigurationError() {
            // Distinct from an exchange failure on purpose: nothing was asked of the provider, so this
            // is an operator problem and must not be reported to the user as a provider outage.
            when(configurationFactory.get(provider))
                    .thenReturn(new ProviderConfiguration(null, null, null, null, null));

            assertThatThrownBy(() -> exchanger.refreshAccessToken(provider, REFRESH_TOKEN))
                    .isInstanceOf(ScmException.class)
                    .extracting(ex -> ((ScmException) ex).getErrorCode())
                    .isEqualTo(ScmErrorCode.SCM_PROVIDER_CONFIGURATION_INVALID);
        }
    }

    @Nested
    @DisplayName("the error-code mapping as a table")
    class ClassificationTable {

        @ParameterizedTest(name = "{0}")
        @ArgumentsSource(TokenEndpointOutcomes.class)
        @DisplayName("status and body map to the expected error code")
        void statusAndBodyMapToErrorCode(String name, int status, String body, ScmErrorCode expected) {
            respondWith(status, body);

            assertThatThrownBy(() -> exchanger.refreshAccessToken(provider, REFRESH_TOKEN))
                    .isInstanceOf(ScmException.class)
                    .extracting(ex -> ((ScmException) ex).getErrorCode())
                    .as(name)
                    .isEqualTo(expected);
        }
    }

    /** The full matrix in one place, so a change in classification shows up as a diff here. */
    static class TokenEndpointOutcomes implements ArgumentsProvider {
        @Override
        public Stream<Arguments> provideArguments(ExtensionContext context) {
            return Stream.of(
                    Arguments.of("400 invalid_grant -> terminal", 400, "{\"error\":\"invalid_grant\"}",
                            ScmErrorCode.SCM_OAUTH_REFRESH_REJECTED),
                    Arguments.of("401 invalid_client -> terminal", 401, "{\"error\":\"invalid_client\"}",
                            ScmErrorCode.SCM_OAUTH_REFRESH_REJECTED),
                    Arguments.of("403 -> transient", 403, "{\"error\":\"forbidden\"}",
                            ScmErrorCode.SCM_OAUTH_EXCHANGE_FAILED),
                    Arguments.of("404 -> transient", 404, "{\"error\":\"not_found\"}",
                            ScmErrorCode.SCM_OAUTH_EXCHANGE_FAILED),
                    Arguments.of("429 -> transient", 429, "{\"error\":\"slow_down\"}",
                            ScmErrorCode.SCM_OAUTH_EXCHANGE_FAILED),
                    Arguments.of("500 -> transient", 500, "{}", ScmErrorCode.SCM_OAUTH_EXCHANGE_FAILED),
                    Arguments.of("503 -> transient", 503, "{}", ScmErrorCode.SCM_OAUTH_EXCHANGE_FAILED),
                    Arguments.of("200 invalid_grant -> terminal", 200, "{\"error\":\"invalid_grant\"}",
                            ScmErrorCode.SCM_OAUTH_REFRESH_REJECTED),
                    Arguments.of("200 server_error -> transient", 200, "{\"error\":\"server_error\"}",
                            ScmErrorCode.SCM_OAUTH_EXCHANGE_FAILED),
                    Arguments.of("200 empty object -> transient", 200, "{}",
                            ScmErrorCode.SCM_OAUTH_EXCHANGE_FAILED));
        }
    }
}
