package com.kksg.applicationServices.scm.operation.engine;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * Raw provider response, before normalization.
 *
 * <p>Both {@code bodyJson} and {@code bodyText} are retained because the mapping stage needs one or
 * the other depending on the operation: a diff arrives as text, everything else as JSON. Keeping
 * both avoids a second read of a consumed stream and lets a text response still be inspected if a
 * provider unexpectedly returns JSON on an error path.
 *
 * @param statusCode HTTP status.
 * @param headers    response headers, lower-cased keys, used for {@code Link}-header pagination.
 * @param bodyJson   parsed body when the response was JSON; {@code null} otherwise.
 * @param bodyText   raw body text; always populated.
 */
public record ScmHttpResponse(
        int statusCode,
        Map<String, List<String>> headers,
        JsonNode bodyJson,
        String bodyText) {

    public boolean isSuccessful() {
        return statusCode >= 200 && statusCode < 300;
    }

    /** @return first value of a header, matched case-insensitively, or {@code null}. */
    public String header(String name) {
        if (headers == null || name == null) {
            return null;
        }
        List<String> values = headers.get(name.toLowerCase());
        return values == null || values.isEmpty() ? null : values.get(0);
    }
}
