package com.kksg.applicationServices.scm.connection.service;

import com.kksg.applicationServices.scm.common.exception.ScmErrorCode;
import com.kksg.applicationServices.scm.common.exception.ScmException;
import com.kksg.applicationServices.scm.common.model.ScmTokenSet;
import com.kksg.applicationServices.scm.connection.entity.ScmConnection;
import com.kksg.applicationServices.scm.connection.entity.ScmConnectionStatus;
import com.kksg.applicationServices.scm.connection.repository.ScmConnectionRepository;
import com.kksg.applicationServices.scm.provider.entity.ScmProvider;
import com.kksg.applicationServices.scm.secret.ScmSecretStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Performs a token refresh under a database row lock.
 *
 * <p><b>Why this is a separate bean from {@link ScmTokenService}.</b> The refresh must run inside a
 * transaction so that {@code SELECT ... FOR UPDATE} actually holds its lock. Spring's transaction
 * support is proxy-based, so a {@code @Transactional} method invoked from another method of the
 * <i>same</i> bean bypasses the proxy and silently runs without a transaction - the lock would be
 * taken and released immediately by the repository's own transaction, and the race it exists to
 * prevent would still occur. Splitting the transactional work into its own bean makes the call go
 * through the proxy.
 *
 * <p><b>The race being prevented.</b> Two concurrent operations on a connection whose token just
 * expired would both attempt a refresh. Providers typically invalidate a refresh token when it is
 * redeemed, so the second exchange fails and, worse, may overwrite the stored refresh token with one
 * the provider has already rejected - permanently breaking the connection and forcing the user to
 * reauthorize. Serialising on the row means the second caller waits, re-reads, sees a valid token and
 * skips the exchange entirely.
 */
@Service
public class ScmTokenRefresher {

    private static final Logger log = LoggerFactory.getLogger(ScmTokenRefresher.class);

    private final ScmConnectionRepository connectionRepository;
    private final ScmSecretStore secretStore;
    private final ScmOAuthTokenExchanger tokenExchanger;

    public ScmTokenRefresher(ScmConnectionRepository connectionRepository,
                             ScmSecretStore secretStore,
                             ScmOAuthTokenExchanger tokenExchanger) {
        this.connectionRepository = connectionRepository;
        this.secretStore = secretStore;
        this.tokenExchanger = tokenExchanger;
    }

    /**
     * @param skew the renewal margin used by the caller, re-applied here so the post-lock re-check
     *             uses identical criteria and cannot oscillate between the two.
     * @return a freshly issued access token, or the token another caller just obtained.
     */
    @Transactional
    public String refresh(Integer connectionId, ScmProvider provider, Duration skew) {
        ScmConnection locked = connectionRepository.findByIdForUpdate(connectionId)
                .orElseThrow(() -> new ScmException(ScmErrorCode.SCM_CONNECTION_NOT_FOUND,
                        "connectionId=%d".formatted(connectionId)));

        if (!ScmConnectionTokens.needsRenewal(locked, skew)) {
            log.debug("SCM_TOKEN_REFRESH_SKIPPED: connectionId={} was renewed by a concurrent caller",
                    connectionId);
            return readAccessToken(locked);
        }

        String refreshToken = secretStore.retrieve(locked.getRefreshTokenReference())
                .orElseThrow(() -> {
                    log.warn("SCM_REFRESH_TOKEN_UNRESOLVABLE: connectionId={}", connectionId);
                    return new ScmException(ScmErrorCode.SCM_CONNECTION_EXPIRED,
                            "connectionId=%d must be reauthorized".formatted(connectionId));
                });

        ScmTokenSet renewed;
        try {
            renewed = tokenExchanger.refreshAccessToken(provider, refreshToken);
        } catch (ScmException ex) {
            // Persist the failure so the UI can prompt for reauthorization rather than retrying forever.
            locked.setConnectionStatus(ScmConnectionStatus.EXPIRED);
            connectionRepository.save(locked);
            log.warn("SCM_TOKEN_REFRESH_FAILED: connectionId={}, providerCode={}",
                    connectionId, provider.getProviderCode());
            throw new ScmException(ScmErrorCode.SCM_CONNECTION_EXPIRED,
                    "connectionId=%d could not be renewed".formatted(connectionId), ex);
        }

        ScmConnectionTokens.write(locked, renewed, secretStore);
        locked.setConnectionStatus(ScmConnectionStatus.ACTIVE);
        locked.setLastUsedAt(Instant.now());
        connectionRepository.save(locked);

        log.info("SCM_TOKEN_REFRESHED: connectionId={}, providerCode={}, expiresAt={}",
                connectionId, provider.getProviderCode(), renewed.getExpiresAt());
        return renewed.getAccessToken();
    }

    private String readAccessToken(ScmConnection connection) {
        return secretStore.retrieve(connection.getAccessTokenReference())
                .orElseThrow(() -> new ScmException(ScmErrorCode.SCM_CONNECTION_EXPIRED,
                        "connectionId=%d must be reauthorized".formatted(connection.getId())));
    }
}
