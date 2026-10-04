package com.kksg.applicationServices.scm.common.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kksg.applicationServices.scm.common.exception.ScmErrorCode;
import com.kksg.applicationServices.scm.common.exception.ScmException;
import com.kksg.applicationServices.scm.operation.engine.ScmHttpRequest;
import com.kksg.applicationServices.scm.operation.engine.ScmHttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Redirect handling, and the security rule that bounds it.
 *
 * <p><b>Why this exists.</b> The client is configured not to let {@code HttpURLConnection} follow
 * redirects, because it replays the original headers while doing so and would hand a user's provider
 * token to whatever a {@code Location} pointed at. Refusing them outright was the first fix, and it
 * broke two real operations: Bitbucket answers {@code 302} for a pull request's {@code /diff} and
 * {@code /diffstat}, redirecting to the equivalent commit-range URL <i>on the same host</i>. Both
 * failed with a provider error while the provider was healthy.
 *
 * <p>So the rule is "follow same-origin only". These tests pin both halves of it: the redirect that
 * must be followed, and the one that must never be - because getting the second wrong is a credential
 * leak, and getting the first wrong is a silently broken feature.
 */
@ExtendWith(MockitoExtension.class)
class ScmHttpExecutorRedirectTest {

    private static final String ORIGIN = "https://api.bitbucket.org";
    private static final String DIFF_URI =
            ORIGIN + "/2.0/repositories/acme/api/pullrequests/5472/diff";
    private static final String REDIRECT_TARGET =
            ORIGIN + "/2.0/repositories/acme/api/diff/acme/api:97dd6da%0D2bbcfa8";

    @Mock
    private RestTemplate restTemplate;

    private ScmHttpExecutor executor;

    @BeforeEach
    void setUp() {
        ScmHttpProperties properties = new ScmHttpProperties();
        executor = new ScmHttpExecutor(restTemplate, new ObjectMapper(), properties);
    }

    private ScmHttpRequest diffRequest() {
        return ScmHttpRequest.of(HttpMethod.GET, DIFF_URI,
                Map.of(HttpHeaders.AUTHORIZATION, "Bearer provider-token"), null, true);
    }

    private ResponseEntity<String> redirect(HttpStatus status, String location) {
        HttpHeaders headers = new HttpHeaders();
        if (location != null) {
            headers.add(HttpHeaders.LOCATION, location);
        }
        return new ResponseEntity<>(null, headers, status);
    }

    private ResponseEntity<String> ok(String body) {
        return new ResponseEntity<>(body, new HttpHeaders(), HttpStatus.OK);
    }

    /* --------------------------------------------------------------------- *
     * The functional half
     * --------------------------------------------------------------------- */

