package com.kksg.applicationServices.scm.connection.entity;

import com.kksg.applicationServices.common.entity.BaseEntity;
import com.kksg.applicationServices.identity.entity.User;
import com.kksg.applicationServices.scm.provider.entity.ScmProvider;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A user's authorization to one SCM provider account.
 *
 * <p><b>This is what separates Module 2 from Module 1.</b> Module 1 answers "who is the user of our
 * platform?" and may well have used GitHub OAuth to answer it. This entity answers a different
 * question: "which source-control accounts has this user granted us access to, and with what
 * credentials?" A user who signed in with GitHub has not thereby connected GitHub for code review -
 * the login grant carries identity scopes, not repository scopes - and a user may connect Bitbucket
 * while never having logged in with it. Modelling connections separately from {@code user_logins} is
 * what makes both cases representable.
 *
 * <p><b>Credentials are references, not values.</b> {@code accessTokenReference} and
 * {@code refreshTokenReference} are opaque handles resolved through {@code ScmSecretStore}. No
 * column on this table can be read to obtain a usable token, so a row dump, a log of the entity, or
 * a careless projection cannot leak provider access.
 *
 * <p><b>Uniqueness.</b> {@code (user_id, provider_id, external_account_id)} is unique so that
 * re-running the OAuth flow updates the existing connection instead of accumulating duplicates,
 * while still allowing one user to connect two distinct accounts on the same provider (personal and
 * work). The constraint is enforced in the database rather than only in the service, because two
 * concurrent callbacks would both pass an application-level existence check.
 *
 * <p>{@code metadata} carries non-secret provider details discovered at connect time - avatar URL,
 * default workspace, and for a self-hosted instance the {@code baseUrl} that overrides the
 * provider's configured default. That override is what lets one {@code SELF_HOSTED} provider row
 * serve many tenants.
 */
@Entity
@Table(name = "scm_connections",
        uniqueConstraints = {
                @UniqueConstraint(name = "ux_scm_connection_user_provider_account",
                        columnNames = {"user_id", "provider_id", "external_account_id"})
        },
        indexes = {
                @Index(name = "ix_scm_connections_user", columnList = "user_id"),
                @Index(name = "ix_scm_connections_provider_account",
                        columnList = "provider_id, external_account_id")
        })
@Getter
@Setter
@NoArgsConstructor
public class ScmConnection extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "provider_id", nullable = false)
    private ScmProvider provider;

    /**
     * Provider's stable identifier for the authorized account (GitHub numeric id, Bitbucket UUID).
     * Stored as text because providers disagree on the type.
     */
    @Column(name = "external_account_id", nullable = false, length = 200)
    private String externalAccountId;

    /** Human-readable account name/login, for display only. */
    @Column(name = "external_account_name", length = 200)
    private String externalAccountName;

    /** Handle into {@code ScmSecretStore}; never the token itself. */
    @Column(name = "access_token_reference", length = 100)
    private String accessTokenReference;

    /** Handle into {@code ScmSecretStore}; null when the provider issues no refresh token. */
    @Column(name = "refresh_token_reference", length = 100)
    private String refreshTokenReference;

    /** Access token expiry; null means the provider issues non-expiring tokens. */
    @Column(name = "token_expiry")
    private Instant tokenExpiry;

    @Enumerated(EnumType.STRING)
    @Column(name = "connection_status", nullable = false, length = 30)
    private ScmConnectionStatus connectionStatus = ScmConnectionStatus.ACTIVE;

    /** Non-secret provider details; may carry a {@code baseUrl} override for self-hosted instances. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadata = new LinkedHashMap<>();

    @Column(name = "connected_at")
    private Instant connectedAt;

    /** Last successful provider API call; useful for spotting connections that silently went stale. */
    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    /**
     * @return true when the provider supplied an expiry that has passed. A null expiry means
     *         "never expires" and is therefore not expired - treating null as expired would break
     *         providers that issue non-expiring tokens.
     */
    public boolean isTokenExpired() {
        return tokenExpiry != null && Instant.now().isAfter(tokenExpiry);
    }

    /**
     * @return true when this connection may be used for API calls. An expired token alone does not
     *         disqualify it: the token service may still be able to refresh it, so expiry is handled
     *         there rather than being treated as terminal here.
     */
    public boolean isUsable() {
        return connectionStatus == ScmConnectionStatus.ACTIVE || connectionStatus == ScmConnectionStatus.EXPIRED;
    }

    @Override
    public String toString() {
        // Never render token references or metadata.
        return "ScmConnection{id=%s, externalAccountId=%s, status=%s}"
                .formatted(getId(), externalAccountId, connectionStatus);
    }
}
