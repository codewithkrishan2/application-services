package com.kksg.applicationServices.scm.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Stable, machine-readable error vocabulary for the SCM integration layer.
 *
 * <p>Each constant owns its HTTP status so that {@code GlobalExceptionHandler} needs exactly one
 * handler for the whole module instead of one per failure mode. Clients should branch on
 * {@link #name()} rather than on the human-readable message, which may be reworded.
 *
 * <p><b>Security note:</b> the {@code defaultMessage} values are deliberately generic. Provider
 * responses frequently echo back request details, and OAuth error payloads have been known to
 * include client identifiers; nothing derived from a token, client secret, webhook secret or
 * authorization code is ever placed in a message that reaches a client.
 */
public enum ScmErrorCode {

    SCM_PROVIDER_NOT_FOUND(HttpStatus.NOT_FOUND, "SCM provider not found"),
    SCM_PROVIDER_INACTIVE(HttpStatus.CONFLICT, "SCM provider is not active"),
    SCM_PROVIDER_CONFIGURATION_INVALID(HttpStatus.INTERNAL_SERVER_ERROR, "SCM provider configuration is invalid"),

    SCM_CONNECTION_NOT_FOUND(HttpStatus.NOT_FOUND, "SCM connection not found"),
    SCM_CONNECTION_ALREADY_EXISTS(HttpStatus.CONFLICT, "SCM connection already exists"),
    SCM_CONNECTION_EXPIRED(HttpStatus.UNAUTHORIZED, "SCM connection has expired and must be reauthorized"),
    SCM_CONNECTION_REVOKED(HttpStatus.UNAUTHORIZED, "SCM connection has been revoked"),
    /**
     * The connection exists and belongs to the caller but may not be used for provider calls -
     * {@code DISCONNECTED}, or a status {@code ScmConnection.isUsable()} excludes.
     *
     * <p>Distinct from {@link #SCM_CONNECTION_EXPIRED}, which is a 401 because reauthorization is the
     * fix and a client should treat it as an authentication problem. This is a 409: the resource is
     * real, the request was well formed, and the conflict is with the connection's current state.
     */
    SCM_CONNECTION_NOT_ACTIVE(HttpStatus.CONFLICT, "SCM connection is not active"),

    /**
     * A caller-supplied value failed validation before any provider call - an out-of-range page size,
     * an unrecognised pull-request state filter, a blank repository segment.
     *
     * <p>Separate from {@link #SCM_OPERATION_PARAMETER_MISSING}, which means a <i>provider's</i>
     * configured operation requires a parameter the caller did not supply. This one means the caller
     * supplied a value the platform itself rejects.
     */
    SCM_REQUEST_INVALID(HttpStatus.BAD_REQUEST, "Request is not valid"),

    SCM_OPERATION_NOT_SUPPORTED(HttpStatus.BAD_REQUEST, "Operation is not supported by this provider"),
    SCM_OPERATION_NOT_CONFIGURED(HttpStatus.INTERNAL_SERVER_ERROR, "Operation is not configured for this provider"),
    SCM_OPERATION_PARAMETER_MISSING(HttpStatus.BAD_REQUEST, "Required operation parameter is missing"),
    SCM_RESPONSE_MAPPING_INVALID(HttpStatus.INTERNAL_SERVER_ERROR, "Provider response could not be normalized"),

    SCM_OAUTH_EXCHANGE_FAILED(HttpStatus.BAD_GATEWAY, "Failed to complete provider authorization"),
    /**
     * The provider <b>refused the grant itself</b> - an OAuth {@code invalid_grant},
     * {@code invalid_client} or equivalent.
     *
     * <p>Separated from {@link #SCM_OAUTH_EXCHANGE_FAILED} because the two differ in whether retrying
     * can ever work, and that difference decides behaviour rather than wording. A 5xx or a timeout at
     * the token endpoint is transient and the next attempt may well succeed; a rejected grant means
     * the credential is dead - consumed, rotated away or withdrawn at the provider - and every
     * subsequent attempt will fail identically.
     *
     * <p>Treating the two alike is what produced an unbounded retry loop: a connection whose refresh
     * token had been revoked was marked {@code EXPIRED}, which is a usable status, so the very next
     * API call attempted the same doomed exchange, and so did the one after that. This code is what
     * lets a refresh failure be recorded as terminal.
     */
    SCM_OAUTH_REFRESH_REJECTED(HttpStatus.UNAUTHORIZED,
            "The provider rejected the stored credential; the connection must be reauthorized"),
    SCM_OAUTH_STATE_INVALID(HttpStatus.BAD_REQUEST, "Authorization state is invalid or expired"),

    SCM_WEBHOOK_SIGNATURE_INVALID(HttpStatus.UNAUTHORIZED, "Webhook signature verification failed"),
    SCM_WEBHOOK_ALREADY_PROCESSED(HttpStatus.OK, "Webhook delivery has already been processed"),
    SCM_WEBHOOK_EVENT_NOT_MAPPED(HttpStatus.OK, "Webhook event is not mapped and was ignored"),

    SCM_PROVIDER_API_ERROR(HttpStatus.BAD_GATEWAY, "Provider API request failed"),
    SCM_PROVIDER_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Provider API rate limit exceeded"),
    /**
     * The provider answered 404/410 for the addressed resource.
     *
     * <p>Kept distinct from {@link #SCM_PROVIDER_API_ERROR} because the two call for opposite
     * reactions: a 502 says "the integration or the provider is unwell, retrying may help", while this
     * says "what you asked for is not there, or this credential cannot see it". Providers deliberately
     * answer 404 rather than 403 for a private resource a token lacks access to, so the two cases are
     * indistinguishable here by design - and the caller translates this into a resource-specific code
     * ({@link #SCM_REPOSITORY_NOT_FOUND}, {@link #SCM_PULL_REQUEST_NOT_FOUND}) using the context it
     * has and the engine does not.
     */
    SCM_PROVIDER_RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "Requested provider resource was not found"),

    SCM_REPOSITORY_NOT_FOUND(HttpStatus.NOT_FOUND,
            "Repository not found, or not accessible through this connection"),
    SCM_PULL_REQUEST_NOT_FOUND(HttpStatus.NOT_FOUND, "Pull request not found in this repository"),

    /**
     * The provider could not find the account scope that owns this connection's repositories.
     *
     * <p>Distinct from {@link #SCM_REPOSITORY_NOT_FOUND} because the user asked for no repository -
     * they asked for a <i>list</i>, and the container the list lives in is what is missing. On
     * providers with no cross-account listing endpoint, repositories can only be read within a named
     * scope (a workspace, an organisation), and that scope is derived from the connection. A 404 on
     * the listing therefore means the derived scope is wrong or invisible, not that a repository is
     * absent - and the two call for completely different remedies.
     *
     * <p>Reported separately so a client can say "we could not resolve the workspace for this
     * account" instead of the previous, unactionable "the provider could not find what was
     * requested".
     */
    SCM_REPOSITORY_SCOPE_NOT_FOUND(HttpStatus.NOT_FOUND,
            "The provider account scope that owns this connection's repositories could not be found"),

    SCM_SECRET_NOT_FOUND(HttpStatus.INTERNAL_SERVER_ERROR, "Stored credential could not be resolved"),
    SCM_SECRET_STORAGE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "Credential could not be stored securely");

    private final HttpStatus httpStatus;
    private final String defaultMessage;

    ScmErrorCode(HttpStatus httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}
