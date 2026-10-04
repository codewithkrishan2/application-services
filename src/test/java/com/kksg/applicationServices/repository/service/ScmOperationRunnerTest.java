package com.kksg.applicationServices.repository.service;

import com.kksg.applicationServices.repository.dto.ScmResourceProvider;
import com.kksg.applicationServices.scm.common.exception.ScmErrorCode;
import com.kksg.applicationServices.scm.common.exception.ScmException;
import com.kksg.applicationServices.scm.common.model.ScmOperationCode;
import com.kksg.applicationServices.scm.common.model.ScmOperationRequest;
import com.kksg.applicationServices.scm.connection.entity.ScmConnection;
import com.kksg.applicationServices.scm.connection.service.ScmConnectionService;
import com.kksg.applicationServices.scm.operation.engine.ScmClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The single place provider calls are logged, named and recorded.
 *
 * <p>Three behaviours, each the reason this class exists rather than services calling the engine
 * directly:
 * <ul>
 *   <li><b>404 is renamed per caller context.</b> The engine can only say "the provider says it is not
 *       there"; whether that means a repository or a pull request is knowable only by the caller.</li>
 *   <li><b>Everything else is left alone.</b> Rate limiting, authentication failure and unsupported
 *       operations are already named precisely and each needs a different client reaction, so
 *       flattening them would destroy information.</li>
 *   <li><b>Usage recording cannot fail a read.</b> It is operational metadata; a write error there must
 *       not turn a successful response into an error.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ScmOperationRunnerTest {

    @Mock
    private ScmClient scmClient;

    @Mock
    private ScmConnectionService connectionService;

    private ScmOperationRunner runner;
    private ScmResourceContext context;

    @BeforeEach
    void setUp() {
        runner = new ScmOperationRunner(scmClient, connectionService);

        ScmConnection connection = new ScmConnection();
        connection.setId(9);
        context = new ScmResourceContext(connection, 1, new ScmResourceProvider("GITHUB", "GitHub"));
    }

    private ScmOperationRequest repositoryRequest() {
        return ScmOperationRequest.of(ScmOperationCode.GET_REPOSITORY)
                .parameter("owner", "acme")
                .parameter("repo", "api");
    }

    @Test
    @DisplayName("passes the normalized request to the engine unaltered")
    void delegatesToEngine() {
        ScmOperationRequest request = repositoryRequest();
        when(scmClient.execute(eq(context.connection()), eq(request)))
                .thenReturn(ScmResponses.list(ScmOperationCode.GET_REPOSITORY, List.of(), false));

        runner.run(context, request, ScmErrorCode.SCM_REPOSITORY_NOT_FOUND);

        // No URL built, no provider named, no response field read here - all of that stays behind the
        // engine, exactly as before this module existed.
        verify(scmClient).execute(context.connection(), request);
    }

    @Test
    @DisplayName("renames a provider 404 to the resource the caller addressed")
    void renamesResourceNotFound() {
        when(scmClient.execute(any(), any()))
                .thenThrow(new ScmException(ScmErrorCode.SCM_PROVIDER_RESOURCE_NOT_FOUND,
                        "providerCode=GITHUB operationCode=GET_REPOSITORY"));

        assertThatThrownBy(() ->
                runner.run(context, repositoryRequest(), ScmErrorCode.SCM_REPOSITORY_NOT_FOUND))
                .isInstanceOf(ScmException.class)
                .satisfies(thrown -> assertThat(((ScmException) thrown).getErrorCode())
                        .isEqualTo(ScmErrorCode.SCM_REPOSITORY_NOT_FOUND))
                // The message names what the client asked for, not the provider and operation, which
                // are diagnostic noise in text a user reads.
                .hasMessageContaining("owner=acme")
                .hasMessageContaining("repo=api");
    }

    @Test
    @DisplayName("leaves the generic 404 alone when the caller names no resource")
    void keepsGenericNotFoundWhenNoCodeGiven() {
        when(scmClient.execute(any(), any()))
                .thenThrow(new ScmException(ScmErrorCode.SCM_PROVIDER_RESOURCE_NOT_FOUND));

        assertThatThrownBy(() -> runner.run(context, repositoryRequest(), null))
                .isInstanceOf(ScmException.class)
                .satisfies(thrown -> assertThat(((ScmException) thrown).getErrorCode())
                        .isEqualTo(ScmErrorCode.SCM_PROVIDER_RESOURCE_NOT_FOUND));
    }

    @ParameterizedTest
    @EnumSource(value = ScmErrorCode.class, names = {
            "SCM_PROVIDER_RATE_LIMITED",
            "SCM_CONNECTION_EXPIRED",
            "SCM_OPERATION_NOT_SUPPORTED",
            "SCM_PROVIDER_API_ERROR",
            "SCM_RESPONSE_MAPPING_INVALID"
    })
    @DisplayName("every other failure propagates with its own code intact")
    void propagatesOtherFailuresUnchanged(ScmErrorCode code) {
        when(scmClient.execute(any(), any())).thenThrow(new ScmException(code));

        assertThatThrownBy(() ->
                runner.run(context, repositoryRequest(), ScmErrorCode.SCM_REPOSITORY_NOT_FOUND))
                .isInstanceOf(ScmException.class)
                .satisfies(thrown -> assertThat(((ScmException) thrown).getErrorCode()).isEqualTo(code));
    }

    @Test
    @DisplayName("records the connection as used after a successful call")
    void recordsUsageOnSuccess() {
        when(scmClient.execute(any(), any()))
                .thenReturn(ScmResponses.list(ScmOperationCode.LIST_REPOSITORIES, List.of(), false));

        runner.run(context, repositoryRequest(), null);

        verify(connectionService).markUsed(9);
    }

    @Test
    @DisplayName("does not record usage when the call failed")
    void doesNotRecordUsageOnFailure() {
        when(scmClient.execute(any(), any()))
                .thenThrow(new ScmException(ScmErrorCode.SCM_PROVIDER_API_ERROR));

        assertThatThrownBy(() -> runner.run(context, repositoryRequest(), null))
                .isInstanceOf(ScmException.class);

        // "Last used" should mean "last worked".
        verify(connectionService, never()).markUsed(anyInt());
    }

    @Test
    @DisplayName("suppresses usage recording when asked to")
    void suppressesUsageRecording() {
        when(scmClient.execute(any(), any()))
                .thenReturn(ScmResponses.list(ScmOperationCode.LIST_REPOSITORIES, List.of(), false));

        runner.run(context, repositoryRequest(), null, false);

        // The search scan makes several calls for one request; recording each would be several
        // identical updates in separate transactions.
        verify(connectionService, never()).markUsed(anyInt());
    }

    @Test
    @DisplayName("a failure to record usage does not fail the read")
    void usageRecordingFailureDoesNotBreakTheRead() {
        when(scmClient.execute(any(), any()))
                .thenReturn(ScmResponses.list(ScmOperationCode.LIST_REPOSITORIES, List.of(), false));
        // markUsed guards its own query, but it is REQUIRES_NEW, so a commit failure escapes that guard
        // and surfaces from the proxy. Simulated here as a throw from the call itself.
        doThrow(new RuntimeException("database unavailable")).when(connectionService).markUsed(9);

        // The field is operational curiosity. It must not be able to turn a successful read into a 500.
        assertThat(runner.run(context, repositoryRequest(), null)).isNotNull();
    }
}
