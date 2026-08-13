package com.kksg.applicationServices.identity.controller;

import com.kksg.applicationServices.common.exception.ApiException;
import com.kksg.applicationServices.common.response.ApiResponse;
import com.kksg.applicationServices.identity.dto.request.LogoutRequest;
import com.kksg.applicationServices.identity.dto.request.RefreshTokenRequest;
import com.kksg.applicationServices.identity.dto.response.AuthResponse;
import com.kksg.applicationServices.identity.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Authentication endpoints")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/refresh")
    @Operation(summary = "Refresh access token", description = "Exchange a valid refresh token for a new token pair")
    public ResponseEntity<ApiResponse<AuthResponse>> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        try {
            AuthResponse response = authService.refreshToken(request);
            return ResponseEntity.ok(ApiResponse.success("Token refreshed successfully", response));
        } catch (ApiException ex) {
            log.warn("Token refresh failed: {}", ex.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(ex.getMessage()));
        } catch (Exception ex) {
            log.error("Unexpected error during token refresh", ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Token refresh failed due to an unexpected error"));
        }
    }

    @PostMapping("/logout")
    @Operation(summary = "Logout", description = "Revoke the refresh token")
    public ResponseEntity<ApiResponse<Void>> logout(@Valid @RequestBody LogoutRequest request) {
        try {
            authService.logout(request.getRefreshToken());
            return ResponseEntity.ok(ApiResponse.success("Logged out successfully", null));
        } catch (Exception ex) {
            log.error("Unexpected error during logout", ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Logout failed due to an unexpected error"));
        }
    }
}
