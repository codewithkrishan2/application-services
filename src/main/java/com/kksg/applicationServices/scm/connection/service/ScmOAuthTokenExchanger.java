package com.kksg.applicationServices.scm.connection.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.kksg.applicationServices.scm.common.exception.ScmErrorCode;
import com.kksg.applicationServices.scm.common.exception.ScmException;
import com.kksg.applicationServices.scm.common.http.ScmHttpExecutor;
import com.kksg.applicationServices.scm.common.model.ScmTokenSet;
import com.kksg.applicationServices.scm.operation.engine.ScmHttpRequest;
import com.kksg.applicationServices.scm.operation.engine.ScmHttpResponse;
import com.kksg.applicationServices.scm.provider.config.ProviderConfiguration;
import com.kksg.applicationServices.scm.provider.config.ProviderConfigurationFactory;
import com.kksg.applicationServices.scm.provider.config.ProviderCredentialResolver;
import com.kksg.applicationServices.scm.provider.entity.ScmProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Performs OAuth 2.0 token-endpoint calls for any provider, driven entirely by configuration.
 *
 * <p><b>Why this removes the need for per-provider OAuth code.</b> Providers implement the same
 * authorization-code grant but disagree on packaging, and those disagreements are exactly what
 * usually forces a {@code GitHubOAuthService} plus a {@code BitbucketOAuthService}. Here they are two
 * configuration values:
 * <ul>
 *   <li>{@code tokenRequest.authStyle} - client credentials in the body, or as HTTP Basic. GitHub
 *       accepts the body form; Bitbucket requires Basic.</li>
 *   <li>{@code tokenRequest.encoding} - form-encoded or JSON body.</li>
 * </ul>
 * With those expressed as data, one implementation covers both, and a third provider is a seed row.
 *
 * <p>Also handles the refresh grant, which is why {@code oauth.supportsRefresh} exists: GitHub OAuth
 * App tokens do not expire and no refresh token is issued, whereas Bitbucket tokens last two hours.
 * The flag lets {@code ScmTokenService} behave correctly for both without naming either.
 *
 * <p><b>Logging contract.</b> Authorization codes, client secrets, access tokens and refresh tokens
 * never appear in a log line or an exception message. Failures log the provider code, the HTTP status
 * and the provider's {@code error} field, which is an OAuth-defined enumerated value and not secret.
 */
@Component
public class ScmOAuthTokenExchanger {

    private static final Logger log = LoggerFactory.getLogger(ScmOAuthTokenExchanger.class);

    private final ScmHttpExecutor httpExecutor;
    private final ProviderConfigurationFactory configurationFactory;
    private final ProviderCredentialResolver credentialResolver;

    public ScmOAuthTokenExchanger(ScmHttpExecutor httpExecutor,
                                  ProviderConfigurationFactory configurationFactory,
                                  ProviderCredentialResolver credentialResolver) {
        this.httpExecutor = httpExecutor;
        this.configurationFactory = configurationFactory;
        this.credentialResolver = credentialResolver;
    }

