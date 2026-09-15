package com.kksg.applicationServices.scm.connection.repository;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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
