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
     * @return the provider's response, including non-2xx. Transport failures (DNS, connect, read
     *         timeout) raise {@link ScmErrorCode#SCM_PROVIDER_API_ERROR}, because unlike an HTTP error
     *         they carry no status to classify.
     */
    public ScmHttpResponse execute(ScmHttpRequest request) {
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
