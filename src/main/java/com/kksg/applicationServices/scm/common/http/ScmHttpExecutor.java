package com.kksg.applicationServices.scm.common.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kksg.applicationServices.scm.common.exception.ScmErrorCode;
import com.kksg.applicationServices.scm.common.exception.ScmException;
import com.kksg.applicationServices.scm.operation.engine.ScmHttpRequest;
import com.kksg.applicationServices.scm.operation.engine.ScmHttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Performs one provider HTTP call and returns the raw response.
 *
 * <p>Lives in {@code scm.common.http} rather than in the engine because two independent callers need
 * it - the operation engine and the OAuth token exchange - and placing it in either would force a
 * package dependency between them.
 *
 * <p>Responses are always read as {@code String} and parsed here rather than letting the HTTP client
 * bind them. That is what makes a unified diff (text) and a repository list (JSON) travel the same
 * code path, and it keeps the raw body available for logging when a provider returns an unexpected
 * shape.
 *
 * <p><b>Logging contract.</b> This class logs method, URI, status and duration - the information
 * needed to debug an integration - and never logs request headers, because the {@code Authorization}
 * header is among them. Request and response bodies are not logged at INFO either; a body can contain
 * a webhook secret on webhook-creation calls.
 */
@Component
public class ScmHttpExecutor {

    private static final Logger log = LoggerFactory.getLogger(ScmHttpExecutor.class);

    private final RestTemplate scmRestTemplate;
    private final ObjectMapper objectMapper;
    private final ScmHttpProperties properties;

