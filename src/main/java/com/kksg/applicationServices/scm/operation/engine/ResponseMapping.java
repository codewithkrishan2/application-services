package com.kksg.applicationServices.scm.operation.engine;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Typed view over {@code scm_provider_operations.response_mapping}.
 *
 * <p>Turns a provider-shaped response into the platform's normalized shape. This is the boundary
 * that stops GitHub's {@code full_name} and Bitbucket's {@code links.clone[0].href} from reaching
 * Modules 3-8.
 *
 * <p>Example ({@code LIST_REPOSITORIES}, Bitbucket):
 * <pre>{@code
 * {
 *   "type": "LIST",
 *   "itemsPath": "values",
 *   "fields": {
 *     "externalId": "uuid",
 *     "name": "name",
 *     "fullName": "full_name",
 *     "owner": "workspace.slug",
 *     "isPrivate": "is_private",
 *     "cloneUrl": "links.clone[0].href"
 *   },
 *   "transforms": { "externalId": "TO_STRING", "isPrivate": "TO_BOOLEAN" }
 * }
 * }</pre>
 *
 * <p><b>Fallback paths.</b> A {@code fields} value may be a single path or an array of candidate
 * paths, in which case the first that resolves to a non-null value wins. This exists because some
 * providers move a value between locations depending on the case - Bitbucket's diffstat exposes
 * {@code new.path} for additions and {@code old.path} for deletions - and a fallback list handles
 * that without a conditional.
 *
 * @param type          shape of the normalized result.
 * @param itemsPath     path to the array inside a wrapped list response; omit when the body is
 *                      already an array.
 * @param fields        normalized field name to provider path, or to an array of candidate paths.
 * @param transforms    per-field type coercion applied after extraction, keyed by normalized field.
 * @param valueMappings per-field value translation ({@code {"state": {"declined": "CLOSED"}}}),
 *                      applied before transforms and matched case-insensitively.
 * @param defaults      values used when extraction yields nothing, so a normalized model can have a
 *                      sane value for a field the provider simply does not report.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ResponseMapping(
        MappingType type,
        String itemsPath,
        Map<String, Object> fields,
        Map<String, FieldTransform> transforms,
        Map<String, Map<String, String>> valueMappings,
        Map<String, Object> defaults) {

    private static final ResponseMapping RAW =
            new ResponseMapping(MappingType.RAW, null, null, null, null, null);

    /** Mapping used when an operation declares none: the provider body is passed through untouched. */
    public static ResponseMapping raw() {
        return RAW;
    }

    public MappingType typeOrDefault() {
        return type != null ? type : MappingType.RAW;
    }

    public Map<String, FieldTransform> transformsOrEmpty() {
        return transforms != null ? transforms : Map.of();
    }

    public Map<String, Map<String, String>> valueMappingsOrEmpty() {
        return valueMappings != null ? valueMappings : Map.of();
    }

    public Map<String, Object> defaultsOrEmpty() {
        return defaults != null ? defaults : Map.of();
    }

    /**
     * Normalizes the polymorphic {@code fields} document into "normalized name -> ordered candidate
     * paths".
     *
     * <p>Accepting both {@code "name": "full_name"} and {@code "path": ["new.path", "old.path"]}
     * keeps the common case terse while allowing fallbacks, at the cost of this one normalization
     * step. Non-string, non-list values are ignored rather than rejected so that a stray key in a
     * configuration document degrades to a missing field instead of failing every request.
     */
    public Map<String, List<String>> fieldPaths() {
        if (fields == null || fields.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> resolved = new LinkedHashMap<>();
        fields.forEach((normalizedName, pathSpec) -> {
            List<String> candidates = new ArrayList<>();
            if (pathSpec instanceof String single) {
                if (!single.isBlank()) {
                    candidates.add(single);
                }
            } else if (pathSpec instanceof List<?> list) {
                for (Object element : list) {
                    if (element instanceof String candidate && !candidate.isBlank()) {
                        candidates.add(candidate);
                    }
                }
            }
            if (!candidates.isEmpty()) {
                resolved.put(normalizedName, List.copyOf(candidates));
            }
        });
        return resolved;
    }

    /** Shape of the normalized output. */
    public enum MappingType {
        /** Normalize each element of an array (optionally located by {@code itemsPath}). */
        LIST,
        /** Normalize a single object. */
        OBJECT,
        /** Body is not JSON; expose it as text. Used for unified diffs. */
        TEXT,
        /** No normalization; the provider body is returned as-is. */
        RAW
    }

    /**
     * Type coercions available to a mapping.
     *
     * <p>A closed set of named, side-effect-free conversions - not an expression language. Each
     * exists because providers genuinely disagree on representation: numeric vs string identifiers,
     * {@code "true"} vs {@code true}, padded strings.
     */
    public enum FieldTransform {
        /** Render as text. Reconciles numeric ids (GitHub) with UUID strings (Bitbucket). */
        TO_STRING,
        TO_INTEGER,
        TO_BOOLEAN,
        TRIM,
        LOWERCASE,
        UPPERCASE
    }
}
