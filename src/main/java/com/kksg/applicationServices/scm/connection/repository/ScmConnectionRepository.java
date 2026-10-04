package com.kksg.applicationServices.scm.connection.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.kksg.applicationServices.scm.connection.entity.ScmConnection;
import com.kksg.applicationServices.scm.connection.entity.ScmConnectionStatus;

public interface ScmConnectionRepository extends JpaRepository<ScmConnection, Integer> {

    /** Enforces the connect flow's upsert semantics against the unique constraint. */
    Optional<ScmConnection> findByUserIdAndProviderIdAndExternalAccountId(Integer userId,
                                                                         Integer providerId,
                                                                         String externalAccountId);

    /**
     * Fetches the provider alongside each connection.
     *
     * <p>Without the graph, rendering a connection list would touch the lazy {@code provider}
     * association once per row (an N+1), and any consumer outside a transaction would hit a
     * {@code LazyInitializationException}.
     */
    @EntityGraph(attributePaths = {"provider"})
    List<ScmConnection> findByUserIdOrderByConnectedAtDesc(Integer userId);

    List<ScmConnection> findByUserIdAndConnectionStatus(Integer userId, ScmConnectionStatus status);

    /**
     * Scoped lookup used by every authenticated read of a single connection.
     *
     * <p>Filtering by {@code userId} in the query rather than loading by id and comparing afterwards
     * makes it impossible to forget the ownership check, and makes an unauthorized id
     * indistinguishable from a missing one - so the endpoint cannot be used to probe which
     * connection ids exist.
     */
    @EntityGraph(attributePaths = {"provider"})
    Optional<ScmConnection> findByIdAndUserId(Integer id, Integer userId);

    /**
     * Locks a connection row for the duration of a token refresh.
     *
     * <p>Two concurrent operations on an expired connection would otherwise both call the provider's
     * refresh endpoint. Providers commonly invalidate the previous refresh token on use, so the slower
     * of the two would persist a refresh token the provider has already revoked and permanently break
     * the connection. Serialising on the row means the second caller waits, re-reads, and finds the
     * token already renewed.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM ScmConnection c WHERE c.id = :id")
    Optional<ScmConnection> findByIdForUpdate(@Param("id") Integer id);

    /**
     * Connections whose access token is due for proactive renewal.
     *
     * <p>Selects on facts a column can answer, and leaves the rest to the caller: whether the
     * <i>provider</i> supports refresh lives in a JSONB configuration document, which is not something
     * to filter on in SQL. So this narrows to rows that could plausibly need work - expiring soon,
     * holding a refresh credential, in a status worth attempting - and the scheduler checks provider
     * support per row.
     *
     * <p><b>Status filter.</b> {@code ACTIVE} and {@code EXPIRED} only. {@code REVOKED} is excluded
     * deliberately: it is the status a rejected grant produces, and retrying it on a timer is exactly
     * the unbounded loop the terminal/transient split exists to stop. {@code DISCONNECTED} has no
     * credentials left, and {@code ERROR} is held aside by definition.
     *
     * <p>Ordered by expiry so the most urgent go first, and the caller applies a page limit - a batch
     * bound matters more than completeness here, because a backlog is drained by the next tick
     * whereas an unbounded batch competes with live traffic for the provider's rate limit.
     */
    @EntityGraph(attributePaths = {"provider"})
    @Query("""
            SELECT c FROM ScmConnection c
            WHERE c.tokenExpiry IS NOT NULL
              AND c.tokenExpiry < :dueBefore
              AND c.refreshTokenReference IS NOT NULL
              AND c.connectionStatus IN (
                    com.kksg.applicationServices.scm.connection.entity.ScmConnectionStatus.ACTIVE,
                    com.kksg.applicationServices.scm.connection.entity.ScmConnectionStatus.EXPIRED)
            ORDER BY c.tokenExpiry ASC
            """)
    List<ScmConnection> findDueForRefresh(@Param("dueBefore") Instant dueBefore, Pageable pageable);

    /**
     * Records that a connection was just used successfully, without loading the entity.
     *
     * <p>A bulk update rather than a read-modify-save because this runs after every provider call and
     * must not contend with anything: it touches one column, takes no row lock beyond the statement,
     * and cannot overwrite a concurrent status change made by the token refresher or by a disconnect.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE ScmConnection c SET c.lastUsedAt = :usedAt WHERE c.id = :id")
    int touchLastUsedAt(@Param("id") Integer id, @Param("usedAt") Instant usedAt);

    /**
     * Attributes an inbound webhook to a connection.
     *
     * <p>Ordered by id so that repeated deliveries resolve to the same connection when several users
     * have connected the same provider account, keeping delivery attribution stable.
     */
    List<ScmConnection> findByProviderIdAndExternalAccountIdOrderByIdAsc(Integer providerId,
                                                                        String externalAccountId);

    List<ScmConnection> findByProviderIdAndConnectionStatusOrderByIdAsc(Integer providerId,
                                                                       ScmConnectionStatus status);

    boolean existsByUserIdAndProviderIdAndExternalAccountId(Integer userId,
                                                            Integer providerId,
                                                            String externalAccountId);
}
