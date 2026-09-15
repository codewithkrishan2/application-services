package com.kksg.applicationServices.scm.connection.service;

import com.kksg.applicationServices.scm.common.model.ScmTokenSet;
import com.kksg.applicationServices.scm.connection.entity.ScmConnection;
import com.kksg.applicationServices.scm.secret.ScmSecretStore;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Shared credential-persistence primitives for connections.
 *
 * <p>A package-private static helper rather than a Spring bean, deliberately. Both
 * {@link ScmTokenService} (connect and read paths) and {@link ScmTokenRefresher} (locked refresh path)
 * need these few operations. Making it a bean would force one of those services to depend on the
 * other and create a circular bean dependency; making it a static helper keeps the bean graph acyclic
 * without duplicating the logic in two places.
 */
final class ScmConnectionTokens {

    static final String ACCESS_TOKEN_LABEL = "scm.connection.access-token";
    static final String REFRESH_TOKEN_LABEL = "scm.connection.refresh-token";

    private ScmConnectionTokens() {
    }

    /**
     * Writes a token set onto a connection, reusing existing secret references.
     *
     * <p>Reusing references means a refresh updates the ciphertext in place rather than creating a new
     * secret row and orphaning the old one.
     *
     * <p>The refresh reference is only replaced when the provider actually returned a new refresh
     * token. Several providers omit {@code refresh_token} from a refresh response and expect the
     * original to stay valid, so overwriting it with null would break the following renewal.
     */
    static void write(ScmConnection connection, ScmTokenSet tokens, ScmSecretStore secretStore) {
        connection.setAccessTokenReference(secretStore.update(
                connection.getAccessTokenReference(), ACCESS_TOKEN_LABEL, tokens.getAccessToken()));

        if (tokens.hasRefreshToken()) {
            connection.setRefreshTokenReference(secretStore.update(
                    connection.getRefreshTokenReference(), REFRESH_TOKEN_LABEL, tokens.getRefreshToken()));
        }
        connection.setTokenExpiry(tokens.getExpiresAt());
    }

    /** Destroys both credentials and clears their references. */
    static void discard(ScmConnection connection, ScmSecretStore secretStore) {
        Optional.ofNullable(connection.getAccessTokenReference()).ifPresent(secretStore::delete);
        Optional.ofNullable(connection.getRefreshTokenReference()).ifPresent(secretStore::delete);
        connection.setAccessTokenReference(null);
        connection.setRefreshTokenReference(null);
        connection.setTokenExpiry(null);
    }

    /**
     * @return true when the token has expired or is close enough to expiry that it would probably be
     *         rejected before the request lands. A null expiry means the provider issues non-expiring
     *         tokens, which is never due for renewal.
     */
    static boolean needsRenewal(ScmConnection connection, Duration skew) {
        Instant expiry = connection.getTokenExpiry();
        return expiry != null && Instant.now().plus(skew).isAfter(expiry);
    }
}
