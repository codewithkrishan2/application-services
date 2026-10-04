package com.kksg.applicationServices.scm.connection.service;

import com.kksg.applicationServices.scm.connection.dto.ScmConnectionReadiness;
import com.kksg.applicationServices.scm.connection.entity.ScmConnection;
import com.kksg.applicationServices.scm.connection.entity.ScmConnectionStatus;
import com.kksg.applicationServices.scm.provider.config.ProviderConfiguration;
import com.kksg.applicationServices.scm.provider.config.ProviderConfigurationFactory;
import com.kksg.applicationServices.scm.provider.entity.ScmProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * The single definition of "can this connection be used right now".
 *
 * <p><b>Why a derived signal rather than another stored column.</b> Readiness depends on the current
 * time, so storing it would mean storing something that is wrong between writes - a token expiring in
 * ten minutes is {@code EXPIRING} now and {@code REFRESHABLE} later with no event in between to trigger
 * an update.
 *
 * <p><b>The disagreement it resolves.</b> {@link ScmConnection#isUsable()} accepts {@code EXPIRED},
 * because the token service can often renew it. The frontend read the same {@code EXPIRED} status and
 * asked the user to reconnect. Both were reading one field to answer two different questions, so the UI
 * demanded consent the backend did not need. The rule below is what settles it: expired <i>and
 * renewable without the user</i> is {@link ScmConnectionReadiness#REFRESHABLE} - usable, no user action.
 *
 * <p>Renewability is two independent facts, and the tests keep them independent: the provider declares
 * {@code oauth.supportsRefresh} (configuration, never a provider name), and this particular connection
 * holds a refresh credential. A provider that supports refresh is no help to a grant that never
 * received a refresh token.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScmConnectionReadinessResolverTest {

    @Mock
    private ProviderConfigurationFactory configurationFactory;

    private ScmProvider provider;
    private ScmConnectionReadinessResolver resolver;

    @BeforeEach
    void setUp() {
        provider = new ScmProvider();
        provider.setId(1);
        provider.setProviderCode("TESTHUB");
        supportsRefresh(true);
        resolver = new ScmConnectionReadinessResolver(configurationFactory);
    }

    private void supportsRefresh(boolean supported) {
        ProviderConfiguration.OAuth oauth = new ProviderConfiguration.OAuth(
                null, null, List.of(), null, null, null, null, null, supported);
        when(configurationFactory.get(provider))
                .thenReturn(new ProviderConfiguration(null, oauth, null, null, null));
    }

    private ScmConnection connection(ScmConnectionStatus status, Instant expiry) {
        ScmConnection connection = new ScmConnection();
        connection.setId(7);
        connection.setProvider(provider);
        connection.setConnectionStatus(status);
        connection.setAccessTokenReference("access-ref");
        connection.setRefreshTokenReference("refresh-ref");
        connection.setTokenExpiry(expiry);
        return connection;
    }

    @Nested
    @DisplayName("expired but renewable is usable without the user")
    class ExpiredAndRenewable {

        @Test
        @DisplayName("EXPIRED with a refresh credential is REFRESHABLE, not reauthorization")
        void expiredWithRefreshTokenIsRefreshable() {
            // The case the old single-field model got wrong, and the reason this class exists.
            ScmConnectionReadiness readiness =
                    resolver.resolve(connection(ScmConnectionStatus.EXPIRED, Instant.now().minusSeconds(60)));

            assertThat(readiness).isEqualTo(ScmConnectionReadiness.REFRESHABLE);
            assertThat(readiness.isUsable()).isTrue();
            assertThat(readiness.requiresUserAction()).isFalse();
        }

        @Test
        @DisplayName("an elapsed expiry on an ACTIVE row is also REFRESHABLE")
        void elapsedExpiryOnActiveRowIsRefreshable() {
            // Status lags reality: nothing rewrites the row the moment a token expires. Readiness is
            // computed from the clock, so it does not need anything to have run.
            assertThat(resolver.resolve(connection(ScmConnectionStatus.ACTIVE, Instant.now().minusSeconds(1))))
                    .isEqualTo(ScmConnectionReadiness.REFRESHABLE);
        }

        @Test
        @DisplayName("EXPIRED with no refresh credential needs the user back")
        void expiredWithoutRefreshTokenNeedsReauthorization() {
            ScmConnection connection = connection(ScmConnectionStatus.EXPIRED, Instant.now().minusSeconds(60));
            connection.setRefreshTokenReference(null);

            ScmConnectionReadiness readiness = resolver.resolve(connection);

            assertThat(readiness).isEqualTo(ScmConnectionReadiness.REAUTHORIZATION_REQUIRED);
            assertThat(readiness.requiresUserAction()).isTrue();
        }

        @Test
        @DisplayName("EXPIRED on a provider that cannot refresh needs the user back")
        void expiredOnNonRefreshingProviderNeedsReauthorization() {
            // Read from configuration. A provider that issues non-expiring tokens and no refresh token
            // genuinely cannot be renewed silently, and that is a config fact, not a provider name.
            supportsRefresh(false);

            assertThat(resolver.resolve(connection(ScmConnectionStatus.EXPIRED, Instant.now().minusSeconds(60))))
                    .isEqualTo(ScmConnectionReadiness.REAUTHORIZATION_REQUIRED);
        }
    }

    @Nested
    @DisplayName("healthy connections")
    class Healthy {

        @Test
        @DisplayName("a token with plenty of life left is READY")
        void comfortableExpiryIsReady() {
            assertThat(resolver.resolve(connection(ScmConnectionStatus.ACTIVE, Instant.now().plusSeconds(7200))))
                    .isEqualTo(ScmConnectionReadiness.READY);
        }

        @Test
        @DisplayName("a null expiry is READY, not a warning")
        void nullExpiryIsReady() {
            // Normal for providers whose OAuth App tokens do not expire. Reporting it as a problem
            // would put a permanent warning on a perfectly healthy connection.
            assertThat(resolver.resolve(connection(ScmConnectionStatus.ACTIVE, null)))
                    .isEqualTo(ScmConnectionReadiness.READY);
        }

        @Test
        @DisplayName("a token inside the 15-minute window is EXPIRING and still usable")
        void nearExpiryIsExpiring() {
            ScmConnectionReadiness readiness =
                    resolver.resolve(connection(ScmConnectionStatus.ACTIVE, Instant.now().plusSeconds(300)));

            assertThat(readiness).isEqualTo(ScmConnectionReadiness.EXPIRING);
            // Advisory only. The token still works, and the sweep is already about to renew it.
            assertThat(readiness.isUsable()).isTrue();
            assertThat(readiness.requiresUserAction()).isFalse();
        }

        @Test
        @DisplayName("the EXPIRING window matches the sweep's lead time")
        void expiringWindowMatchesSweepLeadTime() {
            // Deliberately the same 15 minutes the proactive sweep uses. A shorter window here would
            // report "expiring" for connections the sweep has in fact already renewed; a longer one
            // would warn about connections nothing is yet acting on.
            assertThat(resolver.resolve(connection(ScmConnectionStatus.ACTIVE, Instant.now().plusSeconds(840))))
                    .isEqualTo(ScmConnectionReadiness.EXPIRING);
            assertThat(resolver.resolve(connection(ScmConnectionStatus.ACTIVE, Instant.now().plusSeconds(960))))
                    .isEqualTo(ScmConnectionReadiness.READY);
        }
    }

    @Nested
    @DisplayName("terminal states are reported before credentials are inspected")
    class TerminalStates {

        @Test
        @DisplayName("REVOKED requires reauthorization even with a refresh credential present")
        void revokedRequiresReauthorization() {
            // A refresh token is worthless once the authorization is withdrawn, and attempting to use
            // it is what the terminal/transient split exists to prevent.
            ScmConnectionReadiness readiness =
                    resolver.resolve(connection(ScmConnectionStatus.REVOKED, Instant.now().plusSeconds(7200)));

            assertThat(readiness).isEqualTo(ScmConnectionReadiness.REAUTHORIZATION_REQUIRED);
            assertThat(readiness.isUsable()).isFalse();
        }

        @Test
        @DisplayName("DISCONNECTED is distinct from needing reauthorization")
        void disconnectedIsItsOwnState() {
            // The user removed it on purpose. Prompting them to reconnect would be nagging, so the UI
            // needs to tell the two apart.
            ScmConnectionReadiness readiness =
                    resolver.resolve(connection(ScmConnectionStatus.DISCONNECTED, null));

            assertThat(readiness).isEqualTo(ScmConnectionReadiness.DISCONNECTED);
            assertThat(readiness.isUsable()).isFalse();
            assertThat(readiness.requiresUserAction()).isFalse();
        }

        @Test
        @DisplayName("ERROR is reported as ERROR")
        void errorStatusIsError() {
            assertThat(resolver.resolve(connection(ScmConnectionStatus.ERROR, null)))
                    .isEqualTo(ScmConnectionReadiness.ERROR);
        }

        @Test
        @DisplayName("a missing access credential requires reauthorization regardless of status")
        void missingAccessCredentialRequiresReauthorization() {
            // Nothing to use and nothing to renew from. Reporting READY here would produce a connection
            // the UI offers and every call rejects.
            ScmConnection connection = connection(ScmConnectionStatus.ACTIVE, Instant.now().plusSeconds(7200));
            connection.setAccessTokenReference(null);

            assertThat(resolver.resolve(connection))
                    .isEqualTo(ScmConnectionReadiness.REAUTHORIZATION_REQUIRED);
        }
    }

    @Nested
    @DisplayName("degenerate input never throws")
    class DegenerateInput {

        @Test
        @DisplayName("a null connection resolves to ERROR")
        void nullConnectionIsError() {
            // Readiness is computed while rendering a list. Throwing here would fail the whole response
            // because one row was odd.
            assertThat(resolver.resolve(null)).isEqualTo(ScmConnectionReadiness.ERROR);
        }

        @Test
        @DisplayName("a null status resolves to ERROR")
        void nullStatusIsError() {
            assertThat(resolver.resolve(connection(null, null))).isEqualTo(ScmConnectionReadiness.ERROR);
        }

        @Test
        @DisplayName("a connection with no provider is not treated as renewable")
        void missingProviderIsNotRenewable() {
            // Renewability cannot be established, so the safe answer is the one that asks the user -
            // not the one that keeps calling a token endpoint on a guess.
            ScmConnection connection = connection(ScmConnectionStatus.EXPIRED, Instant.now().minusSeconds(60));
            connection.setProvider(null);

            assertThat(resolver.resolve(connection))
                    .isEqualTo(ScmConnectionReadiness.REAUTHORIZATION_REQUIRED);
        }

        @ParameterizedTest(name = "{0} resolves to a readiness value")
        @EnumSource(ScmConnectionStatus.class)
        @DisplayName("every stored status maps to something")
        void everyStatusMaps(ScmConnectionStatus status) {
            // Guards the switch: a new ScmConnectionStatus added later must not silently fall through
            // to whatever the default branch happens to be.
            assertThat(resolver.resolve(connection(status, Instant.now().plusSeconds(7200)))).isNotNull();
        }
    }

    @Nested
    @DisplayName("the usable/user-action contract")
    class UsabilityContract {

        @Test
        @DisplayName("usable and requiresUserAction are never both true")
        void usableAndUserActionAreExclusive() {
            // The frontend branches on these two independently. A value that claimed both would make
            // the UI show a working connection and a reconnect prompt at the same time.
            for (ScmConnectionReadiness readiness : ScmConnectionReadiness.values()) {
                assertThat(readiness.isUsable() && readiness.requiresUserAction())
                        .as("%s claims to be both usable and in need of user action", readiness)
                        .isFalse();
            }
        }

        @Test
        @DisplayName("only READY, EXPIRING and REFRESHABLE are usable")
        void usableSetIsExplicit() {
            assertThat(ScmConnectionReadiness.values())
                    .filteredOn(ScmConnectionReadiness::isUsable)
                    .containsExactlyInAnyOrder(
                            ScmConnectionReadiness.READY,
                            ScmConnectionReadiness.EXPIRING,
                            ScmConnectionReadiness.REFRESHABLE);
        }
    }
}