    public ScmHttpExecutor(RestTemplate scmRestTemplate,
                           ObjectMapper objectMapper,
                           ScmHttpProperties properties) {
        this.scmRestTemplate = scmRestTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * Most redirect hops followed for one logical call.
     *
     * <p>Two is enough for every observed provider behaviour and small enough that a redirect loop
     * costs three requests rather than a pinned thread. A provider that needs more is signalling
     * something this client should not be guessing at.
     */
    private static final int MAX_REDIRECTS = 2;

    /**
     * @return the provider's response, including non-2xx. Transport failures (DNS, connect, read
     *         timeout) raise {@link ScmErrorCode#SCM_PROVIDER_API_ERROR}, because unlike an HTTP error
     *         they carry no status to classify.
     *
     * <p>Same-origin redirects are followed; see {@link #followRedirect}. Anything else is returned to
     * the engine as-is so it can classify the status itself.
     */
    public ScmHttpResponse execute(ScmHttpRequest request) {
        ScmHttpRequest current = request;

        for (int hop = 0; ; hop++) {
            ScmHttpResponse response = sendWithRetries(current);

            if (!isRedirect(response.statusCode())) {
                return response;
            }
            if (hop >= MAX_REDIRECTS) {
                log.warn("SCM_HTTP_REDIRECT_LIMIT: method={}, uri={}, status={}, hops={}",
                        current.getMethod(), current.getUri(), response.statusCode(), hop + 1);
                throw new ScmException(ScmErrorCode.SCM_PROVIDER_API_ERROR,
                        "provider redirected more than %d times".formatted(MAX_REDIRECTS));
            }

            ScmHttpRequest next = followRedirect(current, response);
            if (next == null) {
                // Not followable. Returned rather than thrown so the engine still sees the status and
                // can classify it, which keeps the error vocabulary consistent.
                return response;
            }
            current = next;
        }
    }

    private boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    /**
     * Builds the follow-up request for a redirect, or {@code null} when the redirect must not be followed.
     *
     * <p><b>Why this is handled here rather than by the HTTP client.</b> {@code HttpURLConnection}
     * follows redirects by default and replays the original request headers while doing so - including
     * the {@code Authorization} header carrying the user's provider token. A provider endpoint
     * answering {@code 302 Location: https://attacker.example/} would therefore be handed that
     * credential, and nothing would notice. That is why automatic following is disabled in
     * {@code ScmHttpClientConfig}.
     *
     * <p>But refusing <i>all</i> redirects was also wrong, and cost real functionality: Bitbucket
     * answers {@code 302} for a pull request's {@code /diff} and {@code /diffstat}, pointing at the
     * equivalent commit-range URL on the same host. With following disabled those two operations failed
     * with a misleading {@code SCM_PROVIDER_API_ERROR} - the provider was healthy and the request was
     * correct.
     *
     * <p>So redirects are followed under one rule: <b>the target must be the same origin</b> - same
     * scheme, host and port as the request that produced it. A same-origin redirect cannot send the
     * credential anywhere it was not already going. A cross-origin one is refused and the credential is
     * never forwarded, which is the property that mattered in the first place.
     *
     * <p>The method is preserved rather than downgraded to GET. Every operation reaching this client is
     * either a read or a deliberate write, and silently turning a redirected {@code POST} into a
     * {@code GET} would be a quieter failure than refusing it.
     */
    private ScmHttpRequest followRedirect(ScmHttpRequest request, ScmHttpResponse response) {
        String location = response.header(HttpHeaders.LOCATION.toLowerCase(Locale.ROOT));
        if (location == null || location.isBlank()) {
            log.warn("SCM_HTTP_REDIRECT_WITHOUT_LOCATION: method={}, uri={}, status={}",
                    request.getMethod(), request.getUri(), response.statusCode());
            return null;
        }

        URI from = URI.create(request.getUri());
        URI target;
        try {
            // Resolved against the original, so a relative Location works as the RFC intends.
            target = from.resolve(location.trim());
        } catch (IllegalArgumentException ex) {
            log.warn("SCM_HTTP_REDIRECT_INVALID_LOCATION: method={}, uri={}", request.getMethod(),
                    request.getUri());
            return null;
        }

        if (!isSameOrigin(from, target)) {
            // Logged without the target's path or query: a redirect Location on an error path can echo
            // request content, and the host is the part worth knowing.
            log.warn("SCM_HTTP_REDIRECT_CROSS_ORIGIN_REFUSED: fromHost={}, toHost={}, status={}",
                    from.getHost(), target.getHost(), response.statusCode());
            return null;
        }

        log.debug("SCM_HTTP_REDIRECT_FOLLOWED: method={}, status={}", request.getMethod(),
                response.statusCode());

        return request.withUri(target.toString());
    }

    private boolean isSameOrigin(URI from, URI to) {
        if (to.getHost() == null || to.getScheme() == null) {
            return false;
        }
        return from.getScheme().equalsIgnoreCase(to.getScheme())
                && from.getHost().equalsIgnoreCase(to.getHost())
                && effectivePort(from) == effectivePort(to);
    }

    private int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    /**
     * Statuses worth trying again.
     *
     * <p>Gateway and availability errors only. Notably absent:
     * <ul>
     *   <li><b>Every 4xx.</b> A malformed request, a missing resource or a rejected credential will be
     *       rejected identically next time; retrying only multiplies the failure.</li>
     *   <li><b>429.</b> Retrying a rate limit inline is how one becomes an outage. It is surfaced to
     *       the caller instead, with the provider's {@code Retry-After} attached, so the decision to
     *       wait belongs to whoever can actually afford to.</li>
     *   <li><b>500 and 501.</b> A bare "internal server error" from a provider is as likely to be a
     *       deterministic fault in the request as a transient one, and 501 is a statement about
     *       capability. 502/503/504 are the ones that explicitly mean "try later".</li>
     * </ul>
     */
    private static final Set<Integer> RETRYABLE_STATUSES = Set.of(502, 503, 504);

    /**
     * Sends the request, retrying only a genuinely retryable failure.
     *
     * <p><b>Only safe methods are retried.</b> A {@code POST} that timed out may well have been
     * applied by the provider - the response was lost, not the request - so retrying it could post a
     * second review comment or create a duplicate webhook. Since the operations that write are exactly
     * the ones with visible side effects, a lost response is reported rather than guessed at.
     */
    private ScmHttpResponse sendWithRetries(ScmHttpRequest request) {
        int maxAttempts = 1 + Math.max(0, properties.getMaxRetries());
        boolean retryable = isIdempotent(request.getMethod());

        for (int attempt = 1; ; attempt++) {
            boolean lastAttempt = attempt >= maxAttempts || !retryable;

            try {
                ScmHttpResponse response = send(request);

                if (lastAttempt || !RETRYABLE_STATUSES.contains(response.statusCode())) {
                    return response;
                }
                log.warn("SCM_HTTP_RETRYING: method={}, uri={}, status={}, attempt={}/{}",
                        request.getMethod(), request.getUri(), response.statusCode(), attempt, maxAttempts);

            } catch (ScmException ex) {
                // A transport failure: DNS, connect refused, read timeout. Unlike an HTTP error it
                // carries no status, and it is the case retries exist for.
                if (lastAttempt) {
                    throw ex;
                }
                log.warn("SCM_HTTP_RETRYING_AFTER_TRANSPORT_FAILURE: method={}, uri={}, attempt={}/{}",
                        request.getMethod(), request.getUri(), attempt, maxAttempts);
            }

            backOff(attempt);
        }
    }

    /**
     * @return whether re-sending this method is free of side effects.
     *
     * <p>{@code DELETE} is idempotent by HTTP's definition and is included: deleting an
     * already-deleted webhook is a no-op the provider reports as 404, which is a better outcome than
     * leaving a webhook behind because one response was lost.
     */
    private boolean isIdempotent(HttpMethod method) {
        return HttpMethod.GET.equals(method)
                || HttpMethod.HEAD.equals(method)
                || HttpMethod.DELETE.equals(method);
    }

    /**
     * Waits before the next attempt, doubling each time.
     *
     * <p>Restores the interrupt flag rather than swallowing it: this runs on a request thread, and a
     * shutdown or a client disconnect that interrupts it should stop the retry loop rather than be
     * discarded.
     */
    private void backOff(int attempt) {
        long delayMs = properties.getRetryBackoffMs() * (1L << (attempt - 1));
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ScmException(ScmErrorCode.SCM_PROVIDER_API_ERROR, "interrupted while retrying", ex);
        }
    }