    @Test
    @DisplayName("follows a same-origin redirect and returns the final response")
    void followsSameOriginRedirect() {
        when(restTemplate.exchange(eq(URI.create(DIFF_URI)), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(redirect(HttpStatus.FOUND, REDIRECT_TARGET));
        when(restTemplate.exchange(eq(URI.create(REDIRECT_TARGET)), eq(HttpMethod.GET), any(),
                eq(String.class)))
                .thenReturn(ok("diff --git a/package.json b/package.json"));

        ScmHttpResponse response = executor.execute(diffRequest());

        // This is the Bitbucket diff case. Before same-origin following it surfaced as a 302 the engine
        // classified as SCM_PROVIDER_API_ERROR.
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.bodyText()).startsWith("diff --git");
    }

    @Test
    @DisplayName("carries the credential and the method across a same-origin redirect")
    void preservesRequestAcrossRedirect() {
        when(restTemplate.exchange(eq(URI.create(DIFF_URI)), any(), any(), eq(String.class)))
                .thenReturn(redirect(HttpStatus.FOUND, REDIRECT_TARGET));
        when(restTemplate.exchange(eq(URI.create(REDIRECT_TARGET)), any(), any(), eq(String.class)))
                .thenReturn(ok("diff"));

        executor.execute(diffRequest());

        ArgumentCaptor<HttpEntity<?>> entity = ArgumentCaptor.captor();
        verify(restTemplate).exchange(eq(URI.create(REDIRECT_TARGET)), eq(HttpMethod.GET),
                entity.capture(), eq(String.class));

        // Same origin, so forwarding the token cannot send it anywhere it was not already going - and
        // without it the redirected request would 401 on a private repository.
        assertThat(entity.getValue().getHeaders().getFirst(HttpHeaders.AUTHORIZATION))
                .isEqualTo("Bearer provider-token");
    }

    @Test
    @DisplayName("resolves a relative Location against the original request")
    void resolvesRelativeLocation() {
        when(restTemplate.exchange(eq(URI.create(DIFF_URI)), any(), any(), eq(String.class)))
                .thenReturn(redirect(HttpStatus.FOUND, "/2.0/elsewhere"));
        when(restTemplate.exchange(eq(URI.create(ORIGIN + "/2.0/elsewhere")), any(), any(),
                eq(String.class)))
                .thenReturn(ok("body"));

        assertThat(executor.execute(diffRequest()).statusCode()).isEqualTo(200);
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 302, 303, 307, 308})
    @DisplayName("follows every redirect status a provider might use")
    void followsAllRedirectStatuses(int status) {
        when(restTemplate.exchange(eq(URI.create(DIFF_URI)), any(), any(), eq(String.class)))
                .thenReturn(redirect(HttpStatus.valueOf(status), REDIRECT_TARGET));
        when(restTemplate.exchange(eq(URI.create(REDIRECT_TARGET)), any(), any(), eq(String.class)))
                .thenReturn(ok("body"));

        assertThat(executor.execute(diffRequest()).statusCode()).isEqualTo(200);
    }

    /* --------------------------------------------------------------------- *
     * The security half
     * --------------------------------------------------------------------- */

    @Test
    @DisplayName("refuses a cross-host redirect and never forwards the credential")
    void refusesCrossHostRedirect() {
        String attacker = "https://attacker.example/collect";
        when(restTemplate.exchange(eq(URI.create(DIFF_URI)), any(), any(), eq(String.class)))
                .thenReturn(redirect(HttpStatus.FOUND, attacker));

        ScmHttpResponse response = executor.execute(diffRequest());

        // The whole reason automatic following is disabled. The 302 is handed back for the engine to
        // classify, and the second request is never made.
        assertThat(response.statusCode()).isEqualTo(302);
        verify(restTemplate, never()).exchange(eq(URI.create(attacker)), any(), any(), eq(String.class));
        verify(restTemplate, times(1)).exchange(any(URI.class), any(), any(), eq(String.class));
    }

    @Test
    @DisplayName("refuses a downgrade from https to http on the same host")
    void refusesSchemeDowngrade() {
        when(restTemplate.exchange(eq(URI.create(DIFF_URI)), any(), any(), eq(String.class)))
                .thenReturn(redirect(HttpStatus.FOUND, "http://api.bitbucket.org/2.0/plain"));

        // Same host but not the same origin: sending the token over cleartext would expose it on the
        // wire, so this is refused for the same reason a different host is.
        assertThat(executor.execute(diffRequest()).statusCode()).isEqualTo(302);
        verify(restTemplate, times(1)).exchange(any(URI.class), any(), any(), eq(String.class));
    }

    @Test
    @DisplayName("refuses a redirect to a different port on the same host")
    void refusesPortChange() {
        when(restTemplate.exchange(eq(URI.create(DIFF_URI)), any(), any(), eq(String.class)))
                .thenReturn(redirect(HttpStatus.FOUND, "https://api.bitbucket.org:8443/2.0/plain"));

        assertThat(executor.execute(diffRequest()).statusCode()).isEqualTo(302);
        verify(restTemplate, times(1)).exchange(any(URI.class), any(), any(), eq(String.class));
    }

