package com.kksg.applicationServices.scm.provider.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * Typed, validated view over the {@code scm_providers.configuration} JSONB document.
 *
 * <p>The database column is schemaless so that a new provider needs no DDL. Untyped maps are
 * miserable to consume, though, so the engine parses the document once into this structure and
 * works against it. Everything provider-specific that is not an HTTP recipe lives here: base URL,
 * OAuth endpoints, webhook signature scheme, paging style.
 *
 * <p><b>Credentials are referenced, never embedded.</b> {@code clientIdProperty},
 * {@code clientSecretProperty} and {@code secretProperty} hold the <i>name</i> of a Spring
 * configuration property; the value is resolved from the {@code Environment} at runtime. The
 * database therefore contains no client secret and no webhook secret, which keeps a database
 * backup from being a credential dump and lets each environment hold its own OAuth app.
 *
 * <p>Nested as records in a single file on purpose: these are inert data holders that are only ever
 * meaningful together, and eight one-field files would obscure rather than clarify the schema.
 * Defaults are exposed through small accessor methods so that seed JSON can omit anything that has
 * a sensible fallback.
 *
 * @param api        transport-level settings for calling the provider's REST API.
 * @param oauth      authorization-code flow settings used when a user connects the provider.
 * @param webhook    inbound webhook identification and signature verification settings.
 * @param pagination how the provider expresses "there is another page".
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProviderConfiguration(
        Api api,
        OAuth oauth,
        Webhook webhook,
        Pagination pagination) {

    public Api apiOrEmpty() {
        return api != null ? api : new Api(null, null, null, null);
    }

    public OAuth oauthOrEmpty() {
        return oauth != null ? oauth : new OAuth(null, null, null, null, null, null, null, null, null);
    }

    public Webhook webhookOrEmpty() {
        return webhook != null ? webhook : new Webhook(null, null, null, null, null, null, null);
    }

    public Pagination paginationOrEmpty() {
        return pagination != null ? pagination : new Pagination(null, null, null, null, null, null);
    }

    /**
     * @param baseUrl        root of the provider's REST API; every {@code endpoint_template} is
     *                       resolved relative to it. For self-hosted providers this is the only
     *                       value that differs between tenants.
     * @param version        provider API version, surfaced to templates as {@code {{api.version}}}.
     * @param defaultHeaders headers applied to every request for this provider, before
     *                       operation-level headers, which override them.
     * @param authentication how a connection's access token is attached to a request.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Api(
            String baseUrl,
            String version,
            Map<String, String> defaultHeaders,
            Authentication authentication) {

        public Map<String, String> defaultHeadersOrEmpty() {
            return defaultHeaders != null ? defaultHeaders : Map.of();
        }

        public Authentication authenticationOrDefault() {
            return authentication != null ? authentication : Authentication.bearerDefault();
        }
    }

    /**
     * How to present the access token on an API call.
     *
     * @param scheme      presentation style.
     * @param header      header name to write; defaults to {@code Authorization}.
     * @param valuePrefix text placed before the token, e.g. {@code "Bearer "}. Kept explicit
     *                    because providers disagree ({@code Bearer}, {@code token}, {@code Basic}).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Authentication(
            AuthScheme scheme,
            String header,
            String valuePrefix) {

        public static Authentication bearerDefault() {
            return new Authentication(AuthScheme.BEARER, "Authorization", "Bearer ");
        }

        public AuthScheme schemeOrDefault() {
            return scheme != null ? scheme : AuthScheme.BEARER;
        }

        public String headerOrDefault() {
            return header != null && !header.isBlank() ? header : "Authorization";
        }

        public String valuePrefixOrDefault() {
            if (valuePrefix != null) {
                return valuePrefix;
            }
            return schemeOrDefault() == AuthScheme.BEARER ? "Bearer " : "";
        }
    }

    public enum AuthScheme {
        /** {@code Authorization: Bearer <token>} - GitHub, Bitbucket, GitLab all accept this. */
        BEARER,
        /** Token written into a custom header with no prefix, e.g. GitLab's {@code PRIVATE-TOKEN}. */
        HEADER,
        /** No credential attached; used for unauthenticated public endpoints. */
        NONE
    }

    /**
     * @param authorizationUrl     where the user's browser is sent to grant access.
     * @param tokenUrl             where an authorization code is exchanged for tokens.
     * @param scopes               requested scopes, joined with {@code scopeSeparator}.
     * @param scopeSeparator       provider-specific scope delimiter; GitHub uses a space,
     *                             some providers use a comma. Defaults to a space.
     * @param clientIdProperty     name of the config property holding the OAuth client id.
     * @param clientSecretProperty name of the config property holding the OAuth client secret.
     * @param redirectUriProperty  name of the config property holding this provider's callback URI.
     * @param tokenRequest         wire format of the token request.
     * @param supportsRefresh      whether the provider issues refresh tokens. GitHub OAuth Apps
     *                             issue non-expiring tokens and no refresh token; Bitbucket issues
     *                             two-hour tokens that must be refreshed. This flag drives
     *                             {@code ScmTokenService} without naming either provider.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OAuth(
            String authorizationUrl,
            String tokenUrl,
            List<String> scopes,
            String scopeSeparator,
            String clientIdProperty,
            String clientSecretProperty,
            String redirectUriProperty,
            TokenRequest tokenRequest,
            Boolean supportsRefresh) {

        public List<String> scopesOrEmpty() {
            return scopes != null ? scopes : List.of();
        }

        public String scopeSeparatorOrDefault() {
            return scopeSeparator != null && !scopeSeparator.isEmpty() ? scopeSeparator : " ";
        }

        public TokenRequest tokenRequestOrDefault() {
            return tokenRequest != null ? tokenRequest : new TokenRequest(null, null, null);
        }

        public boolean supportsRefreshOrDefault() {
            return Boolean.TRUE.equals(supportsRefresh);
        }
    }

    /**
     * Wire format for the token endpoint. This is the single most common source of provider
     * divergence in OAuth implementations, and expressing it as data is what removes the need for
     * a per-provider token-exchange adapter.
     *
     * @param authStyle where client credentials go: request body or HTTP Basic header.
     * @param encoding  body encoding: form-encoded or JSON.
     * @param accept    {@code Accept} header; GitHub returns form-encoded unless JSON is requested.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TokenRequest(
            ClientAuthStyle authStyle,
            BodyEncoding encoding,
            String accept) {

        public ClientAuthStyle authStyleOrDefault() {
            return authStyle != null ? authStyle : ClientAuthStyle.BODY;
        }

        public BodyEncoding encodingOrDefault() {
            return encoding != null ? encoding : BodyEncoding.FORM;
        }

        public String acceptOrDefault() {
            return accept != null && !accept.isBlank() ? accept : "application/json";
        }
    }

    public enum ClientAuthStyle {
        /** {@code client_id} / {@code client_secret} as body parameters. */
        BODY,
        /** {@code Authorization: Basic base64(client_id:client_secret)}. */
        BASIC
    }

    public enum BodyEncoding {
        FORM,
        JSON
    }

    /**
     * @param signatureAlgorithm  MAC used to sign the payload, or {@code NONE}.
     * @param signatureHeader     header carrying the signature.
     * @param signaturePrefix     text preceding the hex digest, e.g. {@code "sha256="}.
     * @param eventHeader         header carrying the provider's event name.
     * @param deliveryIdHeader    header carrying the provider's unique delivery id, which is the
     *                            basis for idempotency.
     * @param actionPath          body path holding the event's action/sub-type, for providers that
     *                            separate event and action. Null when the event name already
     *                            encodes it.
     * @param secretProperty      name of the config property holding the shared webhook secret.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Webhook(
            SignatureAlgorithm signatureAlgorithm,
            String signatureHeader,
            String signaturePrefix,
            String eventHeader,
            String deliveryIdHeader,
            String actionPath,
            String secretProperty) {

        public SignatureAlgorithm signatureAlgorithmOrDefault() {
            return signatureAlgorithm != null ? signatureAlgorithm : SignatureAlgorithm.NONE;
        }

        public String signaturePrefixOrEmpty() {
            return signaturePrefix != null ? signaturePrefix : "";
        }
    }

    public enum SignatureAlgorithm {
        NONE,
        HMAC_SHA1,
        HMAC_SHA256
    }

    /**
     * @param type             paging style.
     * @param pageParameter    query parameter carrying the page number.
     * @param sizeParameter    query parameter carrying the page size.
     * @param defaultPageSize  size used when the caller does not specify one.
     * @param maxPageSize      provider ceiling; requests above it are clamped so the provider does
     *                         not silently truncate and make {@code hasNext} wrong.
     * @param nextPath         body path to the provider's "next page" indicator, e.g. Bitbucket's
     *                         {@code next}. Absence of the field is what signals the final page.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Pagination(
            PaginationType type,
            String pageParameter,
            String sizeParameter,
            Integer defaultPageSize,
            Integer maxPageSize,
            String nextPath) {

        public PaginationType typeOrDefault() {
            return type != null ? type : PaginationType.NONE;
        }

        public int defaultPageSizeOrDefault() {
            return defaultPageSize != null && defaultPageSize > 0 ? defaultPageSize : 50;
        }

        public int maxPageSizeOrDefault() {
            return maxPageSize != null && maxPageSize > 0 ? maxPageSize : 100;
        }
    }

    public enum PaginationType {
        /** Operation is not paged. */
        NONE,
        /** Numeric page parameter, e.g. {@code ?page=2}. */
        PAGE,
        /** Opaque continuation token supplied by the provider. */
        CURSOR
    }
}
