package com.kksg.applicationServices.identity.service.oauth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kksg.applicationServices.common.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The reusable body of an authorization-code sign-in flow.
 *
 * <p>The authorization-code grant is a standard, and the parts providers actually disagree on are
 * narrow enough to express as two configuration choices rather than two code paths:
 * <ul>
 *   <li><b>Where the client credentials go.</b> {@link ClientAuthStyle#BASIC} puts them in an HTTP
 *       Basic header (Bitbucket requires this); {@link ClientAuthStyle#BODY} puts them in the request
 *       body (GitHub's documented style).</li>
 *   <li><b>How the body is encoded.</b> {@link BodyEncoding#FORM} sends
 *       {@code application/x-www-form-urlencoded} (the OAuth 2.0 default, required by Bitbucket);
 *       {@link BodyEncoding#JSON} sends a JSON object.</li>
 * </ul>
 * Subclasses declare those choices plus their endpoints, and implement only
 * {@link #fetchUserProfile(OAuthTokenSet)}, because account payloads are genuinely provider-shaped and
 * not worth forcing into a mapping language here.
 *
 * <p>Response parsing deliberately stays on {@code Map} rather than per-provider DTOs: these payloads
 * are read once, three fields deep, and a typed binding would fail closed on any unexpected extra
 * field a provider adds.
 */
public abstract class AbstractOAuthLoginProvider implements OAuthLoginProvider {

    /** Only ever used to read a small error body off a failed response; needs no application config. */
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
            new ParameterizedTypeReference<>() {
            };

    private static final ParameterizedTypeReference<List<Map<String, Object>>> JSON_ARRAY =
            new ParameterizedTypeReference<>() {
            };

    /** Guards against a slow or hanging provider holding a request thread for the default (infinite) time. */
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 20_000;

    /** Where a provider expects the OAuth client credentials on the token request. */
    protected enum ClientAuthStyle {
        /** {@code Authorization: Basic base64(clientId:clientSecret)}. */
        BASIC,
        /** {@code client_id} and {@code client_secret} as body parameters. */
        BODY
    }

    /** How a provider expects the token request body to be encoded. */
    protected enum BodyEncoding {
        FORM,
        JSON
    }

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final RestTemplate restTemplate;

    protected AbstractOAuthLoginProvider() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        requestFactory.setReadTimeout(READ_TIMEOUT_MS);
        this.restTemplate = new RestTemplate(requestFactory);
    }

    // ---------------------------------------------------------------------
    // Provider-supplied configuration
    // ---------------------------------------------------------------------

    protected abstract String authorizationUrl();

    protected abstract String tokenUrl();

    /**
     * @return the space-separated scopes to request, or {@code null} when the provider derives scopes
     *         from the registered application rather than the authorization request (Bitbucket does).
     */
    protected abstract String scope();

    protected abstract ClientAuthStyle clientAuthStyle();

    protected abstract BodyEncoding tokenRequestEncoding();

    protected abstract String clientId();

    protected abstract String clientSecret();

    /**
     * @return the callback URL registered with the provider, or {@code null}/blank to omit the parameter
     *         and let the provider fall back to the one configured on the application.
     */
    protected abstract String redirectUri();

    // ---------------------------------------------------------------------
    // Shared flow
    // ---------------------------------------------------------------------

    @Override
    public String buildAuthorizationUrl(String state) {
        validateConfiguration();

        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(authorizationUrl())
                .queryParam("client_id", clientId())
                .queryParam("response_type", "code")
                .queryParam("state", state);

        if (hasText(redirectUri())) {
            builder.queryParam("redirect_uri", redirectUri());
        }
        if (hasText(scope())) {
            builder.queryParam("scope", scope());
        }

        return builder.build().encode().toUriString();
    }

    @Override
    public OAuthTokenSet exchangeAuthorizationCode(String code) {
        validateConfiguration();

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("grant_type", "authorization_code");
        parameters.put("code", code);
        if (hasText(redirectUri())) {
            parameters.put("redirect_uri", redirectUri());
        }

        if (clientAuthStyle() == ClientAuthStyle.BASIC) {
            headers.setBasicAuth(clientId(), clientSecret());
        } else {
            parameters.put("client_id", clientId());
            parameters.put("client_secret", clientSecret());
        }

        HttpEntity<?> entity = tokenRequestEncoding() == BodyEncoding.FORM
                ? formEntity(parameters, headers)
                : jsonEntity(parameters, headers);

        return parseTokenResponse(postTokenRequest(tokenUrl(), entity));
    }

    // ---------------------------------------------------------------------
    // Helpers for subclasses
    // ---------------------------------------------------------------------

    /** Performs an authenticated {@code GET} returning a JSON object. */
    protected Map<String, Object> getJsonObject(String url, String accessToken, String resource) {
        ResponseEntity<Map<String, Object>> response =
                execute(url, HttpMethod.GET, bearerEntity(accessToken), JSON_OBJECT, resource);

        Map<String, Object> body = response.getBody();
        if (body == null) {
            throw new ApiException(displayName() + " returned an empty " + resource);
        }
        return body;
    }

    /** Performs an authenticated {@code GET} returning a JSON array of objects. */
    protected List<Map<String, Object>> getJsonArray(String url, String accessToken, String resource) {
        ResponseEntity<List<Map<String, Object>>> response =
                execute(url, HttpMethod.GET, bearerEntity(accessToken), JSON_ARRAY, resource);

        List<Map<String, Object>> body = response.getBody();
        return body == null ? List.of() : body;
    }

    /**
     * Narrows an untyped JSON value to a list of objects, dropping anything that is not an object.
     *
     * <p>Needed because some providers wrap collections in a pagination envelope - Bitbucket returns
     * {@code {"values": [...]}} where GitHub returns a bare array - so the collection arrives as an
     * {@code Object} read out of a map rather than as a typed response body.
     */
    @SuppressWarnings("unchecked")
    protected static List<Map<String, Object>> asJsonObjects(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item)
                .toList();
    }

    /** Reads a top-level string field, tolerating non-string JSON types such as numeric ids. */
    protected static String stringField(Map<String, Object> source, String key) {
        Object value = source == null ? null : source.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    /**
     * Reads a nested string field by walking object keys, for example
     * {@code nestedStringField(account, "links", "avatar", "href")}. Returns {@code null} if any step is
     * absent, which is the right outcome for optional decoration such as an avatar.
     */
    protected static String nestedStringField(Map<String, Object> source, String... path) {
        Object current = source;
        for (int i = 0; i < path.length; i++) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(path[i]);
        }
        if (current == null) {
            return null;
        }
        String text = String.valueOf(current);
        return text.isBlank() ? null : text;
    }

    /** Reads a boolean flag, treating absence as {@code false}. */
    protected static boolean booleanField(Map<String, Object> source, String key) {
        return source != null && Boolean.TRUE.equals(source.get(key));
    }

    protected static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    protected Logger log() {
        return log;
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    /**
     * Fails before the browser is sent anywhere when credentials are missing, so a misconfigured
     * environment produces a clear message instead of an opaque error page on the provider's site.
     */
    private void validateConfiguration() {
        if (!hasText(clientId())) {
            throw new ApiException(displayName() + " sign-in is not configured: missing client ID");
        }
        if (!hasText(clientSecret())) {
            throw new ApiException(displayName() + " sign-in is not configured: missing client secret");
        }
    }

    private HttpEntity<MultiValueMap<String, String>> formEntity(Map<String, String> parameters, HttpHeaders headers) {
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        parameters.forEach(form::add);
        return new HttpEntity<>(form, headers);
    }

    private HttpEntity<Map<String, String>> jsonEntity(Map<String, String> parameters, HttpHeaders headers) {
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(parameters, headers);
    }

    private HttpEntity<Void> bearerEntity(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        return new HttpEntity<>(headers);
    }

    /**
     * Posts the token request, reading the response body whether or not the status is successful.
     *
     * <p>Providers disagree on how they signal a rejected code: GitHub answers HTTP 200 with an
     * {@code error} field, Bitbucket answers HTTP 400 with the same body shape. Treating the 400 as a
     * transport failure would discard the one field that says why - so the body is parsed in both cases and
     * the single interpretation in {@link #parseTokenResponse(Map)} covers both providers.
     */
    private Map<String, Object> postTokenRequest(String url, HttpEntity<?> entity) {
        try {
            ResponseEntity<Map<String, Object>> response =
                    restTemplate.exchange(url, HttpMethod.POST, entity, JSON_OBJECT);

            Map<String, Object> body = response.getBody();
            if (body == null) {
                throw new ApiException(displayName() + " returned an empty token response");
            }
            return body;

        } catch (HttpStatusCodeException ex) {
            Map<String, Object> body = readJsonObject(ex.getResponseBodyAsString());
            if (body == null) {
                // No parseable body, so the status is the only signal worth recording. The body is not
                // logged: on some providers it echoes the submitted code.
                log.error("OAUTH_TOKEN_ENDPOINT_ERROR: provider={}, status={}",
                        provider(), ex.getStatusCode().value());
                throw new ApiException("Failed to exchange the " + displayName() + " authorization code");
            }
            return body;

        } catch (RestClientException ex) {
            log.error("OAUTH_PROVIDER_CALL_FAILED: provider={}, resource=token exchange, cause={}",
                    provider(), ex.getClass().getSimpleName(), ex);
            throw new ApiException("Unable to reach " + displayName() + " for token exchange", ex);
        }
    }

    private <T> ResponseEntity<T> execute(String url,
                                          HttpMethod method,
                                          HttpEntity<?> entity,
                                          ParameterizedTypeReference<T> responseType,
                                          String resource) {
        try {
            return restTemplate.exchange(url, method, entity, responseType);

        } catch (HttpStatusCodeException ex) {
            // Distinguished from a connectivity failure: the provider answered, it just refused. The status
            // is the useful detail, typically 401 for a revoked token or 403 for a missing scope.
            log.error("OAUTH_PROVIDER_REJECTED: provider={}, resource={}, status={}",
                    provider(), resource, ex.getStatusCode().value());
            throw new ApiException(displayName() + " refused to return the " + resource, ex);

        } catch (RestClientException ex) {
            // The provider's own body can echo the submitted code or token, so only the class is logged.
            log.error("OAUTH_PROVIDER_CALL_FAILED: provider={}, resource={}, cause={}",
                    provider(), resource, ex.getClass().getSimpleName(), ex);
            throw new ApiException("Unable to reach " + displayName() + " for " + resource, ex);
        }
    }

    private Map<String, Object> readJsonObject(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    /**
     * Both GitHub and Bitbucket answer a rejected token request with HTTP 200 and an {@code error} field
     * rather than an error status, so the body has to be inspected explicitly.
     */
    private OAuthTokenSet parseTokenResponse(Map<String, Object> body) {
        if (body.containsKey("error")) {
            String error = stringField(body, "error");
            log.warn("OAUTH_TOKEN_EXCHANGE_REJECTED: provider={}, error={}, description={}",
                    provider(), error, stringField(body, "error_description"));

            // Separated because the remedies differ and only one is the user's to act on: a bad code means
            // retry, a rejected client means an operator has to fix the registered credentials.
            if ("invalid_client".equals(error) || "unauthorized_client".equals(error)) {
                throw new ApiException(displayName() + " rejected this application's credentials."
                        + " Please contact support.");
            }
            throw new ApiException("Failed to exchange the " + displayName() + " authorization code");
        }

        String accessToken = stringField(body, "access_token");
        if (!hasText(accessToken)) {
            log.warn("OAUTH_TOKEN_EXCHANGE_REJECTED: provider={}, reason=no_access_token", provider());
            throw new ApiException("Failed to exchange the " + displayName() + " authorization code");
        }

        return new OAuthTokenSet(
                accessToken,
                stringField(body, "refresh_token"),
                parseExpiresIn(body.get("expires_in")),
                stringField(body, "scope"));
    }

    private Long parseExpiresIn(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            log.warn("OAUTH_TOKEN_EXPIRY_UNPARSEABLE: provider={}", provider());
            return null;
        }
    }
}