    @Test
    @DisplayName("treats a redirect with no Location as the failure it is")
    void redirectWithoutLocation() {
        when(restTemplate.exchange(eq(URI.create(DIFF_URI)), any(), any(), eq(String.class)))
                .thenReturn(redirect(HttpStatus.FOUND, null));

        // Returned rather than thrown, so the engine's own status classification still applies and the
        // error vocabulary stays consistent.
        assertThat(executor.execute(diffRequest()).statusCode()).isEqualTo(302);
    }

    @Test
    @DisplayName("bounds a redirect loop instead of following it forever")
    void boundsRedirectLoops() {
        String second = ORIGIN + "/2.0/loop";
        when(restTemplate.exchange(eq(URI.create(DIFF_URI)), any(), any(), eq(String.class)))
                .thenReturn(redirect(HttpStatus.FOUND, second));
        when(restTemplate.exchange(eq(URI.create(second)), any(), any(), eq(String.class)))
                .thenReturn(redirect(HttpStatus.FOUND, second));

        assertThatThrownBy(() -> executor.execute(diffRequest()))
                .isInstanceOf(ScmException.class)
                .satisfies(thrown -> assertThat(((ScmException) thrown).getErrorCode())
                        .isEqualTo(ScmErrorCode.SCM_PROVIDER_API_ERROR))
                .hasMessageContaining("redirected");

        // Bounded: the initial call plus the permitted hops, and then it stops.
        verify(restTemplate, times(3)).exchange(any(URI.class), any(), any(), eq(String.class));
    }

    /* --------------------------------------------------------------------- *
     * Non-redirect behaviour is untouched
     * --------------------------------------------------------------------- */

