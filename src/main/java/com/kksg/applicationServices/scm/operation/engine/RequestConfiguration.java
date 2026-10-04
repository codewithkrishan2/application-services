package com.kksg.applicationServices.scm.operation.engine;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * Typed view over {@code scm_provider_operations.request_configuration}.
 *
 * <p>Describes, declaratively, how to turn a normalized {@code ScmOperationRequest} into a concrete
 * HTTP request. Every value may contain {@code {{placeholder}}} tokens resolved against the caller's
 * parameters.
 *
 * <p>The vocabulary is deliberately closed - headers, query parameters, path aliases, a body shape,
 * and a paging flag. There is no expression language, no conditional and no scripting, because this
 * document is loaded from the database and must not be able to express behaviour. A wrong
 * configuration can produce a wrong request; it can never run code.
 *
 * <p>Example ({@code LIST_REPOSITORIES} for a page-based provider):
 * <pre>{@code
 * {
 *   "queryParams": { "page": "{{page}}", "per_page": "{{pageSize}}", "affiliation": "owner" },
 *   "paginated": true
 * }
 * }</pre>
 *
 * @param headers           per-operation headers; override provider {@code defaultHeaders}. Used for
 *                          things like requesting a diff media type.
 * @param queryParams       query string entries. A value whose placeholders cannot all be resolved
 *                          is dropped, which is what makes optional paging parameters work without
 *                          a separate "optional" flag.
 * @param pathParams        aliases resolved before the endpoint template, letting a provider adapt
 *                          caller parameter names to its own URL vocabulary - e.g.
 *                          {@code {"workspace": "{{owner}}"}} so the template can say
 *                          {@code {{workspace}}}.
 * @param bodyTemplate      request body shape for write operations, as a nested JSON structure with
 *                          placeholders. This is what lets one normalized "post a PR comment" call
 *                          become {@code {"body": "..."}} for one provider and
 *                          {@code {"content": {"raw": "..."}}} for another with no Java branch.
 * @param paginated         whether paging parameters and next-page detection apply.
 * @param requiredParameters parameters that must be present, checked before any network call so a
 *                          caller mistake surfaces as a 400 rather than a provider error.
 * @param parameterValueMappings translation from a <b>normalized</b> parameter value to the value this
 *                          provider expects, keyed by parameter name then by normalized value -
 *                          {@code {"state": {"OPEN": "open", "ALL": "all"}}}. The request-side
 *                          counterpart of {@code response_mapping.valueMappings}, and the reason a
 *                          caller can filter pull requests by the canonical {@code OPEN} without
 *                          knowing that one provider spells it {@code open} and another {@code OPEN}.
 *                          Matched case-insensitively. A value with no entry is passed through
 *                          unchanged, so a provider needing no translation declares nothing.
 * @param multiValueQueryParams query parameters whose resolved value may expand into <b>repeated</b>
 *                          entries, split on {@value #MULTI_VALUE_DELIMITER}. Needed because some
 *                          providers express "any state" as {@code ?state=A&state=B&state=C} rather
 *                          than a single {@code all} token. Opt-in per parameter rather than applied
 *                          everywhere, so a search term that happens to contain the delimiter is never
 *                          silently torn into pieces.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RequestConfiguration(
        Map<String, String> headers,
        Map<String, String> queryParams,
        Map<String, String> pathParams,
        Object bodyTemplate,
        Boolean paginated,
        List<String> requiredParameters,
        Map<String, Map<String, String>> parameterValueMappings,
        List<String> multiValueQueryParams) {

    /**
     * Separator for {@link #multiValueQueryParams}.
     *
     * <p>A vertical bar because it cannot occur in any provider's state, role or sort enumeration, so
     * splitting on it cannot corrupt a legitimate single value.
     */
    public static final String MULTI_VALUE_DELIMITER = "|";

    private static final RequestConfiguration EMPTY =
            new RequestConfiguration(null, null, null, null, null, null, null, null);

    public static RequestConfiguration empty() {
        return EMPTY;
    }

    public Map<String, String> headersOrEmpty() {
        return headers != null ? headers : Map.of();
    }

    public Map<String, String> queryParamsOrEmpty() {
        return queryParams != null ? queryParams : Map.of();
    }

    public Map<String, String> pathParamsOrEmpty() {
        return pathParams != null ? pathParams : Map.of();
    }

    public List<String> requiredParametersOrEmpty() {
        return requiredParameters != null ? requiredParameters : List.of();
    }

    public Map<String, Map<String, String>> parameterValueMappingsOrEmpty() {
        return parameterValueMappings != null ? parameterValueMappings : Map.of();
    }

    public List<String> multiValueQueryParamsOrEmpty() {
        return multiValueQueryParams != null ? multiValueQueryParams : List.of();
    }

    public boolean isPaginated() {
        return Boolean.TRUE.equals(paginated);
    }
}
