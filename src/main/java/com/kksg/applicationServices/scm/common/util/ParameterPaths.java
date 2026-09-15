package com.kksg.applicationServices.scm.common.util;

import java.util.List;
import java.util.Map;

/**
 * Dotted-path reader for the caller-supplied parameter map of an SCM operation.
 *
 * <p>Callers may pass either flat parameters ({@code owner=acme}) or structured ones
 * ({@code repository={owner=acme, slug=api}}), and a {@code request_configuration} may reference
 * either style ({@code {{owner}}} or {@code {{repository.owner}}}). Supporting both keeps seed
 * configuration readable without forcing every caller to flatten its inputs.
 *
 * <p>A flat key containing dots is checked first, so a parameter literally named
 * {@code "repository.owner"} still resolves and never silently disappears.
 */
public final class ParameterPaths {

    private ParameterPaths() {
    }

    public static Object get(Map<String, Object> parameters, String path) {
        if (parameters == null || path == null || path.isBlank()) {
            return null;
        }
        String normalized = path.trim();

        if (parameters.containsKey(normalized)) {
            return parameters.get(normalized);
        }

        Object current = parameters;
        for (String segment : normalized.split("\\.")) {
            current = readSegment(current, segment);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    private static Object readSegment(Object current, String rawSegment) {
        String segment = rawSegment;
        int bracket = segment.indexOf('[');
        String key = bracket >= 0 ? segment.substring(0, bracket) : segment;

        Object value;
        if (key.isEmpty()) {
            value = current;
        } else if (current instanceof Map<?, ?> map) {
            value = ((Map<String, Object>) map).get(key);
        } else {
            return null;
        }

        while (bracket >= 0 && value != null) {
            int close = segment.indexOf(']', bracket);
            if (close < 0) {
                return null;
            }
            int index;
            try {
                index = Integer.parseInt(segment.substring(bracket + 1, close).trim());
            } catch (NumberFormatException ex) {
                return null;
            }
            if (value instanceof List<?> list) {
                value = index >= 0 && index < list.size() ? list.get(index) : null;
            } else {
                return null;
            }
            segment = segment.substring(close + 1);
            bracket = segment.indexOf('[');
        }
        return value;
    }

    /**
     * @return {@code null} when absent, otherwise the value rendered as a string. Used for URL and
     *         query-parameter substitution, where every value ends up textual anyway.
     */
    public static String getAsString(Map<String, Object> parameters, String path) {
        Object value = get(parameters, path);
        return value == null ? null : String.valueOf(value);
    }
}
