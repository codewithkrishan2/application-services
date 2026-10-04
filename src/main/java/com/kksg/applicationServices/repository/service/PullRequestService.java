package com.kksg.applicationServices.repository.service;

import com.kksg.applicationServices.common.response.PageResponse;
import com.kksg.applicationServices.identity.entity.User;
import com.kksg.applicationServices.repository.diff.UnifiedDiffParser;
import com.kksg.applicationServices.repository.dto.PullRequestDiffResponse;
import com.kksg.applicationServices.repository.dto.PullRequestFileResponse;
import com.kksg.applicationServices.repository.dto.PullRequestResponse;
import com.kksg.applicationServices.repository.dto.PullRequestStateFilter;
import com.kksg.applicationServices.repository.mapper.PullRequestMapper;
import com.kksg.applicationServices.repository.mapper.RepositoryMapper;
import com.kksg.applicationServices.scm.common.exception.ScmErrorCode;
import com.kksg.applicationServices.scm.common.exception.ScmException;
import com.kksg.applicationServices.scm.common.model.NormalizedPullRequest;
import com.kksg.applicationServices.scm.common.model.NormalizedPullRequestFile;
import com.kksg.applicationServices.scm.common.model.ScmOperationCode;
import com.kksg.applicationServices.scm.common.model.ScmOperationRequest;
import com.kksg.applicationServices.scm.common.model.ScmOperationResponse;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pull-request browsing within a repository.
 *
 * <p>Part of Repository Management rather than a module of its own because a pull request has no
 * meaning outside the repository that contains it, and because the next module - Review Orchestration -
 * is the thing that will act on pull requests. This one only reads them.
 *
 * <p><b>Every pull request is addressed inside a repository path.</b> That is not merely tidy URL
 * design, it is the last link in the authorization chain: a pull-request number from some other
 * repository resolves to nothing, so a client cannot reach a pull request it was not entitled to by
 * substituting a number. Nothing here trusts an identifier because the client supplied it.
 *
 * <p>Read {@link RepositoryService} for the layering and the reasons this class is not transactional
 * and persists nothing.
 */
@Service
public class PullRequestService {

    /** Parameter names the provider operations declare; see the seeded request configurations. */
    private static final String PARAM_OWNER = "owner";
    private static final String PARAM_REPO = "repo";
    private static final String PARAM_PULL_REQUEST_NUMBER = "pullRequestNumber";
    private static final String PARAM_STATE = "state";

    private final ScmResourceAccessService accessService;
    private final ScmPageScanner pageScanner;
    private final ScmOperationRunner operationRunner;
    private final UnifiedDiffParser diffParser;

    public PullRequestService(ScmResourceAccessService accessService,
                              ScmPageScanner pageScanner,
                              ScmOperationRunner operationRunner,
                              UnifiedDiffParser diffParser) {
        this.accessService = accessService;
        this.pageScanner = pageScanner;
        this.operationRunner = operationRunner;
        this.diffParser = diffParser;
    }

    /**
     * Pull requests in a repository, filtered by state.
     *
     * <p><b>The state filter is applied by the provider, not here.</b> The canonical
     * {@link PullRequestStateFilter} is passed straight through as the {@code state} parameter, and each
     * provider's operation configuration declares how to spell it - {@code parameterValueMappings} on
     * {@code LIST_PULL_REQUESTS}. So filtering costs one page of one provider call, rather than fetching
     * everything and discarding most of it, and no provider name appears in this method to make it
     * possible.
     *
     * <p>A 404 here means the <i>repository</i> is unreachable, not the pull requests - there is no
     * pull request in the request yet - so it is reported as such.
     */
    public PageResponse<PullRequestResponse> listPullRequests(User user, Integer connectionId,
                                                              RepositoryRef ref,
                                                              PullRequestStateFilter state,
                                                              PageQuery query) {
        ScmResourceContext context = accessService.requireUsableConnection(user, connectionId);

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put(PARAM_OWNER, ref.owner());
        parameters.put(PARAM_REPO, ref.name());
        parameters.put(PARAM_STATE, state.name());

        return pageScanner.fetchPage(context,
                ScmOperationCode.LIST_PULL_REQUESTS,
                parameters,
                NormalizedPullRequest.class,
                query,
                pullRequest -> PullRequestMapper.matches(pullRequest, query.search()),
                // List rows omit the repository reference: it is identical on every row and the client
                // already knows it from the path it requested.
                pullRequest -> PullRequestMapper.toResponse(pullRequest, null),
                ScmErrorCode.SCM_REPOSITORY_NOT_FOUND);
    }

