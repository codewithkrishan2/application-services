package com.kksg.applicationServices.identity.service.oauth;

import com.kksg.applicationServices.common.exception.ApiException;
import com.kksg.applicationServices.identity.entity.LoginProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * GitHub sign-in.
 *
 * <p>Only the GitHub-specific details live here: the endpoints, the scopes, and the shape of the account
 * and email payloads. The grant itself is in {@link AbstractOAuthLoginProvider}.
 *
 * <p>Notable GitHub behaviours: a user's email is not on the profile payload at all when they have set it
 * to private, so it has to be read from a second endpoint (which is what the {@code user:email} scope is
 * for), and OAuth App access tokens do not expire and carry no refresh token.
 */
@Component
public class GitHubOAuthLoginProvider extends AbstractOAuthLoginProvider {

    private static final String AUTHORIZE_URL = "https://github.com/login/oauth/authorize";
    private static final String TOKEN_URL = "https://github.com/login/oauth/access_token";
    private static final String USER_URL = "https://api.github.com/user";
    private static final String USER_EMAILS_URL = "https://api.github.com/user/emails";

    /** {@code read:user} for the profile, {@code user:email} for the verified address. */
    private static final String SCOPES = "read:user user:email";

    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;

    public GitHubOAuthLoginProvider(@Value("${github.oauth.client-id:}") String clientId,
                                    @Value("${github.oauth.client-secret:}") String clientSecret,
                                    @Value("${github.oauth.redirect-uri:}") String redirectUri) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.redirectUri = redirectUri;
    }

    @Override
    public LoginProvider provider() {
        return LoginProvider.GITHUB;
    }

    @Override
    public String displayName() {
        return "GitHub";
    }

    @Override
    protected String authorizationUrl() {
        return AUTHORIZE_URL;
    }

    @Override
    protected String tokenUrl() {
        return TOKEN_URL;
    }

    @Override
    protected String scope() {
        return SCOPES;
    }

    @Override
    protected ClientAuthStyle clientAuthStyle() {
        return ClientAuthStyle.BODY;
    }

    @Override
    protected BodyEncoding tokenRequestEncoding() {
        return BodyEncoding.FORM;
    }

    @Override
    protected String clientId() {
        return clientId;
    }

    @Override
    protected String clientSecret() {
        return clientSecret;
    }

    @Override
    protected String redirectUri() {
        return redirectUri;
    }

    @Override
    public OAuthUserProfile fetchUserProfile(OAuthTokenSet tokens) {
        Map<String, Object> account = getJsonObject(USER_URL, tokens.accessToken(), "user profile");

        String providerUserId = stringField(account, "id");
        if (providerUserId == null) {
            throw new ApiException("GitHub did not return an account identifier");
        }

        String name = stringField(account, "name");
        String login = stringField(account, "login");
        String email = findVerifiedEmail(tokens.accessToken());

        if (email == null) {
            // Without an address there is nothing to key the user on, so this has to stop the flow.
            log().warn("OAUTH_FAILED: provider=GITHUB, reason=no_verified_email");
            throw new ApiException("Could not retrieve a verified email from GitHub");
        }

        return new OAuthUserProfile(
                providerUserId,
                email,
                true,
                name != null ? name : login,
                stringField(account, "avatar_url"));
    }

    /**
     * Prefers the primary verified address, then any verified one. Unverified addresses are never
     * accepted: they would let an account be claimed by asserting someone else's email.
     */
    private String findVerifiedEmail(String accessToken) {
        List<Map<String, Object>> emails = getJsonArray(USER_EMAILS_URL, accessToken, "email addresses");

        for (Map<String, Object> entry : emails) {
            if (booleanField(entry, "primary") && booleanField(entry, "verified")) {
                return stringField(entry, "email");
            }
        }
        for (Map<String, Object> entry : emails) {
            if (booleanField(entry, "verified")) {
                return stringField(entry, "email");
            }
        }
        return null;
    }
}
