package com.kksg.applicationServices.repository.service;

import com.kksg.applicationServices.identity.entity.User;
import com.kksg.applicationServices.repository.dto.ScmResourceProvider;
import com.kksg.applicationServices.scm.common.exception.ScmErrorCode;
import com.kksg.applicationServices.scm.common.exception.ScmException;
import com.kksg.applicationServices.scm.connection.entity.ScmConnection;
import com.kksg.applicationServices.scm.connection.service.ScmConnectionService;
import com.kksg.applicationServices.scm.provider.entity.ScmProvider;
import com.kksg.applicationServices.scm.provider.service.ScmProviderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single gate every repository and pull-request request passes through.
 *
 * <p>It answers one question - <i>may this authenticated user act through this connection?</i> - and
 * returns a {@link ScmResourceContext} as proof. Everything below it takes that context as a
 * parameter, so there is no code path that reaches a provider without having been through here.
 *
 * <p><b>Three checks, in this order, and the order matters:</b>
 * <ol>
 *   <li><b>Ownership.</b> Delegated to {@code ScmConnectionService.requireOwned}, which filters by
 *       {@code userId} inside the query. Another user's connection id is reported as <i>not found</i>,
 *       not <i>forbidden</i> - a 403 would confirm the id exists and turn the endpoint into an oracle
 *       for enumerating other users' connections.</li>
 *   <li><b>Usability.</b> A disconnected connection has had its credentials destroyed, so using it
 *       cannot work; saying so as a 409 lets a client offer "reconnect" instead of showing an
 *       unexplained provider failure.</li>
 *   <li><b>Provider activity.</b> Checked last because it is the operator's switch rather than
 *       anything about this user, and a provider turned off mid-session should not be reported in a way
 *       that implies the user's connection is at fault.</li>
 * </ol>
 *
 * <p><b>What this service deliberately does not check is repository and pull-request access.</b> That
 * is not an omission - it is where the enforcement actually belongs. The platform holds no record of
 * which repositories a connection can see, and inventing one would mean maintaining a stale mirror of
 * the provider's permissions. Instead every resource call is made <i>with that connection's
 * credential</i>, so the provider itself decides: a repository the token cannot see answers 404, which
 * the engine reports as {@code SCM_PROVIDER_RESOURCE_NOT_FOUND} and the calling service translates into
 * {@code SCM_REPOSITORY_NOT_FOUND}. The authorization chain is therefore complete without the platform
 * ever having to guess:
 *
 * <pre>
 *   authenticated user  --(owns, this service)-->  connection
 *   connection          --(credential scope, provider)-->  repository
 *   repository          --(URL path, provider)-->  pull request
 * </pre>
 *
 * <p>Note the last link: a pull request is always addressed <i>within</i> a repository path, so a
 * number belonging to a different repository resolves to nothing rather than to someone else's pull
 * request. Nothing is trusted because the client sent it.
 */
@Service
public class ScmResourceAccessService {

    private static final Logger log = LoggerFactory.getLogger(ScmResourceAccessService.class);

    private final ScmConnectionService connectionService;
    private final ScmProviderService providerService;

    public ScmResourceAccessService(ScmConnectionService connectionService,
                                    ScmProviderService providerService) {
        this.connectionService = connectionService;
        this.providerService = providerService;
    }

    /**
     * Resolves a connection the caller is entitled to use.
     *
     * <p>Transactional and read-only so the lazy {@code provider} association can be read while
     * building the context. The transaction ends here: it must not be held across the outbound provider
     * call that follows, or database pool capacity becomes a function of provider latency.
     *
     * @throws ScmException {@link ScmErrorCode#SCM_CONNECTION_NOT_FOUND} when the connection does not
     *         exist or belongs to another user, {@link ScmErrorCode#SCM_CONNECTION_NOT_ACTIVE} when it
     *         may not be used, {@link ScmErrorCode#SCM_PROVIDER_INACTIVE} when its provider is off.
     */
    @Transactional(readOnly = true)
    public ScmResourceContext requireUsableConnection(User user, Integer connectionId) {
        if (user == null) {
            // Unreachable through the controllers - Spring Security rejects an unauthenticated request
            // long before this - but a null principal must never be read as "no ownership constraint".
            throw new ScmException(ScmErrorCode.SCM_CONNECTION_NOT_FOUND);
        }

        ScmConnection connection = connectionService.requireOwned(user, connectionId);
        connectionService.requireUsable(connection);

        ScmProvider provider = connection.getProvider();
        providerService.requireActive(provider);

        log.debug("SCM_RESOURCE_ACCESS_GRANTED: userId={}, connectionId={}, providerCode={}",
                user.getId(), connection.getId(), provider.getProviderCode());

        return new ScmResourceContext(connection, user.getId(),
                new ScmResourceProvider(provider.getProviderCode(), provider.getProviderName()));
    }
}