    private ScmHttpResponse send(ScmHttpRequest request) {
        HttpHeaders headers = new HttpHeaders();
        request.getHeaders().forEach(headers::set);

        Object body = request.getBody();
        if (body != null && !headers.containsKey(HttpHeaders.CONTENT_TYPE)) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }

        HttpEntity<Object> entity = new HttpEntity<>(serializeBody(body, headers), headers);
        long startedAt = System.nanoTime();

        try {
            ResponseEntity<String> response = scmRestTemplate.exchange(
                    URI.create(request.getUri()), request.getMethod(), entity, String.class);

            long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
            String responseBody = response.getBody();

            if (responseBody != null && responseBody.length() > properties.getMaxResponseBytes()) {
                log.warn("SCM_HTTP_RESPONSE_TOO_LARGE: method={}, uri={}, bytes={}",
                        request.getMethod(), request.getUri(), responseBody.length());
                throw new ScmException(ScmErrorCode.SCM_PROVIDER_API_ERROR, "response exceeded configured size limit");
            }

            log.info("SCM_HTTP_CALL: method={}, uri={}, status={}, durationMs={}",
                    request.getMethod(), request.getUri(), response.getStatusCode().value(), durationMs);

            JsonNode parsed = request.isExpectTextResponse() ? null : tryParseJson(responseBody);
            return new ScmHttpResponse(
                    response.getStatusCode().value(),
                    lowerCaseHeaders(response.getHeaders()),
                    parsed,
                    responseBody);

        } catch (ResourceAccessException ex) {
            long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
            log.error("SCM_HTTP_TRANSPORT_FAILURE: method={}, uri={}, durationMs={}, cause={}",
                    request.getMethod(), request.getUri(), durationMs, ex.getClass().getSimpleName());
            throw new ScmException(ScmErrorCode.SCM_PROVIDER_API_ERROR, "provider is unreachable", ex);
        }
    }

    /**
     * Serializes the body according to the already-set content type.
     *
     * <p>Form encoding is handled explicitly because OAuth token endpoints commonly require
     * {@code application/x-www-form-urlencoded}, and letting a message converter guess would silently
     * send JSON to an endpoint that ignores it and returns a confusing {@code invalid_request}.
     */
    private Object serializeBody(Object body, HttpHeaders headers) {
        if (body == null) {
            return null;
        }
        if (body instanceof String alreadySerialized) {
            return alreadySerialized;
        }
        MediaType contentType = headers.getContentType();
        if (contentType != null && MediaType.APPLICATION_FORM_URLENCODED.isCompatibleWith(contentType)) {
            // Callers pass an already-encoded string for form bodies; anything else is a bug worth surfacing.
            throw new ScmException(ScmErrorCode.SCM_PROVIDER_API_ERROR,
                    "form-encoded bodies must be supplied pre-encoded");
        }
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception ex) {
            throw new ScmException(ScmErrorCode.SCM_PROVIDER_API_ERROR, "request body could not be serialized", ex);
        }
    }

    private JsonNode tryParseJson(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception ex) {
            // Not fatal: an error response may be HTML. The mapping stage decides whether it needed JSON.
            log.debug("SCM_HTTP_BODY_NOT_JSON: length={}", body.length());
            return null;
        }
    }

    private Map<String, List<String>> lowerCaseHeaders(HttpHeaders headers) {
        Map<String, List<String>> normalized = new HashMap<>();
        headers.forEach((name, values) -> normalized.put(name.toLowerCase(Locale.ROOT), new ArrayList<>(values)));
        return normalized;
    }
}
