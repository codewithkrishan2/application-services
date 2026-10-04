package com.kksg.applicationServices.identity.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kksg.applicationServices.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Answers unauthenticated requests to protected endpoints.
 *
 * <p>The reply is deliberately uniform: a missing token, a malformed token, a token with a bad signature,
 * an expired token and a token for a deleted account all produce exactly this response. Distinguishing
 * them would tell an attacker which of those they achieved, and the client cannot act differently on any
 * of them anyway - the answer is always "obtain a new token".
 *
 * <p>{@code authException} is intentionally not surfaced; the server-side log is the place for detail.
 */
@Component
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    /** Uses the Spring-configured mapper so this response serializes like every other one. */
    public JwtAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        // RFC 6750 requires the challenge on a 401 from a bearer-protected resource.
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");

        ApiResponse<Void> apiResponse = ApiResponse.error("Unauthorized. Please provide a valid access token.");
        objectMapper.writeValue(response.getOutputStream(), apiResponse);
    }
}
