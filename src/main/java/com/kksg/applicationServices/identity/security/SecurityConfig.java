package com.kksg.applicationServices.identity.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.jwtAuthenticationEntryPoint = jwtAuthenticationEntryPoint;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(jwtAuthenticationEntryPoint))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/v1/health",
                                "/api/v1/auth/**",
                                "/api/v1/oauth/**",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html"
                        ).permitAll()

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
                        .requestMatchers(
                                "/api/v1/scm/webhooks/**",
                                "/api/v1/scm/connections/callback/**"
                        ).permitAll()

                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
