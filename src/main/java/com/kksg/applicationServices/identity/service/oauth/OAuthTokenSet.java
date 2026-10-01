package com.kksg.applicationServices.identity.service.oauth;

import java.time.Instant;

/**
 * The credentials returned by a provider's token endpoint, normalized across providers.
 *
 * <p>Providers disagree about what they return. GitHub OAuth App tokens do not expire and carry no
 * refresh token, so {@code expiresInSeconds} and {@code refreshToken} are {@code null}. Bitbucket
 * tokens expire after roughly two hours and always carry a refresh token. Representing "unknown" as
 * {@code null} rather than defaulting to an arbitrary lifetime keeps the distinction honest: a stored
 * expiry of {@code null} means "no expiry was advertised", not "expires in an hour".
 *
 * @param accessToken      the provider access token; never {@code null}
 * @param refreshToken     the provider refresh token, or {@code null} when the provider issues none
 * @param expiresInSeconds advertised lifetime in seconds, or {@code null} for non-expiring tokens
 * @param scope            the scopes actually granted, as reported by the provider; may be {@code null}
 */
public record OAuthTokenSet(String accessToken, String refreshToken, Long expiresInSeconds, String scope) {

    /**
     * @return the absolute expiry derived from the advertised lifetime, or {@code null} when the
     *         provider did not advertise one.
     */
    public Instant expiresAt() {
        return expiresInSeconds == null ? null : Instant.now().plusSeconds(expiresInSeconds);
    }
}
