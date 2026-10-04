package com.kksg.applicationServices.identity.security;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Deployment-specific security policy: who may call this API from a browser, and whether the API
 * description is published.
 *
 * <p>Both default to the closed position. That matters more than it looks: the previous behaviour was
 * closed only by omission - no CORS configuration existed at all - which is secure but invisible, and the
 * usual way that gets "fixed" under time pressure is a wildcard. Making the policy explicit means
 * widening it is a deliberate, reviewable config change.
 */
@Component
@ConfigurationProperties(prefix = "app.security")
@Getter
@Setter
public class SecurityPolicyProperties {

    /**
     * Exact origins (scheme, host and port) permitted to make cross-origin browser requests.
     *
     * <p>Empty means no cross-origin access, which is correct for this deployment: the Next.js frontend
     * calls this API from its server, never from page scripts, so no browser ever performs a cross-origin
     * request. Populate it only if that changes.
     *
     * <p>Entries must be exact origins. Wildcards and patterns are rejected at startup.
     */
    private List<String> allowedOrigins = List.of();

    /**
     * Whether {@code /v3/api-docs/**} and the Swagger UI are readable without authentication.
     *
     * <p>Off by default. The schema enumerates every route, parameter and error shape, which is a map of
     * the attack surface; it is useful in development and has no place being public in a deployment.
     */
    private boolean exposeApiDocs = false;
}
