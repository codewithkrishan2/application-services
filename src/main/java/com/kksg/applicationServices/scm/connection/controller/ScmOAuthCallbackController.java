package com.kksg.applicationServices.scm.connection.controller;

import com.kksg.applicationServices.scm.common.exception.ScmException;
import com.kksg.applicationServices.scm.connection.entity.ScmConnection;
import com.kksg.applicationServices.scm.connection.service.ScmConnectionAuthorizationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;

/**
 * Receives the provider's browser redirect that completes a connection.
 *
 * <p><b>Unauthenticated by necessity.</b> This is a navigation the provider triggers in the user's
 * browser; it carries no {@code Authorization} header. The user's identity comes from the signed
 * {@code state} parameter, which {@code ScmOAuthStateService} verifies - that signature is the only
 * thing standing between this endpoint and an attacker attaching their own provider account to someone
 * else's platform account.
 *
 * <p>Kept separate from {@code ScmConnectionController} so that the one endpoint in this module which is
 * deliberately outside JWT protection is obvious to anyone reading the code or reviewing
 * {@code SecurityConfig}, rather than hidden among authenticated endpoints.
 *
 * <p><b>Why it redirects instead of returning JSON.</b> The caller is a browser mid-navigation, so it
 * must be sent somewhere. Only a coarse status is placed in the redirect - never a token, a code, or an
 * internal error message - because everything in a URL lands in browser history, referrer headers and
 * proxy logs.
 */
@RestController
@RequestMapping("/api/v1/scm/connections/callback")
@Tag(name = "SCM Connections", description = "User authorizations to source control providers")
public class ScmOAuthCallbackController {

    private static final Logger log = LoggerFactory.getLogger(ScmOAuthCallbackController.class);

    private static final String RESULT_PATH = "/scm/connection-result";

    private final ScmConnectionAuthorizationService authorizationService;
    private final String frontendUrl;

    public ScmOAuthCallbackController(ScmConnectionAuthorizationService authorizationService,
                                      @Value("${app.frontend-url}") String frontendUrl) {
        this.authorizationService = authorizationService;
        this.frontendUrl = frontendUrl;
    }

    @GetMapping("/{providerCode}")
    @Operation(summary = "Provider OAuth callback",
            description = "Completes a connection from the provider redirect and forwards the browser to "
                    + "the frontend. Authenticated by the signed state parameter, not by a bearer token.")
    public void handleCallback(@PathVariable String providerCode,
                              @RequestParam(required = false) String code,
                              @RequestParam(required = false) String state,
                              @RequestParam(required = false) String error,
                              HttpServletResponse response) throws IOException {

        // The user declining consent is an expected outcome, not a failure to investigate.
        if (error != null && !error.isBlank()) {
            log.info("SCM_CONNECT_DENIED: providerCode={}", providerCode);
            response.sendRedirect(buildResultUrl(providerCode, "denied"));
            return;
        }

        try {
            ScmConnection connection = authorizationService.completeFromCallback(code, state);
            log.info("SCM_CONNECT_CALLBACK_COMPLETED: providerCode={}, connectionId={}",
                    providerCode, connection.getId());
            response.sendRedirect(buildResultUrl(providerCode, "success"));

        } catch (ScmException ex) {
            // The error code is a safe, enumerated value; the message may carry diagnostic detail and
            // stays in the log rather than travelling to the browser.
            log.warn("SCM_CONNECT_CALLBACK_FAILED: providerCode={}, errorCode={}",
                    providerCode, ex.getErrorCode());
            response.sendRedirect(buildResultUrl(providerCode, "failed"));

        } catch (Exception ex) {
            log.error("SCM_CONNECT_CALLBACK_ERROR: providerCode={}", providerCode, ex);
            response.sendRedirect(buildResultUrl(providerCode, "failed"));
        }
    }

    private String buildResultUrl(String providerCode, String status) {
        return UriComponentsBuilder.fromHttpUrl(frontendUrl)
                .path(RESULT_PATH)
                .queryParam("provider", providerCode)
                .queryParam("status", status)
                .encode()
                .build()
                .toUriString();
    }
}