    /**
     * Exchanges an authorization code for tokens.
     *
     * @param code the one-time authorization code from the provider's redirect. Never logged: it is
     *             equivalent to a credential until redeemed.
     */
    public ScmTokenSet exchangeAuthorizationCode(ScmProvider provider, String code) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", credentialResolver.resolveRedirectUri(provider));
        return callTokenEndpoint(provider, form, "authorization_code");
    }

    /**
     * Exchanges a refresh token for a fresh access token.
     */
    public ScmTokenSet refreshAccessToken(ScmProvider provider, String refreshToken) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", refreshToken);
        return callTokenEndpoint(provider, form, "refresh_token");
    }

    private ScmTokenSet callTokenEndpoint(ScmProvider provider, Map<String, String> parameters, String grantType) {
        ProviderConfiguration configuration = configurationFactory.get(provider);
        ProviderConfiguration.OAuth oauth = configuration.oauth();
        if (oauth == null) {
            throw new ScmException(ScmErrorCode.SCM_PROVIDER_CONFIGURATION_INVALID,
                    "providerCode=%s has no oauth configuration".formatted(provider.getProviderCode()));
        }
        ProviderConfiguration.TokenRequest tokenRequest = oauth.tokenRequestOrDefault();

        String clientId = credentialResolver.resolveClientId(provider);
        String clientSecret = credentialResolver.resolveClientSecret(provider);

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(HttpHeaders.ACCEPT, tokenRequest.acceptOrDefault());

        Map<String, String> body = new LinkedHashMap<>(parameters);
        if (tokenRequest.authStyleOrDefault() == ProviderConfiguration.ClientAuthStyle.BASIC) {
            String credentials = Base64.getEncoder().encodeToString(
                    (clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
            headers.put(HttpHeaders.AUTHORIZATION, "Basic " + credentials);
        } else {
            body.put("client_id", clientId);
            body.put("client_secret", clientSecret);
        }

        Object serializedBody;
        if (tokenRequest.encodingOrDefault() == ProviderConfiguration.BodyEncoding.FORM) {
            headers.put(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE);
            serializedBody = formEncode(body);
        } else {
            headers.put(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
            serializedBody = body;
        }

        ScmHttpResponse response = httpExecutor.execute(
                ScmHttpRequest.of(HttpMethod.POST, oauth.tokenUrl(), headers, serializedBody, false));

        return parseTokenResponse(provider, response, grantType);
    }

    private ScmTokenSet parseTokenResponse(ScmProvider provider, ScmHttpResponse response, String grantType) {
        JsonNode body = response.bodyJson();

        if (!response.isSuccessful() || body == null) {
            log.warn("SCM_OAUTH_TOKEN_EXCHANGE_FAILED: providerCode={}, grantType={}, status={}",
                    provider.getProviderCode(), grantType, response.statusCode());
            throw new ScmException(ScmErrorCode.SCM_OAUTH_EXCHANGE_FAILED,
                    "providerCode=%s status=%d".formatted(provider.getProviderCode(), response.statusCode()));
        }

        // Providers frequently answer 200 with an error document rather than an error status.
        if (body.hasNonNull("error")) {
            log.warn("SCM_OAUTH_TOKEN_EXCHANGE_REJECTED: providerCode={}, grantType={}, error={}",
                    provider.getProviderCode(), grantType, body.get("error").asText());
            throw new ScmException(ScmErrorCode.SCM_OAUTH_EXCHANGE_FAILED,
                    "providerCode=%s".formatted(provider.getProviderCode()));
        }

        String accessToken = body.path("access_token").asText(null);
        if (accessToken == null || accessToken.isBlank()) {
            log.warn("SCM_OAUTH_TOKEN_MISSING: providerCode={}, grantType={}", provider.getProviderCode(), grantType);
            throw new ScmException(ScmErrorCode.SCM_OAUTH_EXCHANGE_FAILED,
                    "providerCode=%s returned no access token".formatted(provider.getProviderCode()));
        }

        String refreshToken = body.path("refresh_token").asText(null);
        Instant expiresAt = null;
        if (body.hasNonNull("expires_in")) {
            long expiresIn = body.get("expires_in").asLong(0);
            if (expiresIn > 0) {
                expiresAt = Instant.now().plusSeconds(expiresIn);
            }
        }
        String scope = body.path("scope").asText(null);

        log.info("SCM_OAUTH_TOKEN_ISSUED: providerCode={}, grantType={}, expiresAt={}, refreshTokenIssued={}",
                provider.getProviderCode(), grantType, expiresAt, refreshToken != null && !refreshToken.isBlank());

        return new ScmTokenSet(accessToken, refreshToken, expiresAt, scope);
    }

    private String formEncode(Map<String, String> body) {
        StringBuilder encoded = new StringBuilder();
        body.forEach((name, value) -> {
            if (value == null) {
                return;
            }
            if (!encoded.isEmpty()) {
                encoded.append('&');
            }
            encoded.append(URLEncoder.encode(name, StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
        });
        return encoded.toString();
    }
}
