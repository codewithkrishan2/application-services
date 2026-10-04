package com.kksg.applicationServices.scm.connection.service;

import com.kksg.applicationServices.scm.common.exception.ScmErrorCode;
import com.kksg.applicationServices.scm.common.exception.ScmException;
import com.kksg.applicationServices.scm.connection.entity.ScmConnection;
import com.kksg.applicationServices.scm.connection.entity.ScmConnectionStatus;
import com.kksg.applicationServices.scm.connection.repository.ScmConnectionRepository;
import com.kksg.applicationServices.scm.provider.config.ProviderConfiguration;
import com.kksg.applicationServices.scm.provider.config.ProviderConfigurationFactory;
import com.kksg.applicationServices.scm.provider.entity.ScmProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The proactive sweep: renewing credentials before a user request needs them.
 *
 * <p><b>What it is for.</b> On-demand refresh already existed and was correct, but it runs at the last
 * possible moment. The user pays the token-endpoint latency inside their own request, and if the
 * exchange fails there is no slack: that request fails and so does every one after it. Sweeping ahead of
 * expiry turns a failure into a non-event, because the next tick tries again long before anyone notices.
 *
 * <p><b>The two properties worth protecting, and the reasons they are easy to get wrong.</b>
 * <ul>
 *   <li><b>It delegates to {@link ScmTokenRefresher} rather than exchanging tokens itself.</b> That is
 *       what keeps the row lock in play, so the sweep and a concurrent user request cannot both redeem
 *       the same refresh token. A second refresh implementation here is precisely how that race would be
 *       reintroduced - so the test asserts the delegation, not the outcome.</li>
 *   <li><b>One connection's failure must not abandon the batch.</b> A single revoked grant that aborted
 *       the sweep would stop every other tenant's token being renewed.</li>
 * </ul>
 *
 * <p>{@code findDue} is invoked through the real instance; the transactional boundary it carries is
 * Spring's concern and is not what these tests are about.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScmTokenRefreshSchedulerTest {

    @Mock
    private ScmConnectionRepository connectionRepository;

    @Mock
    private ScmTokenRefresher tokenRefresher;

    @Mock
    private ProviderConfigurationFactory configurationFactory;

    private ScmProvider refreshingProvider;
    private ScmProvider nonRefreshingProvider;
    private ScmTokenRefreshProperties properties;
    private ScmTokenRefreshScheduler scheduler;

    @BeforeEach
    void setUp() {
        refreshingProvider = provider(2, "TESTHUB", true);
        nonRefreshingProvider = provider(3, "NOREFRESH", false);

        properties = new ScmTokenRefreshProperties();
        properties.setLeadTime(Duration.ofMinutes(15));
        properties.setBatchSize(50);

        scheduler = new ScmTokenRefreshScheduler(
                connectionRepository, tokenRefresher, configurationFactory, properties);
    }

    private ScmProvider provider(int id, String code, boolean supportsRefresh) {
        ScmProvider provider = new ScmProvider();
        provider.setId(id);
        provider.setProviderCode(code);
        ProviderConfiguration.OAuth oauth = new ProviderConfiguration.OAuth(
                null, null, List.of(), null, null, null, null, null, supportsRefresh);
        when(configurationFactory.get(provider))
                .thenReturn(new ProviderConfiguration(null, oauth, null, null, null));
        return provider;
    }

    private ScmConnection connection(int id, ScmProvider provider) {
        ScmConnection connection = new ScmConnection();
        connection.setId(id);
        connection.setProvider(provider);
        connection.setConnectionStatus(ScmConnectionStatus.ACTIVE);
        connection.setRefreshTokenReference("refresh-ref-" + id);
        connection.setTokenExpiry(Instant.now().plusSeconds(300));
        return connection;
    }

    private void due(ScmConnection... connections) {
        when(connectionRepository.findDueForRefresh(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(connections));
    }

    @Nested
    @DisplayName("it reuses the locking refresher")
    class Delegation {

        @Test
        @DisplayName("each due connection is renewed through ScmTokenRefresher")
        void renewsThroughTheRefresher() {
            due(connection(10, refreshingProvider), connection(11, refreshingProvider));

            scheduler.refreshExpiringConnections();

            // Asserting the collaborator, not the result: going through the refresher is what holds the
            // row lock and makes a concurrent user request wait, re-read and skip the exchange.
            verify(tokenRefresher).refresh(10, refreshingProvider, properties.getLeadTime());
            verify(tokenRefresher).refresh(11, refreshingProvider, properties.getLeadTime());
        }

        @Test
        @DisplayName("the refresher is given the sweep's lead time as its skew")
        void passesLeadTimeAsSkew() {
            properties.setLeadTime(Duration.ofMinutes(30));
            due(connection(10, refreshingProvider));

            scheduler.refreshExpiringConnections();

            // The refresher re-checks after taking the lock, and it must use the same margin the query
            // selected on - otherwise it would decide renewal was unnecessary and the sweep would
            // silently do nothing on every tick.
            verify(tokenRefresher).refresh(10, refreshingProvider, Duration.ofMinutes(30));
        }

        @Test
        @DisplayName("an empty batch does no work at all")
        void emptyBatchIsANoOp() {
            due();

            scheduler.refreshExpiringConnections();

            verify(tokenRefresher, never()).refresh(anyInt(), any(), any());
        }
    }

    @Nested
    @DisplayName("provider capability comes from configuration")
    class ProviderCapability {

        @Test
        @DisplayName("a provider that cannot refresh is skipped without a provider call")
        void nonRefreshingProviderIsSkipped() {
            // Read from oauth.supportsRefresh - no provider name anywhere. Attempting the exchange would
            // burn a token-endpoint call to learn what configuration already states.
            due(connection(20, nonRefreshingProvider));

            scheduler.refreshExpiringConnections();

            verify(tokenRefresher, never()).refresh(anyInt(), any(), any());
        }

        @Test
        @DisplayName("a connection with no provider is skipped rather than crashing the sweep")
        void missingProviderIsSkipped() {
            ScmConnection orphan = connection(21, refreshingProvider);
            orphan.setProvider(null);
            due(orphan);

            scheduler.refreshExpiringConnections();

            verify(tokenRefresher, never()).refresh(anyInt(), any(), any());
        }

        @Test
        @DisplayName("refreshable and non-refreshable providers are handled in the same batch")
        void mixedBatchIsHandledPerConnection() {
            due(connection(22, nonRefreshingProvider), connection(23, refreshingProvider));

            scheduler.refreshExpiringConnections();

            verify(tokenRefresher, never()).refresh(eq(22), any(), any());
            verify(tokenRefresher).refresh(23, refreshingProvider, properties.getLeadTime());
        }
    }

    @Nested
    @DisplayName("one failure does not abandon the batch")
    class FailureIsolation {

        @Test
        @DisplayName("a rejected grant does not stop later connections being renewed")
        void terminalFailureDoesNotStopTheBatch() {
            // The failure mode that matters at scale: one tenant's revoked authorization must not stop
            // every other tenant's token from being renewed.
            due(connection(30, refreshingProvider), connection(31, refreshingProvider));
            when(tokenRefresher.refresh(eq(30), any(), any()))
                    .thenThrow(new ScmException(ScmErrorCode.SCM_CONNECTION_REVOKED, "connectionId=30"));

            scheduler.refreshExpiringConnections();

            verify(tokenRefresher).refresh(31, refreshingProvider, properties.getLeadTime());
        }

        @Test
        @DisplayName("an unexpected runtime failure also does not stop the batch")
        void runtimeFailureDoesNotStopTheBatch() {
            due(connection(32, refreshingProvider), connection(33, refreshingProvider));
            when(tokenRefresher.refresh(eq(32), any(), any()))
                    .thenThrow(new IllegalStateException("database connection lost"));

            scheduler.refreshExpiringConnections();

            verify(tokenRefresher).refresh(33, refreshingProvider, properties.getLeadTime());
        }

        @Test
        @DisplayName("the sweep records nothing on the connection itself")
        void sweepDoesNotWriteStatus() {
            due(connection(34, refreshingProvider));
            when(tokenRefresher.refresh(eq(34), any(), any()))
                    .thenThrow(new ScmException(ScmErrorCode.SCM_CONNECTION_REVOKED, "connectionId=34"));

            scheduler.refreshExpiringConnections();

            // The refresher already recorded the outcome on the locked row, inside its own transaction.
            // A second write here would race its own delegate and could overwrite a REVOKED status.
            verify(connectionRepository, never()).save(any(ScmConnection.class));
        }
    }

    @Nested
    @DisplayName("the batch is bounded")
    class Bounding {

        @Test
        @DisplayName("the configured batch size is applied to the query")
        void batchSizeIsApplied() {
            properties.setBatchSize(7);
            due(connection(40, refreshingProvider));

            scheduler.refreshExpiringConnections();

            // A bound, not a target. Each renewal is an outbound call, so an unbounded sweep on a
            // deployment where many tokens expire together would compete with live traffic for the same
            // rate limit. The backlog is drained by the next tick, in expiry order.
            assertThat(capturedPageable().getPageSize()).isEqualTo(7);
        }

        @Test
        @DisplayName("a non-positive batch size still produces a valid page request")
        void nonPositiveBatchSizeIsClamped() {
            // Misconfiguration must degrade to "renew one per tick", not to an IllegalArgumentException
            // thrown on a scheduler thread every five minutes.
            properties.setBatchSize(0);
            due(connection(41, refreshingProvider));

            scheduler.refreshExpiringConnections();

            assertThat(capturedPageable().getPageSize()).isEqualTo(1);
        }

        @Test
        @DisplayName("the query looks ahead by the lead time")
        void queryLooksAheadByLeadTime() {
            properties.setLeadTime(Duration.ofMinutes(15));
            due(connection(42, refreshingProvider));

            Instant before = Instant.now();
            scheduler.refreshExpiringConnections();

            // Renewing only what has already expired would defeat the purpose: the first user request
            // would still find a dead token.
            assertThat(capturedDueBefore())
                    .isAfterOrEqualTo(before.plus(Duration.ofMinutes(15)).minusSeconds(5));
        }

        private Pageable capturedPageable() {
            ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            verify(connectionRepository).findDueForRefresh(any(Instant.class), captor.capture());
            return captor.getValue();
        }

        private Instant capturedDueBefore() {
            ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
            verify(connectionRepository).findDueForRefresh(captor.capture(), any(Pageable.class));
            return captor.getValue();
        }
    }
}
