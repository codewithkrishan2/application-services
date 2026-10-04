package com.kksg.applicationServices.scm.connection.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A user's SCM connection, as returned by the connection endpoints.
 *
 * <p><b>Never carries credential material.</b> Not the tokens, and not even
 * {@code accessTokenReference} / {@code refreshTokenReference}: a reference is useless to a client and
 * publishing it would expose the internal handle space for no benefit.
 *
 * <p>{@code tokenExpiry} and {@code connectionStatus} are included because a client legitimately needs
 * to show "reconnect required", and neither reveals anything about the credential itself.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScmConnectionResponse {

    private Integer id;

    private Integer providerId;
    private String providerCode;
    private String providerName;

    private String externalAccountId;
    private String externalAccountName;

    private String connectionStatus;

    /**
     * Whether the connection can actually be used right now.
     *
     * <p>Derived rather than stored, and <b>not</b> a restatement of {@code connectionStatus}: an
     * {@code EXPIRED} connection whose provider issues refresh tokens reports {@code REFRESHABLE},
     * because the next call renews it transparently and the user need do nothing. Clients should
     * branch on this to decide what to offer, and on {@code connectionStatus} only to explain why.
     *
     * @see com.kksg.applicationServices.scm.connection.dto.ScmConnectionReadiness
     */
    private String readiness;

    /** Whether a provider call may be attempted. True for READY, EXPIRING and REFRESHABLE. */
    private boolean usable;

    /** Whether the only remedy is the user granting consent again. */
    private boolean reauthorizationRequired;

    /** Null when the provider issues non-expiring tokens. */
    private Instant tokenExpiry;

    private Instant connectedAt;
    private Instant lastUsedAt;

    /** Display-only account details lifted from connection metadata. */
    private String displayName;
    private String avatarUrl;
}