    @ParameterizedTest
    @ValueSource(ints = {200, 201, 400, 401, 403, 404, 409, 429, 500, 501})
    @DisplayName("hands every non-redirect, non-retryable status back without a second attempt")
    void passesNonRedirectStatusesThrough(int status) {
        when(restTemplate.exchange(any(URI.class), any(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{}", new HttpHeaders(), HttpStatus.valueOf(status)));

        ScmHttpResponse response = executor.execute(
                ScmHttpRequest.of(HttpMethod.GET, ORIGIN + "/2.0/user", Map.of(), null, false));

        // The error handler is deliberately non-throwing so the engine, not the HTTP client, owns the
        // mapping from status to ScmErrorCode.
        assertThat(response.statusCode()).isEqualTo(status);

        // Exactly one attempt. 429 is in this list on purpose: retrying a rate limit inline is how one
        // becomes an outage, so it is surfaced rather than retried. So is 500 - a bare "internal
        // server error" is as likely to be a deterministic fault in the request as a transient one.
        verify(restTemplate, times(1)).exchange(any(URI.class), any(), any(), eq(String.class));
    }

    /* --------------------------------------------------------------------- *
     * Bounded retry
     * --------------------------------------------------------------------- */

    @ParameterizedTest
    @ValueSource(ints = {502, 503, 504})
    @DisplayName("retries a gateway error and returns the eventual success")
    void retriesGatewayErrors(int status) {
        when(restTemplate.exchange(any(URI.class), any(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{}", new HttpHeaders(), HttpStatus.valueOf(status)))
                .thenReturn(ok("{\"ok\":true}"));

        ScmHttpResponse response = executor.execute(
                ScmHttpRequest.of(HttpMethod.GET, ORIGIN + "/2.0/user", Map.of(), null, false));

        assertThat(response.statusCode()).isEqualTo(200);
        verify(restTemplate, times(2)).exchange(any(URI.class), any(), any(), eq(String.class));
    }

    @Test
    @DisplayName("stops after the configured number of retries")
    void boundsRetries() {
        when(restTemplate.exchange(any(URI.class), any(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{}", new HttpHeaders(), HttpStatus.BAD_GATEWAY));

        ScmHttpResponse response = executor.execute(
                ScmHttpRequest.of(HttpMethod.GET, ORIGIN + "/2.0/user", Map.of(), null, false));

        // Three requests for one logical call: the first plus two retries. The final status is returned
        // rather than thrown, so the engine still classifies it.
        assertThat(response.statusCode()).isEqualTo(502);
        verify(restTemplate, times(3)).exchange(any(URI.class), any(), any(), eq(String.class));
    }

    @Test
    @DisplayName("retries a transport failure, which is what retries exist for")
    void retriesTransportFailure() {
        when(restTemplate.exchange(any(URI.class), any(), any(), eq(String.class)))
                .thenThrow(new org.springframework.web.client.ResourceAccessException("connect timed out"))
                .thenReturn(ok("{}"));

        assertThat(executor.execute(
                ScmHttpRequest.of(HttpMethod.GET, ORIGIN + "/2.0/user", Map.of(), null, false))
                .statusCode()).isEqualTo(200);

        verify(restTemplate, times(2)).exchange(any(URI.class), any(), any(), eq(String.class));
    }

    @Test
    @DisplayName("gives up on a persistent transport failure with a provider error")
    void persistentTransportFailure() {
        when(restTemplate.exchange(any(URI.class), any(), any(), eq(String.class)))
                .thenThrow(new org.springframework.web.client.ResourceAccessException("connect timed out"));

        assertThatThrownBy(() -> executor.execute(
                userRequest()))
                .isInstanceOf(ScmException.class)
                .satisfies(thrown -> assertThat(((ScmException) thrown).getErrorCode())
                        .isEqualTo(ScmErrorCode.SCM_PROVIDER_API_ERROR));

        verify(restTemplate, times(3)).exchange(any(URI.class), any(), any(), eq(String.class));
    }

    @Test
    @DisplayName("never retries a write, because a lost response is not a lost request")
    void doesNotRetryWrites() {
        when(restTemplate.exchange(any(URI.class), any(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{}", new HttpHeaders(), HttpStatus.BAD_GATEWAY));

        ScmHttpResponse response = executor.execute(ScmHttpRequest.of(HttpMethod.POST,
                ORIGIN + "/2.0/repositories/acme/api/pullrequests/1/comments",
                Map.of(), "{\"content\":{}}", false));

        // A POST that failed at the gateway may still have been applied - the response was lost, not
        // the request. Retrying it could post a second review comment, so the failure is reported.
        assertThat(response.statusCode()).isEqualTo(502);
        verify(restTemplate, times(1)).exchange(any(URI.class), any(), any(), eq(String.class));
    }

    @Test
    @DisplayName("retries DELETE, which is idempotent by definition")
    void retriesDelete() {
        when(restTemplate.exchange(any(URI.class), any(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{}", new HttpHeaders(), HttpStatus.SERVICE_UNAVAILABLE))
                .thenReturn(ok("{}"));

        ScmHttpResponse response = executor.execute(ScmHttpRequest.of(HttpMethod.DELETE,
                ORIGIN + "/2.0/repositories/acme/api/hooks/abc", Map.of(), null, false));

        // Deleting an already-deleted webhook is a no-op the provider reports as 404, which beats
        // leaving a webhook behind because one response was lost.
        assertThat(response.statusCode()).isEqualTo(200);
        verify(restTemplate, times(2)).exchange(any(URI.class), any(), any(), eq(String.class));
    }

    private ScmHttpRequest userRequest() {
        return ScmHttpRequest.of(HttpMethod.GET, ORIGIN + "/2.0/user", Map.of(), null, false);
    }

    @Test
    @DisplayName("lower-cases response headers so the engine can read them case-insensitively")
    void normalisesHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.put("X-RateLimit-Remaining", List.of("0"));
        when(restTemplate.exchange(any(URI.class), any(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{}", headers, HttpStatus.FORBIDDEN));

        ScmHttpResponse response = executor.execute(
                ScmHttpRequest.of(HttpMethod.GET, ORIGIN + "/2.0/user", Map.of(), null, false));

        // Rate-limit classification depends on this: a 403 is only a rate limit when the header says so.
        assertThat(response.header("x-ratelimit-remaining")).isEqualTo("0");
    }
}
