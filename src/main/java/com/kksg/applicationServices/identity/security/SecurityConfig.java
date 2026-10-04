package com.kksg.applicationServices.identity.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    /**
     * Locks down what a response is allowed to pull in or be embedded by.
     *
     * <p>Every endpoint here returns JSON, so nothing legitimate needs a script, style, image or frame.
     * {@code frame-ancestors 'none'} is the modern counterpart to X-Frame-Options and blocks
     * clickjacking; {@code form-action 'none'} stops a reflected-HTML response (should one ever be
     * introduced) from being used to post credentials elsewhere.
     */
    private static final String API_CONTENT_SECURITY_POLICY =
            "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";

    /**
     * Relaxed only enough to let Swagger UI render its own assets from this origin.
     *
     * <p>Applied only when {@code app.security.expose-api-docs} is on, which should be development only.
     * The {@code 'unsafe-inline'} allowances are Swagger UI's requirement, not ours, and are the reason
     * this policy must not be the deployed one.
     */
    private static final String API_DOCS_CONTENT_SECURITY_POLICY =
            "default-src 'self'; img-src 'self' data:; script-src 'self' 'unsafe-inline'; "
                    + "style-src 'self' 'unsafe-inline'; connect-src 'self'; "
                    + "frame-ancestors 'none'; base-uri 'none'; form-action 'self'";

    private static final long HSTS_MAX_AGE_SECONDS = 31_536_000L; // one year

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;
    private final JwtAccessDeniedHandler jwtAccessDeniedHandler;
    private final SecurityPolicyProperties policy;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint,
                          JwtAccessDeniedHandler jwtAccessDeniedHandler,
                          SecurityPolicyProperties policy) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.jwtAuthenticationEntryPoint = jwtAuthenticationEntryPoint;
        this.jwtAccessDeniedHandler = jwtAccessDeniedHandler;
        this.policy = policy;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // Safe to disable only because authentication is bearer-only: this application never reads
                // credentials from a cookie, so a cross-site request cannot carry an identity. The frontend
                // does keep the token in an httpOnly cookie, but that cookie is sent to the frontend's own
                // origin and never to this service. If a cookie-authenticated endpoint is ever added here,
                // CSRF protection has to come back with it.
                .csrf(csrf -> csrf.disable())

                // No cross-origin access at all unless origins are explicitly configured. See
                // SecurityPolicyProperties: the frontend talks to this API server-to-server.
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))

                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .referrerPolicy(referrer ->
                                referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                policy.isExposeApiDocs()
                                        ? API_DOCS_CONTENT_SECURITY_POLICY
                                        : API_CONTENT_SECURITY_POLICY))
                        // Spring only emits HSTS on requests it considers secure, so this stays inert over
                        // plain HTTP in local development and takes effect once TLS terminates upstream.
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(HSTS_MAX_AGE_SECONDS))
                        .permissionsPolicy(permissions -> permissions.policy(
                                "accelerometer=(), camera=(), geolocation=(), gyroscope=(), "
                                        + "magnetometer=(), microphone=(), payment=(), usb=()"))
                )

                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(jwtAuthenticationEntryPoint)
                        // Without this, a 403 bypasses the ApiResponse envelope and returns an empty body
                        // or the container error page.
                        .accessDeniedHandler(jwtAccessDeniedHandler))

                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers(
                            "/api/v1/health",
                            "/api/v1/auth/**",
                            "/api/v1/oauth/**"
                    ).permitAll();

                    // Module 2 (SCM) endpoints that cannot carry a bearer token.
                    //
                    // Inbound provider webhooks: the caller is GitHub/Bitbucket, which has no
                    // application credential. Authenticity is established instead by HMAC
                    // signature verification in ScmWebhookService, which runs before the payload
                    // is parsed and before any database write.
                    //
                    // OAuth callback: a browser navigation initiated by the provider, so no
                    // Authorization header is present. The user's identity is carried by the
                    // signed `state` parameter, verified in ScmOAuthStateService.
                    //
                    // Both are narrow, explicit paths. Every other /api/v1/scm/** endpoint stays
                    // behind JWT via the anyRequest() rule below.
                    auth.requestMatchers(
                            "/api/v1/scm/webhooks/**",
                            "/api/v1/scm/connections/callback/**"
                    ).permitAll();

                    if (policy.isExposeApiDocs()) {
                        auth.requestMatchers(
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html"
                        ).permitAll();
                    }

                    auth.anyRequest().authenticated();
                })

                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Builds the cross-origin policy from the configured allow-list.
     *
     * <p>Returning {@code null} for every request when nothing is configured is not a no-op: it means
     * Spring performs no CORS handling, so a preflight is answered 401 and a cross-origin read is blocked
     * by the browser. That is the intended default for this deployment.
     *
     * <p>Credentials are never allowed. The API authenticates with an {@code Authorization} header that a
     * cross-site page cannot attach on the user's behalf, so there is nothing for credentialed CORS to
     * buy, and refusing it removes the reflected-origin-plus-cookies mistake from the table entirely.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        List<String> origins = policy.getAllowedOrigins();

        if (origins == null || origins.isEmpty()) {
            log.info("CORS_DISABLED: no app.security.allowed-origins configured; "
                    + "cross-origin browser requests will be refused");
            return request -> null;
        }

        origins.forEach(SecurityConfig::rejectWildcard);
        log.info("CORS_ENABLED: allowedOrigins={}", origins);

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(
                HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE, HttpHeaders.ACCEPT));
        configuration.setExposedHeaders(List.of());
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(1800L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    /**
     * A wildcard origin would let any site read authenticated responses using a token it has obtained by
     * other means, and it is the one CORS mistake that is hardest to notice once shipped. Refusing it at
     * startup is better than logging a warning nobody reads.
     */
    private static void rejectWildcard(String origin) {
        if (origin == null || origin.isBlank() || origin.contains("*")) {
            throw new IllegalStateException(
                    "app.security.allowed-origins must contain exact origins such as https://app.example.com; "
                            + "wildcards are not accepted (got: '" + origin + "')");
        }
    }
}
