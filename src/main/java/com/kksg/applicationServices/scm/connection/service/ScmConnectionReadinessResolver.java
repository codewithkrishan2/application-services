package com.kksg.applicationServices.scm.connection.service;

import com.kksg.applicationServices.scm.connection.dto.ScmConnectionReadiness;
import com.kksg.applicationServices.scm.connection.entity.ScmConnection;
import com.kksg.applicationServices.scm.connection.entity.ScmConnectionStatus;
import com.kksg.applicationServices.scm.provider.config.ProviderConfigurationFactory;
import com.kksg.applicationServices.scm.provider.entity.ScmProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Derives {@link ScmConnectionReadiness} from a connection's stored state.
 *
 * <p>One place, so that the API, the UI and any future scheduler agree on what "usable" means. The
 * previous arrangement had each consumer inferring it from {@code connectionStatus}, and they did not
 * agree: the frontend treated {@code EXPIRED} as needing user action while {@code ScmConnection.isUsable()}
 * treated it as fine, so the UI asked for consent the backend did not need.
 *
 * <p>The decisive input is whether the credential can be renewed <b>without the user</b>, which is two
 * facts: the provider declares {@code oauth.supportsRefresh}, and this connection actually holds a
 * refresh credential. Both are required - a provider that supports refresh is no help if this
 * particular connection never received a refresh token.
 */
@Component
public class ScmConnectionReadinessResolver {

    /**
     * How close to expiry counts as {@link ScmConnectionReadiness#EXPIRING}.
     *
     * <p>Matches the proactive sweep's default lead time, so a connection reported as expiring is one
     * the sweep is already about to handle. A shorter window here would report "expiring" for
     * connections that have in fact already been renewed.
     */
    private static final Duration EXPIRING_WINDOW = Duration.ofMinutes(15);

    private final ProviderConfigurationFactory configurationFactory;

    public ScmConnectionReadinessResolver(ProviderConfigurationFactory configurationFactory) {
        this.configurationFactory = configurationFactory;
    }

    public ScmConnectionReadiness resolve(ScmConnection connection) {
        if (connection == null || connection.getConnectionStatus() == null) {
            return ScmConnectionReadiness.ERROR;
        }

        ScmConnectionStatus status = connection.getConnectionStatus();

        // Terminal states first: no amount of credential inspection changes them.
        switch (status) {
            case DISCONNECTED -> {
                return ScmConnectionReadiness.DISCONNECTED;
            }
            case REVOKED -> {
                return ScmConnectionReadiness.REAUTHORIZATION_REQUIRED;
            }
            case ERROR -> {
                return ScmConnectionReadiness.ERROR;
            }
            default -> {
                // ACTIVE and EXPIRED depend on the credential, handled below.
            }
        }

        // No stored access credential means nothing to use and nothing to renew from.
        if (connection.getAccessTokenReference() == null) {
            return ScmConnectionReadiness.REAUTHORIZATION_REQUIRED;
        }

        boolean renewable = isRenewableWithoutUser(connection);

        if (status == ScmConnectionStatus.EXPIRED) {
            // The case the old model got wrong. Expired is only terminal when nothing can renew it.
            return renewable
                    ? ScmConnectionReadiness.REFRESHABLE
                    : ScmConnectionReadiness.REAUTHORIZATION_REQUIRED;
        }

        Instant expiry = connection.getTokenExpiry();
        if (expiry == null) {
            // A null expiry means the provider issues non-expiring tokens - normal for GitHub OAuth
            // Apps, and not something to warn about.
            return ScmConnectionReadiness.READY;
        }

        Instant now = Instant.now();
        if (now.isAfter(expiry)) {
            return renewable
                    ? ScmConnectionReadiness.REFRESHABLE
                    : ScmConnectionReadiness.REAUTHORIZATION_REQUIRED;
        }
        if (now.plus(EXPIRING_WINDOW).isAfter(expiry)) {
            return ScmConnectionReadiness.EXPIRING;
        }
        return ScmConnectionReadiness.READY;
    }

    /**
     * @return whether this connection's credential can be renewed with no user involvement.
     *
     * <p>Both halves matter. The provider flag is configuration - never a provider name - and the
     * reference check is per connection, because a provider that supports refresh still issues no
     * refresh token to a grant that did not request offline access.
     */
    private boolean isRenewableWithoutUser(ScmConnection connection) {
        if (connection.getRefreshTokenReference() == null) {
            return false;
        }
        ScmProvider provider = connection.getProvider();
        if (provider == null) {
            return false;
        }
        return configurationFactory.get(provider).oauthOrEmpty().supportsRefreshOrDefault();
    }
}
