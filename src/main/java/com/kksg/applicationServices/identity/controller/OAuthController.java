package com.kksg.applicationServices.identity.controller;

import com.kksg.applicationServices.identity.service.GitHubOAuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

@RestController
@RequestMapping("/api/v1/oauth")
@Tag(name = "OAuth", description = "OAuth authentication endpoints")
public class OAuthController {

    private static final Logger log = LoggerFactory.getLogger(OAuthController.class);

    @Value("${app.frontend-url}")
    private String frontendUrl;

    private final GitHubOAuthService gitHubOAuthService;

    public OAuthController(GitHubOAuthService gitHubOAuthService) {
        this.gitHubOAuthService = gitHubOAuthService;
    }

    @GetMapping("/github/auth")
    @Operation(summary = "Initiate GitHub OAuth", description = "Redirects the user to GitHub for authorization")
    public void githubAuth(HttpServletResponse response) throws IOException {
        try {
            String authorizationUrl = gitHubOAuthService.buildAuthorizationUrl();
            response.sendRedirect(authorizationUrl);
        } catch (Exception ex) {
            log.error("Failed to initiate GitHub OAuth", ex);
            String errorUrl = frontendUrl + "/oauth-error?message=" + encodeParam("Failed to initiate GitHub authentication");
            response.sendRedirect(errorUrl);
        }
    }

    @GetMapping("/github/callback")
    @Operation(summary = "GitHub OAuth callback", description = "Handles the GitHub OAuth callback, exchanges code for tokens, and redirects to frontend")
    public void githubCallback(
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "error", required = false) String error,
            HttpServletResponse response) throws IOException {
        try {
            String redirectUrl = gitHubOAuthService.handleCallback(code, error);
            response.sendRedirect(redirectUrl);
        } catch (Exception ex) {
            log.error("Unexpected error during GitHub OAuth callback", ex);
            String errorUrl = frontendUrl + "/oauth-error?message=" + encodeParam("Authentication failed due to an unexpected error");
            response.sendRedirect(errorUrl);
        }
    }

    private String encodeParam(String value) {
        try {
            return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "Authentication+failed";
        }
    }
}
