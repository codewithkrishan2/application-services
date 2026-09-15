package com.kksg.applicationServices.scm.connection.service;

import com.kksg.applicationServices.scm.common.exception.ScmErrorCode;
import com.kksg.applicationServices.scm.common.exception.ScmException;
import com.kksg.applicationServices.scm.common.model.ScmTokenSet;
import com.kksg.applicationServices.scm.connection.entity.ScmConnection;
import com.kksg.applicationServices.scm.connection.entity.ScmConnectionStatus;
import com.kksg.applicationServices.scm.connection.repository.ScmConnectionRepository;
import com.kksg.applicationServices.scm.provider.config.ProviderConfiguration;
import com.kksg.applicationServices.scm.provider.config.ProviderConfigurationFactory;
import com.kksg.applicationServices.scm.provider.entity.ScmProvider;
import com.kksg.applicationServices.scm.secret.ScmSecretStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Turns a connection into a usable access token, renewing it when required.
 *
 * <p>Every provider API call goes through here, which is what allows the operation engine to be
 * unaware of credential storage, expiry and refresh entirely. Callers get a token or a meaningful
 * error; there is no 401-then-retry logic anywhere else in the module.
 *
 * <p>The read path is intentionally non-transactional: it is on the hot path of every API call, and
 * holding a database connection across the subsequent provider HTTP request would tie pool capacity
 * to provider latency. Only the refresh path needs a transaction, and that is delegated to
 * {@link ScmTokenRefresher} so it goes through a Spring proxy.
 */
@Service
public class ScmTokenService {

    private static final Logger log = LoggerFactory.getLogger(ScmTokenService.class);

    /**
     * Renewal margin. A token expiring within this window is treated as already expired, because it
     * would very likely be rejected by the time the request reaches the provider - a failure mode that
     * is intermittent and awkward to diagnose.
     */
    private static final Duration EXPIRY_SKEW = Duration.ofSeconds(60);

    private final ScmConnectionRepository connectionRepository;
    private final ScmSecretStore secretStore;
    private final ScmTokenRefresher tokenRefresher;
    private final ProviderConfigurationFactory configurationFactory;

    public ScmTokenService(ScmConnectionRepository connectionRepository,
                           ScmSecretStore secretStore,
                           ScmTokenRefresher tokenRefresher,
                           ProviderConfigurationFactory configurationFactory) {
        this.connectionRepository = connectionRepository;
        this.secretStore = secretStore;
        this.tokenRefresher = tokenRefresher;
        this.configurationFactory = configurationFactory;
    }

    /**
     * @return an access token valid at the time of the call.
     * @throws ScmException {@link ScmErrorCode#SCM_CONNECTION_REVOKED} when the connection may no
     *         longer be used, or {@link ScmErrorCode#SCM_CONNECTION_EXPIRED} when credentials have
     *         expired and cannot be renewed, meaning the user must reauthorize.
     */
    public String resolveAccessToken(ScmConnection connection, ScmProvider provider) {
        if (connection == null) {
            throw new ScmException(ScmErrorCode.SCM_CONNECTION_NOT_FOUND);
        }
        if (!connection.isUsable()) {
            log.warn("SCM_CONNECTION_NOT_USABLE: connectionId={}, status={}",
                    connection.getId(), connection.getConnectionStatus());
            throw new ScmException(ScmErrorCode.SCM_CONNECTION_REVOKED,
                    "connectionId=%d status=%s".formatted(connection.getId(), connection.getConnectionStatus()));
        }

        if (!ScmConnectionTokens.needsRenewal(connection, EXPIRY_SKEW)) {
            return readAccessToken(connection);
        }

        ProviderConfiguration.OAuth oauth = configurationFactory.get(provider).oauthOrEmpty();
        if (!oauth.supportsRefreshOrDefault() || connection.getRefreshTokenReference() == null) {
            markExpired(connection.getId());
            log.warn("SCM_CONNECTION_EXPIRED: connectionId={}, providerCode={}, refreshSupported={}",
                    connection.getId(), provider.getProviderCode(), oauth.supportsRefreshOrDefault());
            throw new ScmException(ScmErrorCode.SCM_CONNECTION_EXPIRED,
                    "connectionId=%d must be reauthorized".formatted(connection.getId()));
        }

        return tokenRefresher.refresh(connection.getId(), provider, EXPIRY_SKEW);
    }

    /**
     * Persists a freshly issued token set onto a connection. Used by the connect flow.
     *
     * <p>Does not save the connection: the caller owns the transaction that writes the connection row,
     * so that credential references and connection status commit together.
     */
    public void storeTokens(ScmConnection connection, ScmTokenSet tokens) {
        ScmConnectionTokens.write(connection, tokens, secretStore);
    }

    /** Destroys both stored credentials. Used when a connection is disconnected or revoked. */
    public void discardTokens(ScmConnection connection) {
        ScmConnectionTokens.discard(connection, secretStore);
    }

    private String readAccessToken(ScmConnection connection) {
        return secretStore.retrieve(connection.getAccessTokenReference())
                .orElseThrow(() -> {
                    log.warn("SCM_ACCESS_TOKEN_UNRESOLVABLE: connectionId={}", connection.getId());
                    return new ScmException(ScmErrorCode.SCM_CONNECTION_EXPIRED,
                            "connectionId=%d must be reauthorized".formatted(connection.getId()));
                });
    }

    /**
     * Records that a connection needs reauthorization.
     *
     * <p>Carries no {@code @Transactional} annotation on purpose: it is a self-invocation from
     * {@link #resolveAccessToken}, which bypasses the Spring proxy, so an annotation here would be
     * silently ineffective. The repository's own transactional {@code save} suffices - this is a
     * single-row status update with nothing to be atomic with.
     */
    private void markExpired(Integer connectionId) {
        connectionRepository.findById(connectionId).ifPresent(connection -> {
            connection.setConnectionStatus(ScmConnectionStatus.EXPIRED);
            connectionRepository.save(connection);
        });
    }
}
