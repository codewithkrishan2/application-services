package com.kksg.applicationServices.scm.common.util;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Minimal, dependency-free reader for dotted paths inside a Jackson tree.
 *
 * <p>Supported syntax is deliberately tiny:
 * <pre>
 *   name                -&gt; field
 *   owner.login         -&gt; nested field
 *   links.clone[0].href -&gt; array element by index
 *   values              -&gt; array/object node itself
 *   $.values            -&gt; optional leading "$." is ignored
 * </pre>
 *
 * <p><b>Why not JSONPath?</b> A full JSONPath engine brings filters and expression evaluation.
 * Expression evaluation over provider-controlled data is exactly the "arbitrary code from JSON"
 * hazard the module is required to avoid, and it would be another dependency to audit. Field
 * access plus fixed array indices covers every mapping the MVP providers need; anything more
 * complex is a signal that a provider adapter is the right tool.
 *
 * <p>Missing or null segments yield {@code null} rather than throwing, because a provider omitting
 * an optional field is normal and must not fail an entire operation.
 */
public final class JsonNodePaths {

    private JsonNodePaths() {
    }

    /**
     * @return the node at {@code path}, or {@code null} if any segment is absent.
     */
    public static JsonNode at(JsonNode root, String path) {
        if (root == null || path == null || path.isBlank()) {
            return null;
        }

        String normalized = path.trim();
        if (normalized.startsWith("$.")) {
            normalized = normalized.substring(2);
        } else if (normalized.equals("$")) {
            return root;
        }
        if (normalized.isBlank()) {
            return root;
        }

        JsonNode current = root;
        for (String rawSegment : normalized.split("\\.")) {
            if (current == null || current.isNull()) {
                return null;
            }
            current = readSegment(current, rawSegment);
        }
        return current == null || current.isNull() ? null : current;
    }

    /**
     * Resolves a segment that may carry any number of trailing array indices,
     * e.g. {@code clone[0]} or {@code matrix[1][2]}.
     */
    private static JsonNode readSegment(JsonNode current, String rawSegment) {
        String segment = rawSegment;
        int bracket = segment.indexOf('[');

        String fieldName = bracket >= 0 ? segment.substring(0, bracket) : segment;
        JsonNode node = fieldName.isEmpty() ? current : current.get(fieldName);

        while (bracket >= 0 && node != null) {
            int close = segment.indexOf(']', bracket);
            if (close < 0) {
                return null;
            }
            String indexText = segment.substring(bracket + 1, close).trim();
            int index;
            try {
                index = Integer.parseInt(indexText);
            } catch (NumberFormatException ex) {
                return null;
            }
            node = node.isArray() && index >= 0 && index < node.size() ? node.get(index) : null;

            segment = segment.substring(close + 1);
            bracket = segment.indexOf('[');
            if (bracket < 0 && !segment.isBlank()) {
                return null;
            }
        }
        return node;
    }

    /**
     * @return the textual form of the node at {@code path}, or {@code null}. Scalars are rendered
     *         with {@code asText()} so numeric ids become strings without a separate conversion.
     */
    public static String textAt(JsonNode root, String path) {
        JsonNode node = at(root, path);
        if (node == null || node.isNull()) {
            return null;
        }
        return node.isValueNode() ? node.asText() : node.toString();
    }
}
