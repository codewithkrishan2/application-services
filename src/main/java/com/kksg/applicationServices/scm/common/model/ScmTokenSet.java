package com.kksg.applicationServices.scm.common.model;

import java.time.Instant;

/**
 * Credentials returned by a provider's token endpoint.
 *
 * <p><b>Deliberately a class, not a record.</b> A record generates a {@code toString()} that prints
 * every component, which for this type means printing an access token the first time an instance is
 * interpolated into a log line or an exception message. The custom {@link #toString()} below reports
 * only whether each token is present.
 *
 * <p>Instances are short-lived: created by the token exchange, immediately handed to
 * {@code ScmSecretStore}, then discarded. Nothing persists one.
 */
public final class ScmTokenSet {

    private final String accessToken;
    private final String refreshToken;
    private final Instant expiresAt;
    private final String scope;

    public ScmTokenSet(String accessToken, String refreshToken, Instant expiresAt, String scope) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.expiresAt = expiresAt;
        this.scope = scope;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    /** Absolute expiry, or {@code null} for providers that issue non-expiring tokens. */
    public Instant getExpiresAt() {
        return expiresAt;
    }

    /** Space- or comma-separated scopes actually granted, which may be narrower than requested. */
    public String getScope() {
        return scope;
    }

    public boolean hasRefreshToken() {
        return refreshToken != null && !refreshToken.isBlank();
    }

    @Override
    public String toString() {
        return "ScmTokenSet{accessToken=%s, refreshToken=%s, expiresAt=%s}".formatted(
                accessToken != null && !accessToken.isBlank() ? "[present]" : "[absent]",
                refreshToken != null && !refreshToken.isBlank() ? "[present]" : "[absent]",
                expiresAt);
    }
}
