package com.kksg.applicationServices.identity.service.oauth;

import com.kksg.applicationServices.common.exception.ApiException;
import com.kksg.applicationServices.identity.entity.LoginProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Bitbucket Cloud sign-in, using an Atlassian 3LO OAuth consumer.
 *
 * <p>Four things differ from GitHub, and all four are expressed as configuration rather than a separate
 * flow:
 * <ul>
 *   <li><b>Client credentials go in an HTTP Basic header.</b> Bitbucket's token endpoint rejects
 *       credentials passed in the body, so {@link ClientAuthStyle#BASIC} is required.</li>
 *   <li><b>No {@code scope} parameter.</b> A Bitbucket consumer's permissions are chosen when the consumer
 *       is registered, not requested per authorization. Sending scopes the consumer was not granted is
 *       rejected, which is why {@link #scope()} is {@code null}.</li>
 *   <li><b>Tokens expire.</b> Bitbucket access tokens last about two hours and come with a refresh token,
 *       both of which are persisted on the login row.</li>
 *   <li><b>Different field names.</b> The account identifier is a braced {@code uuid} rather than a
 *       numeric id, and email confirmation is {@code is_confirmed} rather than {@code verified}.</li>
 * </ul>
 *
 * <p>The {@code redirect_uri} is sent explicitly so the callback is pinned by this application rather than
 * inherited from whatever is currently configured on the consumer. It must match the consumer's registered
 * callback URL exactly, or Bitbucket refuses the authorization.
 */
@Component
public class BitbucketOAuthLoginProvider extends AbstractOAuthLoginProvider {

    private static final String AUTHORIZE_URL = "https://bitbucket.org/site/oauth2/authorize";
    private static final String TOKEN_URL = "https://bitbucket.org/site/oauth2/access_token";
    private static final String USER_URL = "https://api.bitbucket.org/2.0/user";
    private static final String USER_EMAILS_URL = "https://api.bitbucket.org/2.0/user/emails";

    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;

    public BitbucketOAuthLoginProvider(@Value("${bitbucket.oauth.client-id:}") String clientId,
                                       @Value("${bitbucket.oauth.client-secret:}") String clientSecret,
                                       @Value("${bitbucket.oauth.redirect-uri:}") String redirectUri) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.redirectUri = redirectUri;
    }

    @Override
    public LoginProvider provider() {
        return LoginProvider.BITBUCKET;
    }

    @Override
    public String displayName() {
        return "Bitbucket";
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
        return null;
    }

    @Override
    protected ClientAuthStyle clientAuthStyle() {
        return ClientAuthStyle.BASIC;
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

        String providerUserId = stringField(account, "uuid");
        if (providerUserId == null) {
            throw new ApiException("Bitbucket did not return an account identifier");
        }

        String displayName = stringField(account, "display_name");
        String username = stringField(account, "username");
        if (username == null) {
            username = stringField(account, "nickname");
        }

        String email = findConfirmedEmail(tokens.accessToken());
        if (email == null) {
            // Users are keyed on email, so there is nothing to sign in as without one.
            log().warn("OAUTH_FAILED: provider=BITBUCKET, reason=no_confirmed_email");
            throw new ApiException("Could not retrieve a confirmed email from Bitbucket."
                    + " Check that your Bitbucket account has a confirmed address and that the"
                    + " OAuth consumer is allowed to read it.");
        }

        return new OAuthUserProfile(
                providerUserId,
                email,
                true,
                displayName != null ? displayName : username,
                nestedStringField(account, "links", "avatar", "href"));
    }

    /**
     * Prefers the primary confirmed address, then any confirmed one. Unconfirmed addresses are rejected:
     * because users are matched on email, accepting one would let an existing account be claimed by
     * asserting its owner's address here.
     *
     * <p>Bitbucket wraps collections in a pagination envelope. Only the first page is read, which is
     * sufficient because a primary address is unique per account and appears there.
     */
    private String findConfirmedEmail(String accessToken) {
        Map<String, Object> payload = getJsonObject(USER_EMAILS_URL, accessToken, "email addresses");
        List<Map<String, Object>> entries = asJsonObjects(payload.get("values"));

        for (Map<String, Object> entry : entries) {
            if (booleanField(entry, "is_primary") && booleanField(entry, "is_confirmed")) {
                return stringField(entry, "email");
            }
        }
        for (Map<String, Object> entry : entries) {
            if (booleanField(entry, "is_confirmed")) {
                return stringField(entry, "email");
            }
        }
        return null;
    }
}
