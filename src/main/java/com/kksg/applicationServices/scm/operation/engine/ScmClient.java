package com.kksg.applicationServices.scm.operation.engine;

import com.kksg.applicationServices.scm.common.model.ScmCapabilityCode;
import com.kksg.applicationServices.scm.common.model.ScmOperationRequest;
import com.kksg.applicationServices.scm.common.model.ScmOperationResponse;
import com.kksg.applicationServices.scm.connection.entity.ScmConnection;
import com.kksg.applicationServices.scm.provider.entity.ScmProvider;

/**
 * <b>The public entry point of Module 2.</b> Everything Modules 3-8 need from SCM integration is
 * reachable through this interface.
 *
 * <p>A caller names a normalized operation and supplies a connection. It does not know the URL, the
 * HTTP method, the authentication header, the paging convention or the response shape - all of those
 * come from database configuration for the connection's provider:
 *
 * <pre>{@code
 * ScmOperationResponse response = scmClient.execute(
 *         connection,
 *         ScmOperationRequest.of(ScmOperationCode.LIST_REPOSITORIES).page(1, 50));
 *
 * List<NormalizedRepository> repositories = response.asList(NormalizedRepository.class);
 * }</pre>
 *
 * <p>The identical call works for a GitHub connection and a Bitbucket connection. This is the property
 * that lets Module 3 store repositories without knowing where they came from, and it is why no caller
 * should ever branch on {@code providerCode}.
 *
 * <p>The provider is derived from the connection rather than passed separately: passing both would
 * make it possible to combine a connection with the wrong provider's configuration, and that pairing
 * is already fixed by the connection row.
 */
public interface ScmClient {

    /**
     * Executes an operation using a stored connection's credentials, refreshing them first if the
     * provider issues expiring tokens.
     *
     * @throws com.kksg.applicationServices.scm.common.exception.ScmException with
     *         {@code SCM_OPERATION_NOT_SUPPORTED} when the provider does not declare the capability,
     *         {@code SCM_OPERATION_NOT_CONFIGURED} when no active operation row exists,
     *         {@code SCM_OPERATION_PARAMETER_MISSING} when a required parameter is absent,
     *         {@code SCM_CONNECTION_EXPIRED} when credentials cannot be renewed, or
     *         {@code SCM_PROVIDER_API_ERROR} when the provider rejects the call.
     */
    ScmOperationResponse execute(ScmConnection connection, ScmOperationRequest request);

    /**
     * Executes an operation with a caller-supplied access token instead of a stored connection.
     *
     * <p>Exists for the OAuth connect flow, which must call {@code GET_CURRENT_ACCOUNT} to discover
     * the provider account identity <i>before</i> a connection row can be created - the account id is
     * part of that row's unique key. Restricted to that bootstrap case; ordinary callers use
     * {@link #execute} so that token refresh and expiry handling are not bypassed.
     */
    ScmOperationResponse executeWithToken(ScmProvider provider, String accessToken, ScmOperationRequest request);

    /**
     * @return whether the provider declares support for a capability. Lets a caller choose a strategy
     *         up front rather than discovering a limitation from a failed request.
     */
    boolean supports(ScmProvider provider, ScmCapabilityCode capabilityCode);
}
