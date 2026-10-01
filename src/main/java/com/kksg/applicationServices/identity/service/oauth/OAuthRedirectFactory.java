package com.kksg.applicationServices.identity.service.oauth;

import com.kksg.applicationServices.identity.dto.response.AuthResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Builds the frontend URLs that end every sign-in flow.
 *
 * <p>These two shapes are a contract with the frontend, which has route handlers listening on
 * {@code /oauth-success} and {@code /oauth-error} and reads exactly these parameter names. Centralizing
 * them here means a new provider cannot drift from that contract, and a future change (moving the tokens
 * out of the query string, say) happens in one place for every provider at once.
 *
 * <p>Values are encoded by hand and the builder is intentionally left un-encoded afterwards, so each
 * value is percent-encoded exactly once.
 */
@Component
public class OAuthRedirectFactory {

    private static final String SUCCESS_PATH = "/oauth-success";
    private static final String ERROR_PATH = "/oauth-error";

    private final String frontendUrl;

    public OAuthRedirectFactory(@Value("${app.frontend-url}") String frontendUrl) {
        this.frontendUrl = frontendUrl;
    }

    /** The frontend exchanges these query parameters for httpOnly cookies and discards them. */
    public String success(AuthResponse authResponse) {
        return UriComponentsBuilder.fromHttpUrl(frontendUrl)
                .path(SUCCESS_PATH)
                .queryParam("access_token", encode(authResponse.getAccessToken()))
                .queryParam("refresh_token", encode(authResponse.getRefreshToken()))
                .build()
                .toUriString();
    }

    /** @param message text shown to the user; callers must keep provider internals out of it. */
    public String error(String message) {
        return UriComponentsBuilder.fromHttpUrl(frontendUrl)
                .path(ERROR_PATH)
                .queryParam("message", encode(message))
                .build()
                .toUriString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
