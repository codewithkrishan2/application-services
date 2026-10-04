package com.kksg.applicationServices.repository.service;

import com.kksg.applicationServices.scm.common.exception.ScmErrorCode;
import com.kksg.applicationServices.scm.common.exception.ScmException;
import com.kksg.applicationServices.scm.common.model.ScmOperationRequest;
import com.kksg.applicationServices.scm.common.model.ScmOperationResponse;
import com.kksg.applicationServices.scm.connection.service.ScmConnectionService;
import com.kksg.applicationServices.scm.operation.engine.ScmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Executes one SCM operation on behalf of an authorized request, and owns the three things every such
 * call needs doing around it: observability, resource-specific error naming, and recording that the
 * connection was used.
 *
 * <p>It exists so those three concerns appear once rather than in each of the six endpoints. A service
 * that called {@code ScmClient} directly would work; it would also be the fifth place to forget the
 * {@code SCM_PROVIDER_RESOURCE_NOT_FOUND} translation, and then a user asking for a repository they
 * cannot see would get a 502 that reads like an outage.
 *
 * <p><b>Not a wrapper around the engine's abstraction.</b> It takes a normalized
 * {@link ScmOperationRequest} and hands it to {@link ScmClient} unaltered - no provider is named here,
 * no URL is built, no response field is read. Everything provider-specific stays behind the engine,
 * exactly as before.
 *
 * <h2>Logging</h2>
 * One line per call, with the identifiers an operator needs to reconstruct what happened: user,
 * connection, provider, operation, the resource addressed, duration, and outcome. The engine logs the
 * provider-facing half of the same call (method, URI, status); together they cover both sides.
 *
 * <p>Request parameters are logged through a <b>deliberate allow-list</b> rather than wholesale. The
 * parameter map is the same channel that carries {@code webhookSecret} for write operations, so logging
 * it generically would put a webhook secret in the application log the first time this runner was reused
 * for one. Three keys are named; anything else is not logged, including by a future caller.
 */
@Service
public class ScmOperationRunner {

    private static final Logger log = LoggerFactory.getLogger(ScmOperationRunner.class);

    /**
     * Parameter names safe and useful to log.
     *
     * <p>An allow-list, not a deny-list: a deny-list protects only the secrets someone remembered.
     */
    private static final String[] LOGGABLE_PARAMETERS = {"owner", "repo", "pullRequestNumber"};

    private final ScmClient scmClient;
    private final ScmConnectionService connectionService;

    public ScmOperationRunner(ScmClient scmClient, ScmConnectionService connectionService) {
        this.scmClient = scmClient;
        this.connectionService = connectionService;
    }

    /**
     * Runs an operation and returns the normalized response.
     *
     * @param notFoundAs the error code to report when the provider says the addressed resource does not
     *                   exist. Supplied by the caller because only the caller knows whether the request
     *                   addressed a repository or a pull request, and "not found" is far more useful to
     *                   a client when it names which. Pass {@code null} to let the engine's generic
     *                   {@link ScmErrorCode#SCM_PROVIDER_RESOURCE_NOT_FOUND} through unchanged.
     * @throws ScmException as thrown by the engine, with the 404 case renamed per {@code notFoundAs}.
     *         Rate limiting, authentication failure and unsupported operations propagate untouched -
     *         they are already named precisely and each has a different correct client reaction.
     */
    public ScmOperationResponse run(ScmResourceContext context,
                                    ScmOperationRequest request,
                                    ScmErrorCode notFoundAs) {
        return run(context, request, notFoundAs, true);
    }

    /**
     * Runs an operation, optionally without recording the connection as used.
     *
     * <p>{@code recordUsage = false} exists for callers that make <i>several</i> provider calls to serve
     * one request - the search scan does, by design. "Last used" is a per-request fact, so writing it
     * once per provider page would be five identical updates in five separate transactions for a single
     * keystroke in a filter box. The scan records usage on its first call and suppresses it thereafter.
     */
    public ScmOperationResponse run(ScmResourceContext context,
                                    ScmOperationRequest request,
                                    ScmErrorCode notFoundAs,
                                    boolean recordUsage) {

        long startedAt = System.nanoTime();
        try {
            ScmOperationResponse response = scmClient.execute(context.connection(), request);
            long durationMs = elapsedMs(startedAt);

            log.info("REPO_OPERATION_SUCCEEDED: userId={}, connectionId={}, providerCode={}, "
                            + "operation={}, {}, itemCount={}, hasNext={}, durationMs={}",
                    context.userId(), context.connectionId(), context.providerCode(),
                    request.getOperation(), describeTarget(request), response.getPagination().getItemCount(),
                    response.getPagination().isHasNext(), durationMs);

            // After the response, not before: "last used" should mean "last worked".
            if (recordUsage) {
                recordUsage(context);
            }
            return response;

        } catch (ScmException ex) {
            log.warn("REPO_OPERATION_FAILED: userId={}, connectionId={}, providerCode={}, operation={}, "
                            + "{}, errorCode={}, durationMs={}",
                    context.userId(), context.connectionId(), context.providerCode(),
                    request.getOperation(), describeTarget(request), ex.getErrorCode(),
                    elapsedMs(startedAt));

            if (notFoundAs != null && ex.getErrorCode() == ScmErrorCode.SCM_PROVIDER_RESOURCE_NOT_FOUND) {
                // The detail is rebuilt rather than carried over: the engine's detail names the provider
                // and operation, which is diagnostic noise in a message a user reads, and the resource
                // the client asked for is the part worth telling them about.
                throw new ScmException(notFoundAs, describeTarget(request), ex);
            }
            throw ex;
        }
    }

    /**
     * Records the connection as used, absorbing any failure.
     *
     * <p>Guarded here <b>as well as</b> inside {@code markUsed}, which is not redundant: that method's
     * own {@code try} covers the query, but it is {@code REQUIRES_NEW}, so its commit happens in the
     * transactional proxy <i>after</i> the method body returns. A commit failure therefore escapes the
     * inner guard entirely. Since the whole point of the field is operational curiosity, it must not be
     * able to turn a successful read into a 500 - so the outer call site refuses to let it.
     */
    private void recordUsage(ScmResourceContext context) {
        try {
            connectionService.markUsed(context.connectionId());
        } catch (RuntimeException ex) {
            log.warn("REPO_CONNECTION_TOUCH_FAILED: connectionId={}, reason={}",
                    context.connectionId(), ex.getMessage());
        }
    }

    private long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }

    /**
     * @return the addressed resource as a short {@code key=value} list, drawn only from
     *         {@link #LOGGABLE_PARAMETERS}. Used both in logs and in the client-visible not-found
     *         message, which is why it must contain nothing but these identifiers.
     */
    private String describeTarget(ScmOperationRequest request) {
        Map<String, Object> parameters = request.getParameters();
        StringBuilder description = new StringBuilder();
        for (String name : LOGGABLE_PARAMETERS) {
            Object value = parameters.get(name);
            if (value != null) {
                if (!description.isEmpty()) {
                    description.append(' ');
                }
                description.append(name).append('=').append(value);
            }
        }
        return description.toString();
    }
}
