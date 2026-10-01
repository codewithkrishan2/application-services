package com.kksg.applicationServices.identity.service.oauth;

import com.kksg.applicationServices.identity.entity.LoginProvider;

/**
 * The provider-specific half of the sign-in flow.
 *
 * <p>Everything that is identical across providers - issuing the CSRF state, matching or creating the
 * {@link com.kksg.applicationServices.identity.entity.User}, upserting the
 * {@link com.kksg.applicationServices.identity.entity.UserLogin}, minting the application's own token
 * pair, and building the frontend redirect - lives in
 * {@link OAuthLoginService}. An implementation of this interface supplies only what genuinely differs:
 * the endpoints, how the token request is packaged, and how the account payload is shaped.
 *
 * <p>Implementations are discovered as beans. Adding a provider therefore means adding one class and
 * its configuration, with no change to the orchestration or to existing providers.
 *
 * <p>Implementations signal failure with
 * {@link com.kksg.applicationServices.common.exception.ApiException} carrying a message that is safe to
 * show a user, because {@code OAuthLoginService} turns that message into a redirect to the frontend
 * error page. Provider codes, tokens and secrets must never appear in those messages.
 */
public interface OAuthLoginProvider {

    /** The enum value persisted on {@code user_logins.provider} for this provider. */
    LoginProvider provider();

    /** Human-readable name used in user-facing messages, for example {@code "Bitbucket"}. */
    String displayName();

    /**
     * @param state the signed state to round-trip through the provider; see {@link OAuthLoginStateService}
     * @return the provider's consent URL to redirect the browser to
     */
    String buildAuthorizationUrl(String state);

    /** Trades a single-use authorization code for provider credentials. */
    OAuthTokenSet exchangeAuthorizationCode(String code);

    /**
     * Reads the authenticated account, including a confirmed email address.
     *
     * @throws com.kksg.applicationServices.common.exception.ApiException when no confirmed email is
     *         available, since this application keys users on email
     */
    OAuthUserProfile fetchUserProfile(OAuthTokenSet tokens);
}
