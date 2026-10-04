package com.kksg.applicationServices.repository.controller;

import com.kksg.applicationServices.common.response.ApiResponse;
import com.kksg.applicationServices.common.response.PageResponse;
import com.kksg.applicationServices.identity.entity.User;
import com.kksg.applicationServices.repository.dto.PullRequestDiffResponse;
import com.kksg.applicationServices.repository.dto.PullRequestFileResponse;
import com.kksg.applicationServices.repository.dto.PullRequestResponse;
import com.kksg.applicationServices.repository.dto.PullRequestStateFilter;
import com.kksg.applicationServices.repository.service.PageQuery;
import com.kksg.applicationServices.repository.service.PullRequestService;
import com.kksg.applicationServices.repository.service.RepositoryRef;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pull requests within a repository, read through an SCM connection.
 *
 * <p>The path spells out the containment the backend enforces -
 * {@code connection / repository / pull request} - and that is load-bearing rather than cosmetic. A
 * pull request is only ever resolved inside the repository named in the path, so a number belonging to
 * a different repository resolves to nothing instead of to someone else's pull request.
 *
 * <p><b>Keyed by {@code pullRequestNumber}, not by a pull-request id.</b> On at least one provider the
 * two are different integers and only the number is accepted in a pull-request URL; using the id
 * produces 404s. The number is also the value a user sees and quotes, so it is the right thing in the
 * URL regardless.
 */
@RestController
@RequestMapping("/api/v1/scm/connections/{connectionId}/repositories/{owner}/{repo}/pull-requests")
@Tag(name = "Pull requests", description = "Pull requests in a repository reachable through a connection")
@SecurityRequirement(name = "bearerAuth")
public class PullRequestController {

    private final PullRequestService pullRequestService;

    public PullRequestController(PullRequestService pullRequestService) {
        this.pullRequestService = pullRequestService;
    }

    @GetMapping
    @Operation(summary = "List pull requests",
            description = "Filtered by canonical state (OPEN, CLOSED, MERGED, ALL) at the provider, "
                    + "most recently updated first. Defaults to OPEN. `search` matches title, author, "
                    + "either branch name, or an exact pull-request number.")
    public ResponseEntity<ApiResponse<PageResponse<PullRequestResponse>>> listPullRequests(
            @AuthenticationPrincipal User user,
            @PathVariable Integer connectionId,
            @PathVariable String owner,
            @PathVariable String repo,
            @Parameter(description = "Zero-based page index") @RequestParam(required = false) Integer page,
            @Parameter(description = "Items per page, 1-100") @RequestParam(required = false) Integer size,
            @Parameter(description = "OPEN | CLOSED | MERGED | ALL") @RequestParam(required = false) String state,
            @Parameter(description = "Free-text filter") @RequestParam(required = false) String search) {

        RepositoryRef ref = new RepositoryRef(owner, repo);
        PageQuery query = PageQuery.of(page, size, search);
        PullRequestStateFilter stateFilter = PullRequestStateFilter.parse(state);

        return ResponseEntity.ok(ApiResponse.success(
                pullRequestService.listPullRequests(user, connectionId, ref, stateFilter, query)));
    }

    @GetMapping("/{pullRequestNumber}")
    @Operation(summary = "Get pull request",
            description = "One pull request, with the repository it belongs to attached. `state` is "
                    + "canonical: a merged pull request reports MERGED even on providers that report it "
                    + "as closed.")
    public ResponseEntity<ApiResponse<PullRequestResponse>> getPullRequest(
            @AuthenticationPrincipal User user,
            @PathVariable Integer connectionId,
            @PathVariable String owner,
            @PathVariable String repo,
            @PathVariable Integer pullRequestNumber) {

        RepositoryRef ref = new RepositoryRef(owner, repo);
        return ResponseEntity.ok(ApiResponse.success(
                pullRequestService.getPullRequest(user, connectionId, ref, pullRequestNumber)));
    }

    @GetMapping("/{pullRequestNumber}/files")
    @Operation(summary = "List changed files",
            description = "Paged, because providers page it. Per-file patches are not included even "
                    + "where a provider volunteers them - use the diff endpoint, which is the single "
                    + "source of patch content for every provider.")
    public ResponseEntity<ApiResponse<PageResponse<PullRequestFileResponse>>> listChangedFiles(
            @AuthenticationPrincipal User user,
            @PathVariable Integer connectionId,
            @PathVariable String owner,
            @PathVariable String repo,
            @PathVariable Integer pullRequestNumber,
            @Parameter(description = "Zero-based page index") @RequestParam(required = false) Integer page,
            @Parameter(description = "Items per page, 1-100") @RequestParam(required = false) Integer size) {

        RepositoryRef ref = new RepositoryRef(owner, repo);
        PageQuery query = PageQuery.of(page, size, null);

        return ResponseEntity.ok(ApiResponse.success(
                pullRequestService.listChangedFiles(user, connectionId, ref, pullRequestNumber, query)));
    }

    @GetMapping("/{pullRequestNumber}/diff")
    @Operation(summary = "Get pull request diff",
            description = "Unified diff parsed into files, hunks and lines with both sets of line "
                    + "numbers resolved. Not paged - a diff is one provider response - but bounded: "
                    + "`truncated` is true when the parse budget was reached.")
    public ResponseEntity<ApiResponse<PullRequestDiffResponse>> getDiff(
            @AuthenticationPrincipal User user,
            @PathVariable Integer connectionId,
            @PathVariable String owner,
            @PathVariable String repo,
            @PathVariable Integer pullRequestNumber) {

        RepositoryRef ref = new RepositoryRef(owner, repo);
        return ResponseEntity.ok(ApiResponse.success(
                pullRequestService.getDiff(user, connectionId, ref, pullRequestNumber)));
    }
}
