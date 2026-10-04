package com.kksg.applicationServices.scm.operation.engine;

import com.kksg.applicationServices.scm.common.exception.ScmErrorCode;
import com.kksg.applicationServices.scm.common.exception.ScmException;
import com.kksg.applicationServices.scm.common.model.ScmOperationRequest;
import com.kksg.applicationServices.scm.common.util.ParameterPaths;
import com.kksg.applicationServices.scm.common.util.PlaceholderResolver;
import com.kksg.applicationServices.scm.provider.config.ProviderConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Turns a normalized operation request plus provider configuration into a concrete
 * {@link ScmHttpRequest}.
 *
 * <p>This is where "LIST_REPOSITORIES" becomes {@code GET https://api.github.com/user/repos?page=1}
 * or {@code GET https://api.bitbucket.org/2.0/repositories?page=1&pagelen=50}. It contains no
 * provider name and no provider-specific branch; every difference comes from the two JSONB documents.
 *
 * <p>The pipeline, in order:
 * <ol>
 *   <li>validate required parameters - fail before any network call;</li>
 *   <li>apply paging defaults and clamp page size to the provider ceiling;</li>
 *   <li>resolve {@code pathParams} aliases and merge them into the effective parameters;</li>
 *   <li>resolve the endpoint template, URL-encoding substituted values;</li>
 *   <li>append query parameters, silently dropping any whose placeholders are unresolved;</li>
 *   <li>layer headers: provider defaults, then operation headers, then authentication;</li>
 *   <li>shape the body from {@code bodyTemplate}, if any.</li>
 * </ol>
 */
@Component
public class ScmRequestBuilder {

    private static final Logger log = LoggerFactory.getLogger(ScmRequestBuilder.class);

    /** Exposes the caller's normalized body to {@code bodyTemplate} as {@code {{body.*}}}. */
    private static final String BODY_PARAMETER = "body";

    /**
     * Builds the request and returns it together with the effective parameter map, which the caller
     * needs for the operation context and for building the next page's request.
     */
    public BuiltRequest build(ScmOperationRequest request,
                             ProviderConfiguration configuration,
                             com.kksg.applicationServices.scm.operation.service.ResolvedOperation resolved,
                             String baseUrl,
                             String accessToken) {

        RequestConfiguration requestConfiguration = resolved.requestConfiguration();
        String operationLabel = String.valueOf(resolved.operation().getOperationCode());

        Map<String, Object> parameters = new LinkedHashMap<>(request.getParameters());
        if (request.getBody() != null) {
            parameters.put(BODY_PARAMETER, request.getBody());
        }

        validateRequiredParameters(requestConfiguration, parameters, operationLabel);
        applyPagingDefaults(requestConfiguration, configuration, parameters);
        applyPathAliases(requestConfiguration, parameters, operationLabel);

        String resolvedPath = PlaceholderResolver.resolveRequired(
                resolved.operation().getEndpointTemplate(), encodeForPath(parameters), operationLabel);

        String uri = buildUri(baseUrl, resolvedPath, requestConfiguration, parameters);
        Map<String, String> headers = buildHeaders(configuration, requestConfiguration, parameters, accessToken);
        Object body = buildBody(requestConfiguration, parameters, request.getBody());

        boolean expectText = resolved.responseMapping().typeOrDefault() == ResponseMapping.MappingType.TEXT;
        HttpMethod method = resolveMethod(resolved.operation().getHttpMethod(), operationLabel);

        log.debug("SCM_REQUEST_BUILT: operationCode={}, method={}, uri={}", operationLabel, method, uri);
        return new BuiltRequest(ScmHttpRequest.of(method, uri, headers, body, expectText), parameters);
    }

    private void validateRequiredParameters(RequestConfiguration configuration,
                                            Map<String, Object> parameters,
                                            String operationLabel) {
        List<String> missing = new ArrayList<>();
        for (String required : configuration.requiredParametersOrEmpty()) {
            if (ParameterPaths.get(parameters, required) == null) {
                missing.add(required);
            }
        }
        if (!missing.isEmpty()) {
            throw new ScmException(ScmErrorCode.SCM_OPERATION_PARAMETER_MISSING,
                    "%s requires parameter(s) %s".formatted(operationLabel, missing));
        }
    }

    /**
     * Fills in paging parameters the caller omitted and clamps the page size.
     *
     * <p>Clamping matters for correctness, not just politeness: a provider asked for more than its
     * maximum silently returns its maximum, so an un-clamped caller would compare the returned count
     * against the requested count and wrongly conclude there are no further pages.
     */
    private void applyPagingDefaults(RequestConfiguration requestConfiguration,
                                     ProviderConfiguration configuration,
                                     Map<String, Object> parameters) {
        if (!requestConfiguration.isPaginated()) {
            return;
        }
        ProviderConfiguration.Pagination pagination = configuration.paginationOrEmpty();
        if (pagination.typeOrDefault() == ProviderConfiguration.PaginationType.NONE) {
            return;
        }

        Object requestedSize = parameters.get(ScmOperationRequest.PARAM_PAGE_SIZE);
        int pageSize = requestedSize != null
                ? parsePositiveInt(requestedSize, pagination.defaultPageSizeOrDefault())
                : pagination.defaultPageSizeOrDefault();
        parameters.put(ScmOperationRequest.PARAM_PAGE_SIZE, Math.min(pageSize, pagination.maxPageSizeOrDefault()));

        if (pagination.typeOrDefault() == ProviderConfiguration.PaginationType.PAGE
                && parameters.get(ScmOperationRequest.PARAM_PAGE) == null) {
            parameters.put(ScmOperationRequest.PARAM_PAGE, 1);
        }
    }

    /**
     * Resolves {@code pathParams} aliases so an endpoint template can use its own vocabulary.
     *
     * <p>Lets a provider whose URLs speak of a "workspace" declare
     * {@code "workspace": "{{owner}}"} and keep using {@code {{workspace}}} in its template, while
     * callers everywhere pass the same {@code owner} parameter.
     */
    private void applyPathAliases(RequestConfiguration requestConfiguration,
                                  Map<String, Object> parameters,
                                  String operationLabel) {
        Map<String, String> aliases = requestConfiguration.pathParamsOrEmpty();
        if (aliases.isEmpty()) {
            return;
        }
        Map<String, Object> resolvedAliases = new LinkedHashMap<>();
        aliases.forEach((name, template) ->
                PlaceholderResolver.resolveOptional(template, parameters)
                        .filter(value -> !value.isBlank())
                        .ifPresent(value -> resolvedAliases.put(name, value)));

        // Aliases must not shadow an explicit caller parameter of the same name.
        resolvedAliases.forEach(parameters::putIfAbsent);
        log.trace("SCM_PATH_ALIASES_RESOLVED: operationCode={}, aliases={}", operationLabel, resolvedAliases.keySet());
    }

    private String buildUri(String baseUrl,
                            String resolvedPath,
                            RequestConfiguration requestConfiguration,
                            Map<String, Object> parameters) {
        String normalizedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String normalizedPath = resolvedPath.startsWith("/") ? resolvedPath : "/" + resolvedPath;

        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(normalizedBase + normalizedPath);
        requestConfiguration.queryParamsOrEmpty().forEach((name, template) -> {
            Optional<String> value = PlaceholderResolver.resolveOptional(template, parameters);
            // An unresolved placeholder means "caller did not supply this"; omitting the parameter is
            // the correct behaviour and is what makes optional paging work without extra flags.
            value.filter(resolved -> !resolved.isBlank())
                    .ifPresent(resolved -> builder.queryParam(name, resolved));
        });
        return builder.encode(StandardCharsets.UTF_8).build().toUriString();
    }

    /**
     * Layers headers so the most specific wins: provider defaults, then operation-level headers,
     * then authentication last so a configuration document cannot accidentally overwrite the
     * credential header with a stale literal.
     */
    private Map<String, String> buildHeaders(ProviderConfiguration configuration,
                                             RequestConfiguration requestConfiguration,
                                             Map<String, Object> parameters,
                                             String accessToken) {
        Map<String, String> headers = new LinkedHashMap<>(configuration.apiOrEmpty().defaultHeadersOrEmpty());

        requestConfiguration.headersOrEmpty().forEach((name, template) ->
                PlaceholderResolver.resolveOptional(template, parameters)
                        .ifPresent(value -> headers.put(name, sanitizeHeaderValue(name, value))));

        ProviderConfiguration.Authentication authentication = configuration.apiOrEmpty().authenticationOrDefault();
        if (accessToken != null && !accessToken.isBlank()
                && authentication.schemeOrDefault() != ProviderConfiguration.AuthScheme.NONE) {
            headers.put(authentication.headerOrDefault(), authentication.valuePrefixOrDefault() + accessToken);
        }
        return headers;
    }

    /**
     * Refuses a header value containing a line break.
     *
     * <p>Header templates are resolved from the same caller-supplied parameter map as paths and bodies, so a
     * value with CR or LF in it would be written into an outbound request header. Depending on the client,
     * that either errors or injects additional headers into the request - the classic header-splitting
     * primitive. Rejecting rather than stripping keeps the failure visible: silently altering a credential
     * or content header would be worse than refusing the call.
     */
    private String sanitizeHeaderValue(String name, String value) {
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new ScmException(ScmErrorCode.SCM_OPERATION_PARAMETER_MISSING,
                    "header '%s' resolved to a value containing a line break".formatted(name));
        }
        return value;
    }

    private Object buildBody(RequestConfiguration requestConfiguration,
                             Map<String, Object> parameters,
                             Object callerBody) {
        Object template = requestConfiguration.bodyTemplate();
        if (template == null) {
            // No template: pass the caller's normalized body straight through.
            return callerBody;
        }
        return resolveBodyTemplate(template, parameters);
    }

    /**
     * Recursively substitutes placeholders inside a body template.
     *
     * <p>A string whose placeholders cannot be resolved yields {@code null} and the enclosing key is
     * dropped, so optional body fields need no separate declaration. This is what allows one
     * normalized "post a comment" request to become {@code {"body": "..."}} for one provider and
     * {@code {"content": {"raw": "..."}}} for another.
     */
    private Object resolveBodyTemplate(Object template, Map<String, Object> parameters) {
        if (template instanceof String text) {
            return PlaceholderResolver.containsPlaceholder(text)
                    ? PlaceholderResolver.resolveOptional(text, parameters).orElse(null)
                    : text;
        }
        if (template instanceof Map<?, ?> map) {
            Map<String, Object> resolved = new LinkedHashMap<>();
            map.forEach((key, value) -> {
                Object resolvedValue = resolveBodyTemplate(value, parameters);
                if (resolvedValue != null) {
                    resolved.put(String.valueOf(key), resolvedValue);
                }
            });
            return resolved;
        }
        if (template instanceof List<?> list) {
            List<Object> resolved = new ArrayList<>(list.size());
            for (Object element : list) {
                Object resolvedValue = resolveBodyTemplate(element, parameters);
                if (resolvedValue != null) {
                    resolved.add(resolvedValue);
                }
            }
            return resolved;
        }
        return template;
    }

    /**
     * Produces a parameter view whose values are safe to paste into a URL path.
     *
     * <p>Encoding happens before substitution rather than after, because encoding the assembled path
     * would also escape the template's own separators. Path-segment encoding means a value containing
     * {@code /} becomes {@code %2F} instead of silently introducing a new path segment - which would
     * otherwise let a crafted repository name redirect a request to a different endpoint.
     */
    private Map<String, Object> encodeForPath(Map<String, Object> parameters) {
        Map<String, Object> encoded = new LinkedHashMap<>();
        parameters.forEach((key, value) -> {
            if (value instanceof String text) {
                rejectDotSegment(key, text);
                encoded.put(key, UriUtils.encodePathSegment(text, StandardCharsets.UTF_8));
            } else {
                encoded.put(key, value);
            }
        });
        return encoded;
    }

    /**
     * Rejects {@code .} and {@code ..} as path parameter values.
     *
     * <p>Percent-encoding a path segment does not help here: {@code .} is an unreserved character, so a
     * value of {@code ..} passes through untouched. Neither this client nor {@code HttpURLConnection}
     * normalizes dot segments, so the provider's own server resolves them - meaning a repository named
     * {@code ..} would turn {@code /repos/{{owner}}/{{repo}}/pulls} into a request one level up the path,
     * against an endpoint the caller was never authorized for. The host cannot be changed this way, which
     * bounds the impact, but a parameter should not be able to move the request at all.
     */
    private void rejectDotSegment(String parameterName, String value) {
        String trimmed = value.trim();
        if (".".equals(trimmed) || "..".equals(trimmed)) {
            throw new ScmException(ScmErrorCode.SCM_OPERATION_PARAMETER_MISSING,
                    "parameter '%s' has an invalid value".formatted(parameterName));
        }
    }

    private HttpMethod resolveMethod(String httpMethod, String operationLabel) {
        if (httpMethod == null || httpMethod.isBlank()) {
            throw new ScmException(ScmErrorCode.SCM_PROVIDER_CONFIGURATION_INVALID,
                    "%s has no http_method".formatted(operationLabel));
        }
        return HttpMethod.valueOf(httpMethod.trim().toUpperCase(java.util.Locale.ROOT));
    }

    private int parsePositiveInt(Object value, int fallback) {
        try {
            int parsed = Integer.parseInt(String.valueOf(value));
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    /**
     * The built request together with the effective parameters it was built from.
     *
     * @param request    ready to send.
     * @param parameters caller parameters plus paging defaults and resolved aliases; carried forward
     *                   so the operation context and pagination reflect what was actually sent.
     */
    public record BuiltRequest(ScmHttpRequest request, Map<String, Object> parameters) {
    }
}
