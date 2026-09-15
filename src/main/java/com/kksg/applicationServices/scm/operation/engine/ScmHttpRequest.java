package com.kksg.applicationServices.scm.operation.engine;

import org.springframework.http.HttpMethod;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A fully resolved, provider-specific HTTP request produced by {@code ScmRequestBuilder}.
 *
 * <p>This is the seam between the declarative half of the engine (configuration in, request out) and
 * the imperative half (request in, response out). Materialising it as a value rather than calling
 * the HTTP client inline has two practical benefits: it is trivially assertable in tests without a
 * server, and it is the object a provider adapter is handed if it needs to sign or adjust a request.
 *
 * <p>Mutable header map with a copy-on-construct: adapters receive a request they can safely derive
 * from via {@link #withHeader}, without being able to mutate the instance the engine still holds.
 */
public class ScmHttpRequest {

    private final HttpMethod method;
    private final String uri;
    private final Map<String, String> headers;
    private final Object body;
    private final boolean expectTextResponse;

    private ScmHttpRequest(HttpMethod method, String uri, Map<String, String> headers,
                           Object body, boolean expectTextResponse) {
        this.method = method;
        this.uri = uri;
        this.headers = new LinkedHashMap<>(headers);
        this.body = body;
        this.expectTextResponse = expectTextResponse;
    }

    public static ScmHttpRequest of(HttpMethod method, String uri, Map<String, String> headers,
                                    Object body, boolean expectTextResponse) {
        return new ScmHttpRequest(method, uri, headers, body, expectTextResponse);
    }

    /** @return a copy with one header added or replaced; the receiver is unchanged. */
    public ScmHttpRequest withHeader(String name, String value) {
        Map<String, String> merged = new LinkedHashMap<>(headers);
        merged.put(name, value);
        return new ScmHttpRequest(method, uri, merged, body, expectTextResponse);
    }

    /** @return a copy targeting a different absolute URI; used for cursor-based continuation. */
    public ScmHttpRequest withUri(String newUri) {
        return new ScmHttpRequest(method, newUri, headers, body, expectTextResponse);
    }

    public HttpMethod getMethod() {
        return method;
    }

    /** Absolute URI, base URL already applied and query string already appended. */
    public String getUri() {
        return uri;
    }

    public Map<String, String> getHeaders() {
        return Map.copyOf(headers);
    }

    public Object getBody() {
        return body;
    }

    /**
     * True when the response must be read as text rather than parsed as JSON - the unified-diff
     * operations. Decided from the operation's {@code response_mapping} type, so it is configuration
     * rather than a hardcoded endpoint list.
     */
    public boolean isExpectTextResponse() {
        return expectTextResponse;
    }

    @Override
    public String toString() {
        // Headers are omitted: they carry the Authorization value.
        return "ScmHttpRequest{method=%s, uri=%s}".formatted(method, uri);
    }
}
