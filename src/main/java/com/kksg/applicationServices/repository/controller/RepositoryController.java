package com.kksg.applicationServices.repository.controller;

import com.kksg.applicationServices.common.response.ApiResponse;
import com.kksg.applicationServices.common.response.PageResponse;
import com.kksg.applicationServices.identity.entity.User;
import com.kksg.applicationServices.repository.dto.RepositoryResponse;
import com.kksg.applicationServices.repository.service.PageQuery;
import com.kksg.applicationServices.repository.service.RepositoryRef;
import com.kksg.applicationServices.repository.service.RepositoryService;
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
 * Repositories reachable through one of the authenticated user's SCM connections.
 *
 * <p>Nested under the connection on purpose. A repository has no identity in this product independent
 * of the authorization it is read through - the same name can exist on two providers, and whether it is
 * visible at all depends on whose credential is asking - so a top-level {@code /repositories} route
 * would have needed the connection as a query parameter anyway, while inviting the mistake of treating
 * a repository id as globally unique. The URL states the resolution order the backend actually uses.
 *
 * <p><b>A repository is addressed as {@code {owner}/{repo}}, two path segments.</b> Provider APIs
 * address repositories by owner-qualified name rather than by id, and neither provider's configuration
 * offers an id-keyed route, so the owner-qualified name <i>is</i> the repository's identifier for every
 * call this module makes. Expressing it as two segments rather than one encoded segment avoids relying
 * on servlet containers and proxies passing an encoded slash through intact, which they are not
 * consistent about. Clients get the exact value to use as {@code fullName} on every repository
 * response.
 *
 * <p>Thin, like the other controllers here: resolve the principal, validate the path, delegate.
 * Authorization lives in the service layer so it cannot be bypassed by a future caller that reaches the
 * service directly, and failures propagate to {@code GlobalExceptionHandler}, which maps every
 * {@code ScmErrorCode} to its status with the code under {@code errors.code}.
 */
@RestController
@RequestMapping("/api/v1/scm/connections/{connectionId}/repositories")
@Tag(name = "Repositories", description = "Repositories reachable through an SCM connection")
@SecurityRequirement(name = "bearerAuth")
public class RepositoryController {

    private final RepositoryService repositoryService;

    public RepositoryController(RepositoryService repositoryService) {
        this.repositoryService = repositoryService;
    }

    @GetMapping
    @Operation(summary = "List repositories",
            description = "Repositories the connection's credential can see, most recently updated "
                    + "first. `search` matches name, full name and description. Totals are absent "
                    + "unless the provider publishes one - see the pagination notes.")
    public ResponseEntity<ApiResponse<PageResponse<RepositoryResponse>>> listRepositories(
            @AuthenticationPrincipal User user,
            @PathVariable Integer connectionId,
            @Parameter(description = "Zero-based page index") @RequestParam(required = false) Integer page,
            @Parameter(description = "Items per page, 1-100") @RequestParam(required = false) Integer size,
            @Parameter(description = "Free-text filter") @RequestParam(required = false) String search) {

        PageQuery query = PageQuery.of(page, size, search);
        return ResponseEntity.ok(ApiResponse.success(
                repositoryService.listRepositories(user, connectionId, query)));
    }

    @GetMapping("/{owner}/{repo}")
    @Operation(summary = "Get repository",
            description = "One repository, addressed by owner-qualified name. Answers 404 "
                    + "`SCM_REPOSITORY_NOT_FOUND` when the connection's credential cannot see it - "
                    + "providers do not distinguish absent from invisible, and neither does this.")
    public ResponseEntity<ApiResponse<RepositoryResponse>> getRepository(
            @AuthenticationPrincipal User user,
            @PathVariable Integer connectionId,
            @PathVariable String owner,
            @PathVariable String repo) {

        RepositoryRef ref = new RepositoryRef(owner, repo);
        return ResponseEntity.ok(ApiResponse.success(
                repositoryService.getRepository(user, connectionId, ref)));
    }
}
