package com.kksg.applicationServices.scm.connection.controller;

import com.kksg.applicationServices.common.response.ApiResponse;
import com.kksg.applicationServices.identity.entity.User;
import com.kksg.applicationServices.scm.connection.dto.CreateScmConnectionRequest;
import com.kksg.applicationServices.scm.connection.dto.ScmAuthorizationUrlResponse;
import com.kksg.applicationServices.scm.connection.dto.ScmConnectionResponse;
import com.kksg.applicationServices.scm.connection.entity.ScmConnection;
import com.kksg.applicationServices.scm.connection.mapper.ScmConnectionMapper;
import com.kksg.applicationServices.scm.connection.service.ScmConnectionAuthorizationService;
import com.kksg.applicationServices.scm.connection.service.ScmConnectionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Manages the authenticated user's SCM connections.
 *
 * <p>Thin: every method resolves the principal, delegates, and wraps the result. Ownership enforcement
 * lives in {@code ScmConnectionService} rather than here, so it cannot be bypassed by a future caller
 * that reaches the service directly; failures propagate to {@code GlobalExceptionHandler}.
 *
 * <p>The browser-redirect half of the OAuth flow lives in {@code ScmOAuthCallbackController} because it
 * must be reachable without a bearer token and returns a redirect rather than JSON - mixing those
 * response semantics into this controller would obscure that one of its endpoints is unauthenticated.
 */
@RestController
@RequestMapping("/api/v1/scm/connections")
@Tag(name = "SCM Connections", description = "User authorizations to source control providers")
@SecurityRequirement(name = "bearerAuth")
public class ScmConnectionController {

    private final ScmConnectionService connectionService;
    private final ScmConnectionAuthorizationService authorizationService;

    public ScmConnectionController(ScmConnectionService connectionService,
                                   ScmConnectionAuthorizationService authorizationService) {
        this.connectionService = connectionService;
        this.authorizationService = authorizationService;
    }

    @GetMapping
    @Operation(summary = "List connections",
            description = "Returns the current user's SCM connections, most recently connected first")
    public ResponseEntity<ApiResponse<List<ScmConnectionResponse>>> listConnections(
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.success(connectionService.listConnections(user)));
    }

    @GetMapping("/authorize")
    @Operation(summary = "Start a connection",
            description = "Returns the provider authorization URL the client should navigate to")
    public ResponseEntity<ApiResponse<ScmAuthorizationUrlResponse>> authorize(
            @AuthenticationPrincipal User user,
            @RequestParam String providerCode) {

        ScmConnectionAuthorizationService.AuthorizationRedirect redirect =
                authorizationService.buildAuthorizationUrl(user, providerCode);

        return ResponseEntity.ok(ApiResponse.success(ScmAuthorizationUrlResponse.builder()
                .authorizationUrl(redirect.authorizationUrl())
                .providerCode(redirect.providerCode())
                .state(redirect.state())
                .build()));
    }

    @PostMapping
    @Operation(summary = "Complete a connection",
            description = "Exchanges a provider authorization code for stored credentials and creates or "
                    + "updates the connection")
    public ResponseEntity<ApiResponse<ScmConnectionResponse>> createConnection(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody CreateScmConnectionRequest request) {

        ScmConnection connection = authorizationService.completeForUser(
                user, request.getProviderCode(), request.getCode());

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("SCM connection established",
                        ScmConnectionMapper.toResponse(connection)));
    }

    @GetMapping("/{connectionId}")
    @Operation(summary = "Get connection", description = "Returns one of the current user's connections")
    public ResponseEntity<ApiResponse<ScmConnectionResponse>> getConnection(
            @AuthenticationPrincipal User user,
            @PathVariable Integer connectionId) {
        return ResponseEntity.ok(ApiResponse.success(connectionService.getConnection(user, connectionId)));
    }

    @DeleteMapping("/{connectionId}")
    @Operation(summary = "Disconnect",
            description = "Destroys stored credentials and marks the connection disconnected. Idempotent.")
    public ResponseEntity<ApiResponse<Void>> disconnect(
            @AuthenticationPrincipal User user,
            @PathVariable Integer connectionId) {
        connectionService.disconnect(user, connectionId);
        return ResponseEntity.ok(ApiResponse.success("SCM connection disconnected", null));
    }
}
