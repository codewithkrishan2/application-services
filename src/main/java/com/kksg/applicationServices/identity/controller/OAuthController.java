package com.kksg.applicationServices.identity.controller;

import com.kksg.applicationServices.identity.entity.LoginProvider;
import com.kksg.applicationServices.identity.service.oauth.OAuthLoginService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * GitHub sign-in endpoints.
 *
 * <p>The paths are unchanged; the flow now runs through the shared {@link OAuthLoginService}, which also
 * serves Bitbucket. Because that service converts every failure into a redirect to the frontend error
 * page, the controller is left with nothing but the redirect itself.
 */
@RestController
@RequestMapping("/api/v1/oauth")
@Tag(name = "OAuth - GitHub", description = "GitHub sign-in")
public class OAuthController {

    private final OAuthLoginService oAuthLoginService;

    public OAuthController(OAuthLoginService oAuthLoginService) {
        this.oAuthLoginService = oAuthLoginService;
    }

    @GetMapping("/github/auth")
    @Operation(summary = "Start GitHub sign-in",
            description = "Redirects the browser to GitHub for authorization.")
    public void githubAuth(HttpServletResponse response) throws IOException {
        response.sendRedirect(oAuthLoginService.startAuthorization(LoginProvider.GITHUB));
    }

    @GetMapping("/github/callback")
    @Operation(summary = "GitHub sign-in callback",
            description = "Verifies the state, exchanges the code for tokens, provisions the user, "
                    + "then redirects to the frontend with the application's token pair.")
    public void githubCallback(@RequestParam(value = "code", required = false) String code,
                               @RequestParam(value = "state", required = false) String state,
                               @RequestParam(value = "error", required = false) String error,
                               HttpServletResponse response) throws IOException {
        response.sendRedirect(
                oAuthLoginService.completeAuthorization(LoginProvider.GITHUB, code, state, error));
    }
}
