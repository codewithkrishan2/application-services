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
 * Bitbucket Cloud sign-in endpoints.
 *
 * <p>Both routes are browser navigations rather than API calls, so they answer with a 302 in every case -
 * including failure, which lands on the frontend's error page. {@link OAuthLoginService} is total and
 * never throws, which is why there is no error handling here to duplicate across providers.
 *
 * <p>The path shape {@code /api/v1/oauth/{provider}/auth} and {@code .../callback} matches the existing
 * GitHub routes, so these endpoints are already public via the {@code /api/v1/oauth/**} rule in
 * {@code SecurityConfig} and need no security change. The callback path must also be registered as the
 * consumer's callback URL in Bitbucket.
 */
@RestController
@RequestMapping("/api/v1/oauth/bitbucket")
@Tag(name = "OAuth - Bitbucket", description = "Bitbucket Cloud sign-in")
public class BitbucketOAuthController {

    private final OAuthLoginService oAuthLoginService;

    public BitbucketOAuthController(OAuthLoginService oAuthLoginService) {
        this.oAuthLoginService = oAuthLoginService;
    }

    @GetMapping("/auth")
    @Operation(summary = "Start Bitbucket sign-in",
            description = "Redirects the browser to Bitbucket for authorization.")
    public void authorize(HttpServletResponse response) throws IOException {
        response.sendRedirect(oAuthLoginService.startAuthorization(LoginProvider.BITBUCKET));
    }

    @GetMapping("/callback")
    @Operation(summary = "Bitbucket sign-in callback",
            description = "Verifies the state, exchanges the code for tokens, provisions the user, "
                    + "then redirects to the frontend with the application's token pair.")
    public void callback(@RequestParam(value = "code", required = false) String code,
                         @RequestParam(value = "state", required = false) String state,
                         @RequestParam(value = "error", required = false) String error,
                         HttpServletResponse response) throws IOException {
        response.sendRedirect(
                oAuthLoginService.completeAuthorization(LoginProvider.BITBUCKET, code, state, error));
    }
}
