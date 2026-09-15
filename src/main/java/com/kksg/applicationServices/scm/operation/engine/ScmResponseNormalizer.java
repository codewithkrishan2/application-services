package com.kksg.applicationServices.scm.operation.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.kksg.applicationServices.scm.common.exception.ScmErrorCode;
import com.kksg.applicationServices.scm.common.exception.ScmException;
import com.kksg.applicationServices.scm.common.util.JsonNodePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Applies a {@link ResponseMapping} to a provider response, producing the platform's normalized shape.
 *
 * <p>This is the component that makes Modules 3-8 provider-agnostic. GitHub's
 * <pre>{@code {"id": 123, "full_name": "acme/api", "private": true}}</pre>
 * and Bitbucket's
 * <pre>{@code {"uuid": "{9f...}", "full_name": "acme/api", "is_private": true}}</pre>
 * both leave here as
 * <pre>{@code {"externalId": "...", "fullName": "acme/api", "isPrivate": true}}</pre>
 *
 * <p>Per-field extraction is fail-soft: a path that resolves to nothing yields the mapping's declared
 * default, or is omitted. That is deliberate - providers routinely omit optional fields, and failing
 * an entire pull-request listing because one repository has no description would be a worse outcome
 * than a null description. Structural failures, by contrast, are fatal: if a {@code LIST} mapping's
 * {@code itemsPath} does not resolve to an array, the response is not what the configuration claims
 * and normalizing it would invent data, so it raises
 * {@link ScmErrorCode#SCM_RESPONSE_MAPPING_INVALID}.
 */
@Component
public class ScmResponseNormalizer {

    private static final Logger log = LoggerFactory.getLogger(ScmResponseNormalizer.class);

    private final ObjectMapper objectMapper;

    public ScmResponseNormalizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public JsonNode normalize(ScmHttpResponse response, ResponseMapping mapping, String operationLabel) {
        return switch (mapping.typeOrDefault()) {
            case TEXT -> new TextNode(response.bodyText() != null ? response.bodyText() : "");
            case RAW -> response.bodyJson();
            case OBJECT -> normalizeObjectResponse(response, mapping, operationLabel);
            case LIST -> normalizeListResponse(response, mapping, operationLabel);
        };
    }

    private JsonNode normalizeObjectResponse(ScmHttpResponse response,
                                             ResponseMapping mapping,
                                             String operationLabel) {
        JsonNode body = response.bodyJson();
        if (body == null || body.isNull()) {
            return null;
        }
        JsonNode source = mapping.itemsPath() != null && !mapping.itemsPath().isBlank()
                ? JsonNodePaths.at(body, mapping.itemsPath())
                : body;
        if (source == null || source.isNull()) {
            return null;
        }
        return normalizeElement(source, mapping);
    }

    private JsonNode normalizeListResponse(ScmHttpResponse response,
                                           ResponseMapping mapping,
                                           String operationLabel) {
        JsonNode body = response.bodyJson();
        if (body == null || body.isNull()) {
            return objectMapper.createArrayNode();
        }

        JsonNode itemsNode = mapping.itemsPath() != null && !mapping.itemsPath().isBlank()
                ? JsonNodePaths.at(body, mapping.itemsPath())
                : body;

        if (itemsNode == null || itemsNode.isNull()) {
            // A provider legitimately returns an absent collection for an empty result set.
            return objectMapper.createArrayNode();
        }
        if (!itemsNode.isArray()) {
            log.error("SCM_RESPONSE_MAPPING_SHAPE_MISMATCH: operationCode={}, itemsPath={}, actualNodeType={}",
                    operationLabel, mapping.itemsPath(), itemsNode.getNodeType());
            throw new ScmException(ScmErrorCode.SCM_RESPONSE_MAPPING_INVALID,
                    "%s expected an array at '%s'".formatted(operationLabel,
                            mapping.itemsPath() != null ? mapping.itemsPath() : "$"));
        }

        ArrayNode normalized = objectMapper.createArrayNode();
        for (JsonNode element : itemsNode) {
            normalized.add(normalizeElement(element, mapping));
        }
        return normalized;
    }

    /**
     * Maps one provider object into one normalized object.
     *
     * <p>Public because the webhook pipeline reuses it: a webhook payload needs exactly the same
     * "extract by declared path, translate values, coerce types" treatment as an API response, and the
     * event mapping in {@code scm_provider_events.configuration} is expressed with the same vocabulary.
     * Sharing this method is what keeps webhook normalization from becoming a second, divergent mapping
     * implementation.
     */
    public ObjectNode normalizeObject(JsonNode source, ResponseMapping mapping) {
        return normalizeElement(source, mapping);
    }

    /**
     * Maps one provider object into one normalized object.
     */
    private ObjectNode normalizeElement(JsonNode source, ResponseMapping mapping) {
        Map<String, List<String>> fieldPaths = mapping.fieldPaths();
        if (fieldPaths.isEmpty()) {
            // A mapping with no field declarations means "pass through", which keeps trivial
            // configurations short rather than forcing an identity mapping to be spelled out.
            return source.isObject() ? (ObjectNode) source.deepCopy() : objectMapper.createObjectNode();
        }

        ObjectNode target = objectMapper.createObjectNode();
        Map<String, Object> defaults = mapping.defaultsOrEmpty();

        fieldPaths.forEach((normalizedName, candidatePaths) -> {
            JsonNode extracted = extractFirstPresent(source, candidatePaths);

            if (extracted == null) {
                Object fallback = defaults.get(normalizedName);
                if (fallback != null) {
                    target.set(normalizedName, objectMapper.valueToTree(fallback));
                }
                return;
            }

            JsonNode mapped = applyValueMapping(extracted, mapping.valueMappingsOrEmpty().get(normalizedName));
            JsonNode transformed = applyTransform(mapped, mapping.transformsOrEmpty().get(normalizedName));
            target.set(normalizedName, transformed);
        });

        return target;
    }

    /**
     * Fallback path resolution: first candidate that yields a non-null value wins.
     *
     * <p>Required by providers that relocate a value depending on the case - Bitbucket's diffstat puts
     * a file path in {@code new.path} for additions and {@code old.path} for deletions - and it
     * replaces what would otherwise have to be a conditional.
     */
    private JsonNode extractFirstPresent(JsonNode source, List<String> candidatePaths) {
        for (String path : candidatePaths) {
            JsonNode value = JsonNodePaths.at(source, path);
            if (value != null && !value.isNull()) {
                return value;
            }
        }
        return null;
    }

    /**
     * Translates a provider's vocabulary into the platform's, case-insensitively.
     *
     * <p>Unmapped values pass through unchanged rather than becoming null, so a provider introducing a
     * new state degrades to "unrecognised" at the enum boundary instead of silently losing the field.
     */
    private JsonNode applyValueMapping(JsonNode value, Map<String, String> valueMapping) {
        if (valueMapping == null || valueMapping.isEmpty() || !value.isValueNode()) {
            return value;
        }
        String text = value.asText();
        for (Map.Entry<String, String> entry : valueMapping.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(text)) {
                return new TextNode(entry.getValue());
            }
        }
        return value;
    }

    private JsonNode applyTransform(JsonNode value, ResponseMapping.FieldTransform transform) {
        if (transform == null || value == null || value.isNull()) {
            return value;
        }
        return switch (transform) {
            case TO_STRING -> new TextNode(value.isValueNode() ? value.asText() : value.toString());
            case TO_INTEGER -> toInteger(value);
            case TO_BOOLEAN -> JsonNodeFactory.instance.booleanNode(toBoolean(value));
            case TRIM -> new TextNode(value.asText().trim());
            case LOWERCASE -> new TextNode(value.asText().toLowerCase(Locale.ROOT));
            case UPPERCASE -> new TextNode(value.asText().toUpperCase(Locale.ROOT));
        };
    }

    private JsonNode toInteger(JsonNode value) {
        if (value.isNumber()) {
            return JsonNodeFactory.instance.numberNode(value.asInt());
        }
        try {
            return JsonNodeFactory.instance.numberNode(Integer.parseInt(value.asText().trim()));
        } catch (NumberFormatException ex) {
            // Losing one malformed numeric field is preferable to failing the whole operation.
            log.debug("SCM_TRANSFORM_TO_INTEGER_FAILED: nodeType={}", value.getNodeType());
            return null;
        }
    }

    private boolean toBoolean(JsonNode value) {
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        String text = value.asText().trim();
        return "true".equalsIgnoreCase(text) || "1".equals(text) || "yes".equalsIgnoreCase(text);
    }
}