    /**
     * One pull request, with the repository it belongs to attached.
     *
     * <p>The repository reference is built from the request path rather than fetched, so the detail page
     * costs one provider call instead of two. See {@code RepositoryRefResponse} for why a full
     * repository is not embedded.
     */
    public PullRequestResponse getPullRequest(User user, Integer connectionId, RepositoryRef ref,
                                              Integer pullRequestNumber) {
        ScmResourceContext context = accessService.requireUsableConnection(user, connectionId);
        int number = requireValidNumber(pullRequestNumber);

        ScmOperationResponse response = operationRunner.run(context,
                pullRequestRequest(ScmOperationCode.GET_PULL_REQUEST, ref, number),
                ScmErrorCode.SCM_PULL_REQUEST_NOT_FOUND);

        NormalizedPullRequest pullRequest = response.as(NormalizedPullRequest.class);
        if (pullRequest == null) {
            throw new ScmException(ScmErrorCode.SCM_RESPONSE_MAPPING_INVALID,
                    "operation=GET_PULL_REQUEST returned no pull request");
        }
        return PullRequestMapper.toResponse(pullRequest,
                RepositoryMapper.toRef(ref, context.provider()));
    }

    /**
     * The files a pull request changes.
     *
     * <p>Paged because both providers page it, and a pull request touching several hundred files is
     * ordinary in a repository with generated code or lockfiles.
     *
     * <p>No search predicate is passed even though the endpoint accepts {@code search}: a changed-files
     * list is a single pull request's worth of paths, so a client filtering them is filtering data it
     * already holds, and a scan here would spend provider calls to do what the client can do instantly.
     */
    public PageResponse<PullRequestFileResponse> listChangedFiles(User user, Integer connectionId,
                                                                  RepositoryRef ref,
                                                                  Integer pullRequestNumber,
                                                                  PageQuery query) {
        ScmResourceContext context = accessService.requireUsableConnection(user, connectionId);
        int number = requireValidNumber(pullRequestNumber);

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put(PARAM_OWNER, ref.owner());
        parameters.put(PARAM_REPO, ref.name());
        parameters.put(PARAM_PULL_REQUEST_NUMBER, number);

        return pageScanner.fetchPage(context,
                ScmOperationCode.GET_PULL_REQUEST_FILES,
                parameters,
                NormalizedPullRequestFile.class,
                query,
                null,
                PullRequestMapper::toFileResponse,
                ScmErrorCode.SCM_PULL_REQUEST_NOT_FOUND);
    }

    /**
     * A pull request's diff, parsed into files, hunks and lines.
     *
     * <p>This is the one operation whose provider response is <b>text rather than JSON</b>: its
     * configured response mapping is {@code TEXT}, so the payload arrives on
     * {@code ScmOperationResponse.getRawText()}. Reading {@code rawText} is correct here and is not the
     * leak of provider shape that reading {@code rawBody} would be - unified diff is a format both
     * providers emit identically, not a provider-specific document.
     *
     * <p>Not paged. A diff is one indivisible provider response, so there is no page to ask for; the
     * bound is a parse budget instead, and the response says when it was hit.
     */
    public PullRequestDiffResponse getDiff(User user, Integer connectionId, RepositoryRef ref,
                                           Integer pullRequestNumber) {
        ScmResourceContext context = accessService.requireUsableConnection(user, connectionId);
        int number = requireValidNumber(pullRequestNumber);

        ScmOperationResponse response = operationRunner.run(context,
                pullRequestRequest(ScmOperationCode.GET_PULL_REQUEST_DIFF, ref, number),
                ScmErrorCode.SCM_PULL_REQUEST_NOT_FOUND);

        return diffParser.parse(response.getRawText(), number);
    }

    private ScmOperationRequest pullRequestRequest(ScmOperationCode operation, RepositoryRef ref,
                                                   int pullRequestNumber) {
        return ScmOperationRequest.of(operation)
                .parameter(PARAM_OWNER, ref.owner())
                .parameter(PARAM_REPO, ref.name())
                .parameter(PARAM_PULL_REQUEST_NUMBER, pullRequestNumber);
    }

    /**
     * Rejects a pull-request number that cannot exist.
     *
     * <p>Checked here and not left to the provider because a zero or negative value would be
     * substituted into a URL and come back as an opaque provider error, which reads to a client like a
     * fault on our side rather than a bad request on theirs. Spring already rejects a non-numeric path
     * segment before this point.
     */
    private int requireValidNumber(Integer pullRequestNumber) {
        if (pullRequestNumber == null || pullRequestNumber < 1) {
            throw new ScmException(ScmErrorCode.SCM_REQUEST_INVALID,
                    "pullRequestNumber must be a positive integer");
        }
        return pullRequestNumber;
    }
}
