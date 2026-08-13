package com.kksg.applicationServices.identity.entity;

import com.kksg.applicationServices.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

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

    @Column(name = "access_token", columnDefinition = "TEXT")
    private String accessToken;

    @Column(name = "refresh_token", columnDefinition = "TEXT")
    private String refreshToken;

    @Column(name = "expires_at")
    private Instant expiresAt;

    // Application-level refresh token fields
    @Column(name = "app_refresh_token", unique = true)
    private String appRefreshToken;

    @Column(name = "app_refresh_token_expires_at")
    private Instant appRefreshTokenExpiresAt;

    @Column(name = "app_refresh_token_revoked", nullable = false)
    private boolean appRefreshTokenRevoked = false;

    @Column(name = "app_refresh_token_revoked_at")
    private Instant appRefreshTokenRevokedAt;

    public boolean isAppRefreshTokenExpired() {
        return appRefreshTokenExpiresAt != null && Instant.now().isAfter(appRefreshTokenExpiresAt);
    }

    public boolean isAppRefreshTokenUsable() {
        return appRefreshToken != null && !appRefreshTokenRevoked && !isAppRefreshTokenExpired();
    }
}
