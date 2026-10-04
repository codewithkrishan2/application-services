package com.kksg.applicationServices.scm.connection.service;

import com.kksg.applicationServices.scm.common.exception.ScmErrorCode;
import com.kksg.applicationServices.scm.common.exception.ScmException;
import com.kksg.applicationServices.scm.common.model.ScmTokenSet;
import com.kksg.applicationServices.scm.connection.entity.ScmConnection;
import com.kksg.applicationServices.scm.connection.entity.ScmConnectionStatus;
import com.kksg.applicationServices.scm.connection.repository.ScmConnectionRepository;
import com.kksg.applicationServices.scm.provider.entity.ScmProvider;
import com.kksg.applicationServices.scm.secret.ScmSecretStore;
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

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a failed refresh does to the connection - which is the difference between a connection the user
 * is asked to fix and a connection that quietly hammers the provider's token endpoint.
 *
 * <p><b>The bug these tests pin down.</b> Every refresh failure used to set {@code EXPIRED}.
 * {@link ScmConnection#isUsable()} deliberately accepts {@code EXPIRED} - expiry alone should not
 * disqualify a connection, because refresh may well fix it - so a connection whose refresh token had
 * been revoked stayed usable, and the next API request walked straight back into the same doomed
 * exchange. One token-endpoint call per user request, indefinitely, and the UI never asked for consent
 * because nothing recorded that consent was needed.
 *
 * <p>The fix splits failures in two, and the two assertions that matter are:
 * <ul>
 *   <li>a rejected grant leaves the connection in a state {@code isUsable()} rejects - that is the
 *       thing that actually stops the loop, not the error code;</li>
 *   <li>a transient failure leaves the status untouched, so a provider hiccup does not cost the user a
 *       trip through consent.</li>
 * </ul>
 *
 * <p>The locking is not asserted here because it is not this class's behaviour: it is the
 * {@code @Transactional} proxy plus {@code findByIdForUpdate}. What <i>is</i> asserted is the
 * observable consequence - the post-lock re-check means a caller that lost the race performs no
 * exchange at all.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScmTokenRefresherTest {

    private static final int CONNECTION_ID = 42;
    private static final Duration SKEW = Duration.ofSeconds(60);
    private static final String ACCESS_REF = "secret-ref-access";
    private static final String REFRESH_REF = "secret-ref-refresh";

    @Mock
    private ScmConnectionRepository connectionRepository;

    @Mock
    private ScmSecretStore secretStore;

    @Mock
    private ScmOAuthTokenExchanger tokenExchanger;

    private ScmProvider provider;
    private ScmConnection connection;
    private ScmTokenRefresher refresher;

    @BeforeEach
    void setUp() {
        provider = new ScmProvider();
        provider.setId(1);
        provider.setProviderCode("TESTHUB");

        connection = new ScmConnection();
        connection.setId(CONNECTION_ID);
        connection.setProvider(provider);
        connection.setConnectionStatus(ScmConnectionStatus.ACTIVE);
        connection.setAccessTokenReference(ACCESS_REF);
        connection.setRefreshTokenReference(REFRESH_REF);
        // Already expired, so the post-lock re-check agrees renewal is needed.
        connection.setTokenExpiry(Instant.now().minusSeconds(120));

        when(connectionRepository.findByIdForUpdate(CONNECTION_ID)).thenReturn(Optional.of(connection));
        when(connectionRepository.save(any(ScmConnection.class))).thenAnswer(i -> i.getArgument(0));
        when(secretStore.retrieve(REFRESH_REF)).thenReturn(Optional.of("stored-refresh-token"));
        when(secretStore.retrieve(ACCESS_REF)).thenReturn(Optional.of("stored-access-token"));
        when(secretStore.update(anyString(), anyString(), anyString()))
                .thenAnswer(i -> i.getArgument(0));

        refresher = new ScmTokenRefresher(connectionRepository, secretStore, tokenExchanger);
    }

    private void exchangeFailsWith(ScmErrorCode code) {
        when(tokenExchanger.refreshAccessToken(eq(provider), anyString()))
                .thenThrow(new ScmException(code, "providerCode=TESTHUB"));
    }

    @Nested
    @DisplayName("a rejected grant is terminal")
    class TerminalFailure {

        @Test
        @DisplayName("sets REVOKED, which is the state that stops the retry loop")
        void rejectedGrantRevokesTheConnection() {
            exchangeFailsWith(ScmErrorCode.SCM_OAUTH_REFRESH_REJECTED);

            assertThatThrownBy(() -> refresher.refresh(CONNECTION_ID, provider, SKEW))
                    .isInstanceOf(ScmException.class)
                    .extracting(ex -> ((ScmException) ex).getErrorCode())
                    .isEqualTo(ScmErrorCode.SCM_CONNECTION_REVOKED);

            assertThat(connection.getConnectionStatus()).isEqualTo(ScmConnectionStatus.REVOKED);

            // This is the assertion that actually matters. The error code is cosmetic by comparison:
            // what breaks the loop is that the next caller fails the usability check in
            // ScmTokenService before any provider call is attempted.
            assertThat(connection.isUsable()).isFalse();

            verify(connectionRepository).save(connection);
        }

        @Test
        @DisplayName("does not keep the old credentials usable by writing new ones")
        void rejectedGrantWritesNoTokens() {
            exchangeFailsWith(ScmErrorCode.SCM_OAUTH_REFRESH_REJECTED);

            assertThatThrownBy(() -> refresher.refresh(CONNECTION_ID, provider, SKEW))
                    .isInstanceOf(ScmException.class);

            verify(secretStore, never()).update(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("preserves the provider's error as the cause")
        void rejectedGrantKeepsTheCause() {
            // The caller needs a connection-level code to act on; support needs to know which
            // token-endpoint verdict produced it. Chaining gives both without logging a credential.
            exchangeFailsWith(ScmErrorCode.SCM_OAUTH_REFRESH_REJECTED);

            assertThatThrownBy(() -> refresher.refresh(CONNECTION_ID, provider, SKEW))
                    .hasCauseInstanceOf(ScmException.class)
                    .extracting(ex -> ((ScmException) ex.getCause()).getErrorCode())
                    .isEqualTo(ScmErrorCode.SCM_OAUTH_REFRESH_REJECTED);
        }
    }

    @Nested
    @DisplayName("anything else is transient")
    class TransientFailure {

        @ParameterizedTest(name = "{0} leaves the status untouched")
        @EnumSource(value = ScmErrorCode.class, names = {
                "SCM_OAUTH_EXCHANGE_FAILED", "SCM_PROVIDER_API_ERROR", "SCM_PROVIDER_RATE_LIMITED"})
        @DisplayName("a transient failure does not revoke a working connection")
        void transientFailureLeavesStatusAlone(ScmErrorCode code) {
            exchangeFailsWith(code);

            assertThatThrownBy(() -> refresher.refresh(CONNECTION_ID, provider, SKEW))
                    .isInstanceOf(ScmException.class)
                    .extracting(ex -> ((ScmException) ex).getErrorCode())
                    // Reported as expired, not revoked: a later attempt may well succeed.
                    .isEqualTo(ScmErrorCode.SCM_CONNECTION_EXPIRED);

            // Marking this REVOKED would send the user through consent because the provider had a bad
            // minute. The proactive sweep gets several more attempts before any user is affected.
            assertThat(connection.getConnectionStatus()).isEqualTo(ScmConnectionStatus.ACTIVE);
        }

        @Test
        @DisplayName("a transient failure writes nothing to the row")
        void transientFailurePersistsNothing() {
            exchangeFailsWith(ScmErrorCode.SCM_OAUTH_EXCHANGE_FAILED);

            assertThatThrownBy(() -> refresher.refresh(CONNECTION_ID, provider, SKEW))
                    .isInstanceOf(ScmException.class);

            verify(connectionRepository, never()).save(any(ScmConnection.class));
        }

        @Test
        @DisplayName("an already-EXPIRED connection stays EXPIRED and therefore stays retryable")
        void expiredConnectionStaysRetryable() {
            connection.setConnectionStatus(ScmConnectionStatus.EXPIRED);
            exchangeFailsWith(ScmErrorCode.SCM_OAUTH_EXCHANGE_FAILED);

            assertThatThrownBy(() -> refresher.refresh(CONNECTION_ID, provider, SKEW))
                    .isInstanceOf(ScmException.class);

            assertThat(connection.getConnectionStatus()).isEqualTo(ScmConnectionStatus.EXPIRED);
            assertThat(connection.isUsable()).isTrue();
        }
    }

    @Nested
    @DisplayName("the successful path")
    class Success {

        @Test
        @DisplayName("stores the new token, reactivates the connection and returns it")
        void successStoresAndReactivates() {
            Instant expiry = Instant.now().plusSeconds(7200);
            when(tokenExchanger.refreshAccessToken(eq(provider), anyString()))
                    .thenReturn(new ScmTokenSet("new-access", null, expiry, "repo"));

            String token = refresher.refresh(CONNECTION_ID, provider, SKEW);

            assertThat(token).isEqualTo("new-access");
            assertThat(connection.getTokenExpiry()).isEqualTo(expiry);
            // Back to ACTIVE even from EXPIRED: a successful renewal is exactly what clears that state.
            assertThat(connection.getConnectionStatus()).isEqualTo(ScmConnectionStatus.ACTIVE);
            assertThat(connection.getLastUsedAt()).isNotNull();
            verify(secretStore).update(ACCESS_REF, ScmConnectionTokens.ACCESS_TOKEN_LABEL, "new-access");
            verify(connectionRepository).save(connection);
        }

        @Test
        @DisplayName("redeems the stored refresh token, not the access token")
        void successUsesTheStoredRefreshToken() {
            when(tokenExchanger.refreshAccessToken(eq(provider), anyString()))
                    .thenReturn(new ScmTokenSet("new-access", null, null, null));

            refresher.refresh(CONNECTION_ID, provider, SKEW);

            verify(tokenExchanger).refreshAccessToken(provider, "stored-refresh-token");
        }

        @Test
        @DisplayName("a rotated refresh token replaces the stored one")
        void rotatedRefreshTokenIsStored() {
            // Providers that rotate on every redemption make this mandatory: keeping the old value
            // would break the next renewal and look like a revoked grant.
            when(tokenExchanger.refreshAccessToken(eq(provider), anyString()))
                    .thenReturn(new ScmTokenSet("new-access", "rotated-refresh", null, null));

            refresher.refresh(CONNECTION_ID, provider, SKEW);

            verify(secretStore).update(REFRESH_REF, ScmConnectionTokens.REFRESH_TOKEN_LABEL, "rotated-refresh");
        }

        @Test
        @DisplayName("an omitted refresh token leaves the stored one intact")
        void omittedRefreshTokenIsNotOverwritten() {
            // Several providers omit refresh_token from a refresh response and expect the original to
            // keep working. Overwriting it with null would permanently break the connection.
            when(tokenExchanger.refreshAccessToken(eq(provider), anyString()))
                    .thenReturn(new ScmTokenSet("new-access", null, null, null));

            refresher.refresh(CONNECTION_ID, provider, SKEW);

            verify(secretStore, never())
                    .update(eq(REFRESH_REF), eq(ScmConnectionTokens.REFRESH_TOKEN_LABEL), any());
            assertThat(connection.getRefreshTokenReference()).isEqualTo(REFRESH_REF);
        }

        @Test
        @DisplayName("the secret reference is reused rather than replaced")
        void secretReferenceIsReused() {
            when(tokenExchanger.refreshAccessToken(eq(provider), anyString()))
                    .thenReturn(new ScmTokenSet("new-access", null, null, null));

            refresher.refresh(CONNECTION_ID, provider, SKEW);

            // Rotation in place, so a refresh does not orphan a secret row or contend on the
            // connection row's reference column.
            assertThat(connection.getAccessTokenReference()).isEqualTo(ACCESS_REF);
        }
    }

    @Nested
    @DisplayName("the concurrency re-check")
    class ConcurrencyRecheck {

        @Test
        @DisplayName("a caller that lost the race performs no exchange")
        void concurrentWinnerShortCircuits() {
            // Simulates the state after another thread refreshed while this one waited on the row lock:
            // the row now holds a healthy expiry.
            connection.setTokenExpiry(Instant.now().plusSeconds(7200));

            String token = refresher.refresh(CONNECTION_ID, provider, SKEW);

            assertThat(token).isEqualTo("stored-access-token");
            // The whole point. Redeeming a refresh token twice is how a working connection gets
            // destroyed - providers commonly invalidate the old one on redemption, so the loser's
            // exchange both fails and can overwrite the stored token with a rejected value.
            verify(tokenExchanger, never()).refreshAccessToken(any(), anyString());
            verify(connectionRepository, never()).save(any(ScmConnection.class));
        }

        @Test
        @DisplayName("the re-check uses the caller's own skew so the two cannot disagree")
        void recheckUsesCallerSkew() {
            // Expiry inside the caller's skew window: the caller decided renewal was needed, and the
            // re-check must reach the same conclusion. A different margin here would let a token
            // oscillate between "renew" and "no need" on every request.
            connection.setTokenExpiry(Instant.now().plusSeconds(30));
            when(tokenExchanger.refreshAccessToken(eq(provider), anyString()))
                    .thenReturn(new ScmTokenSet("new-access", null, null, null));

            refresher.refresh(CONNECTION_ID, provider, SKEW);

            verify(tokenExchanger).refreshAccessToken(provider, "stored-refresh-token");
        }
    }

    @Nested
    @DisplayName("preconditions")
    class Preconditions {

        @Test
        @DisplayName("a missing connection is reported as missing")
        void missingConnection() {
            when(connectionRepository.findByIdForUpdate(CONNECTION_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> refresher.refresh(CONNECTION_ID, provider, SKEW))
                    .isInstanceOf(ScmException.class)
                    .extracting(ex -> ((ScmException) ex).getErrorCode())
                    .isEqualTo(ScmErrorCode.SCM_CONNECTION_NOT_FOUND);
        }

        @Test
        @DisplayName("an unresolvable refresh token asks for reauthorization")
        void unresolvableRefreshToken() {
            // The row references a secret the store cannot produce - a rotated encryption key, or a
            // deleted secret row. Nothing to retry with, so the user must reconnect.
            when(secretStore.retrieve(REFRESH_REF)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> refresher.refresh(CONNECTION_ID, provider, SKEW))
                    .isInstanceOf(ScmException.class)
                    .extracting(ex -> ((ScmException) ex).getErrorCode())
                    .isEqualTo(ScmErrorCode.SCM_CONNECTION_EXPIRED);

            verify(tokenExchanger, never()).refreshAccessToken(any(), anyString());
        }

        @Test
        @DisplayName("an unresolvable access token on the short-circuit path is reported, not returned null")
        void unresolvableAccessTokenOnShortCircuit() {
            connection.setTokenExpiry(Instant.now().plusSeconds(7200));
            when(secretStore.retrieve(ACCESS_REF)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> refresher.refresh(CONNECTION_ID, provider, SKEW))
                    .isInstanceOf(ScmException.class)
                    .extracting(ex -> ((ScmException) ex).getErrorCode())
                    .isEqualTo(ScmErrorCode.SCM_CONNECTION_EXPIRED);
        }
    }
}
