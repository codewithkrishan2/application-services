package com.kksg.applicationServices.repository.service;

import com.kksg.applicationServices.identity.entity.User;
import com.kksg.applicationServices.scm.common.exception.ScmErrorCode;
import com.kksg.applicationServices.scm.common.exception.ScmException;
import com.kksg.applicationServices.scm.connection.entity.ScmConnection;
import com.kksg.applicationServices.scm.connection.entity.ScmConnectionStatus;
import com.kksg.applicationServices.scm.connection.repository.ScmConnectionRepository;
import com.kksg.applicationServices.scm.connection.service.ScmConnectionReadinessResolver;
import com.kksg.applicationServices.scm.connection.service.ScmConnectionService;
import com.kksg.applicationServices.scm.connection.service.ScmTokenService;
import com.kksg.applicationServices.scm.provider.entity.ScmProvider;
import com.kksg.applicationServices.scm.provider.service.ScmProviderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The authorization gate. <b>This is the security-critical test in the module.</b>
 *
 * <p>Three properties are asserted, and the first is the one that matters most:
 * <ol>
 *   <li>User A cannot reach User B's connection, <b>and is told it does not exist rather than that it
 *       is forbidden</b>. A 403 would confirm the id is real and turn the endpoint into an oracle for
 *       enumerating other users' connections.</li>
 *   <li>A connection that cannot be used is a 409, not a 404 - the caller owns it and reconnecting is
 *       the fix, so it must not be reported as missing.</li>
 *   <li>A deactivated provider stops work immediately, checked on every use rather than at connect
 *       time.</li>
 * </ol>
 *
 * <p>A real {@code ScmConnectionService} is used over a mocked repository rather than mocking the
 * service itself, so the ownership-scoped query is genuinely exercised. Mocking the service would have
 * tested that this class calls a method, not that an id belonging to someone else is actually refused.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScmResourceAccessServiceTest {

    private static final int USER_A = 1;
    private static final int USER_B = 2;
    private static final int CONNECTION_ID = 77;

    @Mock
    private ScmConnectionRepository connectionRepository;

    @Mock
    private ScmTokenService tokenService;

    @Mock
    private ScmProviderService providerService;

    @Mock
    private ScmConnectionReadinessResolver readinessResolver;

    private ScmConnectionService connectionService;
    private ScmResourceAccessService accessService;

    @BeforeEach
    void setUp() {
        connectionService = new ScmConnectionService(connectionRepository, tokenService, readinessResolver);
        accessService = new ScmResourceAccessService(connectionService, providerService);
    }

    private User user(int id) {
        User user = new User();
        user.setId(id);
        user.setEmail("user%d@example.invalid".formatted(id));
        return user;
    }

    private ScmProvider provider(boolean active) {
        ScmProvider provider = new ScmProvider();
        provider.setId(10);
        provider.setProviderCode("GITHUB");
        provider.setProviderName("GitHub");
        provider.setActive(active);
        return provider;
    }

    private ScmConnection connection(ScmConnectionStatus status, ScmProvider provider) {
        ScmConnection connection = new ScmConnection();
        connection.setId(CONNECTION_ID);
        connection.setConnectionStatus(status);
        connection.setProvider(provider);
        connection.setExternalAccountId("acct-1");
        return connection;
    }

    @Test
    @DisplayName("grants access to the owner of an active connection")
    void grantsAccessToOwner() {
        ScmProvider provider = provider(true);
        when(connectionRepository.findByIdAndUserId(CONNECTION_ID, USER_A))
                .thenReturn(Optional.of(connection(ScmConnectionStatus.ACTIVE, provider)));

        ScmResourceContext context = accessService.requireUsableConnection(user(USER_A), CONNECTION_ID);

        assertThat(context.connectionId()).isEqualTo(CONNECTION_ID);
        assertThat(context.userId()).isEqualTo(USER_A);
        assertThat(context.providerCode()).isEqualTo("GITHUB");
        assertThat(context.provider().name()).isEqualTo("GitHub");
        verify(providerService).requireActive(eq(provider));
    }

    @Test
    @DisplayName("User A cannot reach User B's connection, and is told it does not exist")
    void refusesAnotherUsersConnection() {
        // The connection genuinely exists and belongs to User B. The scoped query is what refuses it,
        // and the response must not reveal that the id is real.
        when(connectionRepository.findByIdAndUserId(CONNECTION_ID, USER_B))
                .thenReturn(Optional.of(connection(ScmConnectionStatus.ACTIVE, provider(true))));
        when(connectionRepository.findByIdAndUserId(CONNECTION_ID, USER_A))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> accessService.requireUsableConnection(user(USER_A), CONNECTION_ID))
                .isInstanceOf(ScmException.class)
                .satisfies(thrown -> {
                    ScmException ex = (ScmException) thrown;
                    assertThat(ex.getErrorCode()).isEqualTo(ScmErrorCode.SCM_CONNECTION_NOT_FOUND);
                    // Not FORBIDDEN: a 403 here would confirm the id exists.
                    assertThat(ex.getErrorCode().getHttpStatus().value()).isEqualTo(404);
                });

        // And the owner is unaffected.
        assertThat(accessService.requireUsableConnection(user(USER_B), CONNECTION_ID).connectionId())
                .isEqualTo(CONNECTION_ID);
    }

    @Test
    @DisplayName("an absent connection is not found")
    void refusesMissingConnection() {
        when(connectionRepository.findByIdAndUserId(CONNECTION_ID, USER_A)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accessService.requireUsableConnection(user(USER_A), CONNECTION_ID))
                .isInstanceOf(ScmException.class)
                .satisfies(thrown -> assertThat(((ScmException) thrown).getErrorCode())
                        .isEqualTo(ScmErrorCode.SCM_CONNECTION_NOT_FOUND));
    }

    @Test
    @DisplayName("a null connection id is not found rather than an unfiltered lookup")
    void refusesNullConnectionId() {
        assertThatThrownBy(() -> accessService.requireUsableConnection(user(USER_A), null))
                .isInstanceOf(ScmException.class)
                .satisfies(thrown -> assertThat(((ScmException) thrown).getErrorCode())
                        .isEqualTo(ScmErrorCode.SCM_CONNECTION_NOT_FOUND));
    }

    @Test
    @DisplayName("a null principal is refused rather than read as no ownership constraint")
    void refusesNullPrincipal() {
        // Unreachable through the controllers, but a null user must never mean "skip the check".
        assertThatThrownBy(() -> accessService.requireUsableConnection(null, CONNECTION_ID))
                .isInstanceOf(ScmException.class)
                .satisfies(thrown -> assertThat(((ScmException) thrown).getErrorCode())
                        .isEqualTo(ScmErrorCode.SCM_CONNECTION_NOT_FOUND));
    }

    @Test
    @DisplayName("a disconnected connection is a conflict, not a missing resource")
    void refusesDisconnectedConnection() {
        when(connectionRepository.findByIdAndUserId(CONNECTION_ID, USER_A))
                .thenReturn(Optional.of(connection(ScmConnectionStatus.DISCONNECTED, provider(true))));

        assertThatThrownBy(() -> accessService.requireUsableConnection(user(USER_A), CONNECTION_ID))
                .isInstanceOf(ScmException.class)
                .satisfies(thrown -> {
                    ScmException ex = (ScmException) thrown;
                    // The caller owns it and reconnecting is the fix, so reporting it as missing would
                    // be both wrong and unhelpful.
                    assertThat(ex.getErrorCode()).isEqualTo(ScmErrorCode.SCM_CONNECTION_NOT_ACTIVE);
                    assertThat(ex.getErrorCode().getHttpStatus().value()).isEqualTo(409);
                });
    }

    @ParameterizedTest
    @EnumSource(value = ScmConnectionStatus.class, names = {"ACTIVE", "EXPIRED"})
    @DisplayName("ACTIVE and EXPIRED both pass the usability check")
    void allowsRefreshableStatuses(ScmConnectionStatus status) {
        // EXPIRED is allowed through on purpose: the token service may still be able to refresh it, so
        // failing here would break connections that are in fact usable.
        when(connectionRepository.findByIdAndUserId(CONNECTION_ID, USER_A))
                .thenReturn(Optional.of(connection(status, provider(true))));

        assertThat(accessService.requireUsableConnection(user(USER_A), CONNECTION_ID)).isNotNull();
    }

    @Test
    @DisplayName("a deactivated provider stops the request")
    void refusesInactiveProvider() {
        ScmProvider inactive = provider(false);
        when(connectionRepository.findByIdAndUserId(CONNECTION_ID, USER_A))
                .thenReturn(Optional.of(connection(ScmConnectionStatus.ACTIVE, inactive)));
        // The real ScmProviderService throws here; the mock is told to do the same.
        org.mockito.Mockito.doThrow(new ScmException(ScmErrorCode.SCM_PROVIDER_INACTIVE))
                .when(providerService).requireActive(eq(inactive));

        assertThatThrownBy(() -> accessService.requireUsableConnection(user(USER_A), CONNECTION_ID))
                .isInstanceOf(ScmException.class)
                .satisfies(thrown -> assertThat(((ScmException) thrown).getErrorCode())
                        .isEqualTo(ScmErrorCode.SCM_PROVIDER_INACTIVE));
    }
}
