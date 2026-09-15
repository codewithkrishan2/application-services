package com.kksg.applicationServices.scm.connection.service;

import com.kksg.applicationServices.identity.entity.User;
import com.kksg.applicationServices.scm.common.exception.ScmErrorCode;
import com.kksg.applicationServices.scm.common.exception.ScmException;
import com.kksg.applicationServices.scm.connection.dto.ScmConnectionResponse;
import com.kksg.applicationServices.scm.connection.entity.ScmConnection;
import com.kksg.applicationServices.scm.connection.entity.ScmConnectionStatus;
import com.kksg.applicationServices.scm.connection.mapper.ScmConnectionMapper;
import com.kksg.applicationServices.scm.connection.repository.ScmConnectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Manages existing connections: listing, ownership-scoped lookup, and disconnection.
 *
 * <p>Deliberately does not perform OAuth or call providers - that is
 * {@code ScmConnectionAuthorizationService}. This service only touches the database, which keeps its
 * behaviour easy to reason about and to test.
 *
 * <p><b>Every read is scoped by user.</b> Connections are the most sensitive rows this module owns:
 * reaching one means reaching a user's repositories. Lookups therefore filter by {@code userId} in the
 * query rather than loading by id and checking afterwards, so the check cannot be omitted by a future
 * caller, and an id belonging to another user is reported as not-found rather than forbidden - which
 * avoids turning the endpoint into an oracle for which connection ids exist.
 */
@Service
public class ScmConnectionService {

    private static final Logger log = LoggerFactory.getLogger(ScmConnectionService.class);

    private final ScmConnectionRepository connectionRepository;
    private final ScmTokenService tokenService;

    public ScmConnectionService(ScmConnectionRepository connectionRepository,
                                ScmTokenService tokenService) {
        this.connectionRepository = connectionRepository;
        this.tokenService = tokenService;
    }

    @Transactional(readOnly = true)
    public List<ScmConnectionResponse> listConnections(User user) {
        return connectionRepository.findByUserIdOrderByConnectedAtDesc(user.getId()).stream()
                .map(ScmConnectionMapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ScmConnectionResponse getConnection(User user, Integer connectionId) {
        return ScmConnectionMapper.toResponse(requireOwned(user, connectionId));
    }

    /**
     * @throws ScmException {@link ScmErrorCode#SCM_CONNECTION_NOT_FOUND} when the connection does not
     *         exist <i>or</i> belongs to another user.
     */
    @Transactional(readOnly = true)
    public ScmConnection requireOwned(User user, Integer connectionId) {
        if (connectionId == null) {
            throw new ScmException(ScmErrorCode.SCM_CONNECTION_NOT_FOUND);
        }
        return connectionRepository.findByIdAndUserId(connectionId, user.getId())
                .orElseThrow(() -> {
                    log.warn("SCM_CONNECTION_NOT_FOUND: userId={}, connectionId={}", user.getId(), connectionId);
                    return new ScmException(ScmErrorCode.SCM_CONNECTION_NOT_FOUND,
                            "connectionId=%d".formatted(connectionId));
                });
    }

    /**
     * Disconnects a connection: destroys its credentials and marks it {@code DISCONNECTED}.
     *
     * <p><b>The row is kept rather than deleted.</b> Module 3 will reference connections from repository
     * records and Module 8 from review history; deleting the row would either cascade that history away
     * or leave dangling references. Marking it disconnected preserves an audit trail of what was
     * reviewed under which authorization.
     *
     * <p>The credentials, by contrast, are destroyed immediately - retaining a token for a connection
     * the user has revoked would be indefensible, and {@link ScmConnection#isUsable()} already excludes
     * this state from any API call.
     *
     * <p>Idempotent: disconnecting an already-disconnected connection succeeds without side effects, so
     * a retried request cannot fail.
     */
    @Transactional
    public void disconnect(User user, Integer connectionId) {
        ScmConnection connection = requireOwned(user, connectionId);

        if (connection.getConnectionStatus() == ScmConnectionStatus.DISCONNECTED) {
            log.info("SCM_DISCONNECT_NOOP: userId={}, connectionId={} already disconnected",
                    user.getId(), connectionId);
            return;
        }

        tokenService.discardTokens(connection);
        connection.setConnectionStatus(ScmConnectionStatus.DISCONNECTED);
        connectionRepository.save(connection);

        log.info("SCM_DISCONNECTED: userId={}, connectionId={}, providerCode={}",
                user.getId(), connectionId,
                connection.getProvider() != null ? connection.getProvider().getProviderCode() : null);
    }

    /**
     * Resolves the connection a webhook delivery should be attributed to.
     *
     * <p>Not user-scoped, because an inbound webhook has no authenticated user - it is identified by
     * provider and external account. Returns the first active connection for that account: several
     * users may have connected the same organisation, and any of their authorizations is equally valid
     * for recording the delivery.
     *
     * <p>Attribution is best-effort by design. A delivery that cannot be attributed is still recorded,
     * because dropping it would lose the audit trail and the idempotency marker.
     */
    @Transactional(readOnly = true)
    public ScmConnection findConnectionForDelivery(Integer providerId, String externalAccountId) {
        if (providerId == null || externalAccountId == null || externalAccountId.isBlank()) {
            return null;
        }
        return connectionRepository
                .findByProviderIdAndExternalAccountIdOrderByIdAsc(providerId, externalAccountId).stream()
                .filter(ScmConnection::isUsable)
                .findFirst()
                .orElse(null);
    }
}
