package com.kksg.applicationServices.scm.connection.service;

import com.kksg.applicationServices.scm.common.exception.ScmException;
import com.kksg.applicationServices.scm.connection.entity.ScmConnection;
import com.kksg.applicationServices.scm.connection.repository.ScmConnectionRepository;
import com.kksg.applicationServices.scm.provider.config.ProviderConfigurationFactory;
import com.kksg.applicationServices.scm.provider.entity.ScmProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Renews provider credentials before a user request needs them.
 *
 * <p><b>What this adds.</b> Refresh itself already existed, on demand: a request that found an
 * expiring token renewed it inline. That is correct but it is the last possible moment, and it has two
 * costs. The user pays the token-endpoint latency inside their own request, and - worse - if the
 * exchange fails there is no slack left: the request fails, and so does every request after it until
 * someone reconnects. A token that expired overnight is discovered by whoever happens to arrive first.
 *
 * <p>Sweeping ahead of expiry turns that into a non-event. A failure found here is retried on the next
 * tick, several times over, before any user is affected.
 *
 * <p><b>It reuses the on-demand path rather than reimplementing it.</b> Every renewal goes through
 * {@link ScmTokenRefresher#refresh}, which holds a row lock and re-checks after acquiring it. So the
 * sweep and a concurrent user request cannot both exchange the same refresh token - whichever arrives
 * second waits, re-reads, finds a valid token and does nothing. Writing a second refresh
 * implementation here is precisely how that race would have been reintroduced.
 *
 * <p><b>Provider-agnostic.</b> Whether a provider can refresh at all is read from its configuration
 * ({@code oauth.supportsRefresh}), never from its code. GitHub OAuth App tokens do not expire and no
 * refresh token is issued, so GitHub connections have a null expiry and the query never returns them;
 * no branch is needed to express that.
 *
 * <p>Switched off with {@code scm.token-refresh.enabled=false}, which leaves the on-demand path in
 * place.
 */
@Component
@ConditionalOnProperty(name = "scm.token-refresh.enabled", havingValue = "true", matchIfMissing = true)
public class ScmTokenRefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(ScmTokenRefreshScheduler.class);

    private final ScmConnectionRepository connectionRepository;
    private final ScmTokenRefresher tokenRefresher;
    private final ProviderConfigurationFactory configurationFactory;
    private final ScmTokenRefreshProperties properties;

    public ScmTokenRefreshScheduler(ScmConnectionRepository connectionRepository,
                                    ScmTokenRefresher tokenRefresher,
                                    ProviderConfigurationFactory configurationFactory,
                                    ScmTokenRefreshProperties properties) {
        this.connectionRepository = connectionRepository;
        this.tokenRefresher = tokenRefresher;
        this.configurationFactory = configurationFactory;
        this.properties = properties;
    }

    /**
     * One sweep.
     *
     * <p>{@code fixedDelayString} rather than {@code fixedRate}: the delay is measured from the end of
     * the previous run, so a slow sweep - many connections, a slow provider - cannot overlap itself and
     * double the outbound call rate.
     */
    @Scheduled(fixedDelayString = "${scm.token-refresh.interval:PT5M}",
            initialDelayString = "${scm.token-refresh.interval:PT5M}")
    public void refreshExpiringConnections() {
        Instant dueBefore = Instant.now().plus(properties.getLeadTime());

        List<ScmConnection> due = findDue(dueBefore);
        if (due.isEmpty()) {
            return;
        }

        int renewed = 0;
        int skipped = 0;
        int failed = 0;

        for (ScmConnection connection : due) {
            ScmProvider provider = connection.getProvider();

            // Configuration, not a provider name. A provider that issues no refresh token cannot be
            // renewed, and attempting it would burn a token-endpoint call to learn what the
            // configuration already says.
            if (provider == null
                    || !configurationFactory.get(provider).oauthOrEmpty().supportsRefreshOrDefault()) {
                skipped++;
                continue;
            }

            try {
                // Through the locking refresher, so a concurrent user request cannot redeem the same
                // refresh token. If that request got there first this is a no-op re-read.
                tokenRefresher.refresh(connection.getId(), provider, properties.getLeadTime());
                renewed++;
            } catch (ScmException ex) {
                // One connection's failure must not abandon the rest of the batch. The refresher has
                // already recorded the outcome on the row - REVOKED for a rejected grant, status
                // untouched for a transient failure - so there is nothing to decide here.
                failed++;
                log.warn("SCM_TOKEN_SWEEP_CONNECTION_FAILED: connectionId={}, providerCode={}, errorCode={}",
                        connection.getId(), provider.getProviderCode(), ex.getErrorCode());
            } catch (RuntimeException ex) {
                failed++;
                log.error("SCM_TOKEN_SWEEP_CONNECTION_ERROR: connectionId={}, cause={}",
                        connection.getId(), ex.getClass().getSimpleName(), ex);
            }
        }

        log.info("SCM_TOKEN_SWEEP_COMPLETED: candidates={}, renewed={}, skipped={}, failed={}, leadTime={}",
                due.size(), renewed, skipped, failed, properties.getLeadTime());
    }

    /**
     * Reads the batch in its own short transaction.
     *
     * <p>Separate from the refresh loop on purpose: the loop makes outbound HTTP calls, and holding a
     * transaction across them would tie database pool capacity to provider latency - the same reason
     * the operation engine is not transactional.
     */
    @Transactional(readOnly = true)
    protected List<ScmConnection> findDue(Instant dueBefore) {
        return connectionRepository.findDueForRefresh(
                dueBefore, PageRequest.of(0, Math.max(1, properties.getBatchSize())));
    }
}
