package com.kksg.applicationServices.identity.entity;

import com.kksg.applicationServices.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Links a user to one identity provider, and holds that pairing's refresh-token state.
 *
 * <p><b>No provider credential is stored here.</b> This entity used to carry the GitHub or Bitbucket
 * {@code access_token} and {@code refresh_token} in cleartext {@code TEXT} columns. Nothing ever read
 * them - provider API calls go through the SCM module, which keeps its own copies encrypted with
 * AES-GCM - so they were a live, repo-scoped credential sitting in the database for no purpose. The
 * columns are gone rather than encrypted: a credential that is not stored cannot leak.
 *
 * <p><b>The application's own refresh token is stored as a digest, not a value.</b> See
 * {@code RefreshTokenService} for why a digest is sufficient here.
 */
@Entity
@Table(name = "user_logins", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"user_id", "provider"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UserLogin extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false)
    private LoginProvider provider;

    @Column(name = "provider_user_id")
    private String providerUserId;

    /** When the provider's own grant expires. Metadata only; the grant itself is not kept here. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    /**
     * SHA-256 digest of the current refresh token, hex encoded.
     *
     * <p>The column name is deliberately unchanged from when this held the token itself. Renaming it
     * under {@code ddl-auto: update} would add a new column and leave the old one in place, still full
     * of usable plaintext credentials - the opposite of the intent. Any legacy plaintext left in this
     * column is inert, because lookups digest the presented token before comparing.
     */
    @Column(name = "app_refresh_token", unique = true)
    private String appRefreshTokenHash;

    /**
     * Digest of the token this one replaced, kept for one rotation.
     *
     * <p>This is what makes theft detectable. A rotated token should never be presented again, so if one
     * is, two parties hold the same credential and the newer holder may be an attacker. Without this
     * column a replay is indistinguishable from a typo, and the only response available is to reject the
     * single request.
     */
    @Column(name = "app_refresh_token_previous_hash")
    private String appRefreshTokenPreviousHash;

    @Column(name = "app_refresh_token_expires_at")
    private Instant appRefreshTokenExpiresAt;

    @Column(name = "app_refresh_token_revoked", nullable = false)
    private boolean appRefreshTokenRevoked = false;

    @Column(name = "app_refresh_token_revoked_at")
    private Instant appRefreshTokenRevokedAt;

    /**
     * A missing expiry counts as expired.
     *
     * <p>It previously counted as "never expires", which turned any row with a null
     * {@code app_refresh_token_expires_at} into an immortal credential. For a check that gates a
     * long-lived credential, the absence of data has to fail closed.
     */
    public boolean isAppRefreshTokenExpired() {
        return appRefreshTokenExpiresAt == null || Instant.now().isAfter(appRefreshTokenExpiresAt);
    }

    public boolean isAppRefreshTokenUsable() {
        return appRefreshTokenHash != null && !appRefreshTokenRevoked && !isAppRefreshTokenExpired();
    }
}
