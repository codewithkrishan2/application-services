package com.kksg.applicationServices.repository.service;

import com.kksg.applicationServices.common.response.PageResponse;
import com.kksg.applicationServices.identity.entity.User;
import com.kksg.applicationServices.repository.dto.RepositoryResponse;
import com.kksg.applicationServices.repository.mapper.RepositoryMapper;
import com.kksg.applicationServices.scm.common.exception.ScmErrorCode;
import com.kksg.applicationServices.scm.common.exception.ScmException;
import com.kksg.applicationServices.scm.common.model.NormalizedRepository;
import com.kksg.applicationServices.scm.common.model.ScmOperationCode;
import com.kksg.applicationServices.scm.common.model.ScmOperationRequest;
import com.kksg.applicationServices.scm.common.model.ScmOperationResponse;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Repository browsing: what the application does with repositories, as opposed to how they are
 * fetched.
 *
 * <p>Its whole job is orchestration - authorize, name an operation, hand back a DTO. There is no
 * provider name here, no URL, no HTTP, and no branch on {@code providerCode}, because every one of
 * those lives behind {@code ScmClient} and must stay there. The layering the module relies on:
 *
 * <pre>
 *   controller
 *      -> this service              application behaviour, authorization, response shape
 *      -> ScmPageScanner            paging and search, resource-independent
 *      -> ScmOperationRunner        logging, error naming, usage recording
 *      -> ScmClient                 the SCM operation engine (Module 2)
 *      -> provider configuration    endpoint, auth, paging, response mapping - all database rows
 * </pre>
 *
 * <p><b>Nothing is persisted.</b> Repositories are provider resources, not application records: a
 * {@code repositories} table would be a mirror that is stale the moment it is written, and would have
 * to answer questions this module does not need to ask (what happens on rename, on transfer, on access
 * being revoked). Indexing is a later feature with its own requirements.
 *
 * <p><b>Not transactional.</b> Deliberately, and for the reason the engine documents: a transaction
 * open across an outbound provider call ties database pool capacity to provider latency. The one piece
 * of work that needs a transaction - resolving and authorizing the connection - takes its own short
 * one inside {@link ScmResourceAccessService}.
 */
@Service
public class RepositoryService {

    private final ScmResourceAccessService accessService;
    private final ScmPageScanner pageScanner;
    private final ScmOperationRunner operationRunner;

    public RepositoryService(ScmResourceAccessService accessService,
                             ScmPageScanner pageScanner,
                             ScmOperationRunner operationRunner) {
        this.accessService = accessService;
        this.pageScanner = pageScanner;
        this.operationRunner = operationRunner;
    }

    /**
     * Repositories reachable through one of the caller's connections.
     *
     * <p>Which repositories those are is decided by the provider, from the scope of the stored
     * credential - not by anything this application records. That is what makes the listing correct
     * without a permissions mirror, and it is why a repository the user has lost access to simply stops
     * appearing.
     *
     * @throws com.kksg.applicationServices.scm.common.exception.ScmException
     *         {@code SCM_CONNECTION_NOT_FOUND} when the connection is not the caller's,
     *         {@code SCM_CONNECTION_NOT_ACTIVE} when it cannot be used,
     *         {@code SCM_OPERATION_NOT_SUPPORTED} when the provider does not offer the listing,
     *         {@code SCM_PROVIDER_RATE_LIMITED} or {@code SCM_PROVIDER_API_ERROR} on provider failure.
     */
    public PageResponse<RepositoryResponse> listRepositories(User user, Integer connectionId,
                                                             PageQuery query) {
        ScmResourceContext context = accessService.requireUsableConnection(user, connectionId);

        return pageScanner.fetchPage(context,
                ScmOperationCode.LIST_REPOSITORIES,
                Map.of(),
                NormalizedRepository.class,
                query,
                repository -> RepositoryMapper.matches(repository, query.search()),
                repository -> RepositoryMapper.toResponse(repository, context.provider()),
                // A 404 on the listing is not a missing repository - the request names none. It means
                // the account scope the listing is made within could not be found, which on a provider
                // with no cross-account listing endpoint is the scope derived from this connection.
                // Naming it lets a client say "we could not resolve the workspace for this account"
                // rather than the unactionable "the provider could not find what was requested".
                ScmErrorCode.SCM_REPOSITORY_SCOPE_NOT_FOUND);
    }

    /**
     * One repository, addressed by owner and name.
     *
     * <p>A repository the connection's credential cannot see answers 404 at the provider, which arrives
     * here as {@code SCM_REPOSITORY_NOT_FOUND}. That is the authorization check: a user cannot read
     * another user's repository by guessing its name, because the call is made with <i>their</i>
     * credential and the provider refuses it. Providers answer 404 rather than 403 for an invisible
     * private repository precisely so that its existence is not disclosed, and that property is
     * preserved rather than unpicked here.
     */
    public RepositoryResponse getRepository(User user, Integer connectionId, RepositoryRef ref) {
        ScmResourceContext context = accessService.requireUsableConnection(user, connectionId);

        ScmOperationResponse response = operationRunner.run(context,
                ScmOperationRequest.of(ScmOperationCode.GET_REPOSITORY)
                        .parameter("owner", ref.owner())
                        .parameter("repo", ref.name()),
                ScmErrorCode.SCM_REPOSITORY_NOT_FOUND);

        NormalizedRepository repository = response.as(NormalizedRepository.class);
        if (repository == null) {
            // A 2xx that normalizes to nothing means the provider's response did not match its
            // configured mapping. Reporting it as "not found" would send an operator looking at
            // permissions for what is actually a configuration fault, so it is not translated.
            throw new ScmException(ScmErrorCode.SCM_RESPONSE_MAPPING_INVALID,
                    "operation=GET_REPOSITORY returned no repository");
        }
        return RepositoryMapper.toResponse(repository, context.provider());
    }
}
