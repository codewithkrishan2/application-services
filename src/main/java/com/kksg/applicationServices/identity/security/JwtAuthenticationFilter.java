package com.kksg.applicationServices.identity.security;

import com.kksg.applicationServices.identity.entity.User;
import com.kksg.applicationServices.identity.entity.UserStatus;
import com.kksg.applicationServices.identity.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Locale;
import java.util.Optional;

/**
 * Establishes the authenticated principal from a bearer token.
 *
 * <p>The filter never rejects a request itself: it either populates the security context or leaves the
 * request anonymous, and the authorization rules in {@link SecurityConfig} decide what that means. Keeping
 * the decision in one place is what makes a bad token and no token indistinguishable to the caller.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    /** RFC 6750 defines the scheme as case-insensitive, so the comparison must be too. */
    private static final String BEARER_PREFIX = "bearer ";

    private final JwtTokenProvider jwtTokenProvider;
    private final UserRepository userRepository;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider, UserRepository userRepository) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        try {
            authenticate(request);
        } catch (RuntimeException ex) {
            // An exception thrown here would escape to the container as a raw 500, bypassing
            // GlobalExceptionHandler (which only sees exceptions raised inside MVC). A failure to
            // authenticate must leave the request anonymous, never abort it with a stack trace.
            SecurityContextHolder.clearContext();
            log.error("AUTHENTICATION_FILTER_ERROR: path={}, cause={}",
                    request.getRequestURI(), ex.getClass().getSimpleName(), ex);
        }

        filterChain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request) {
        String token = extractToken(request);
        if (token == null) {
            return;
        }

        Optional<JwtTokenProvider.AccessTokenClaims> claims = jwtTokenProvider.verifyAccessToken(token);
        if (claims.isEmpty()) {
            return;
        }

        Integer userId = claims.get().userId();
        Optional<User> found = userRepository.findById(userId);
        if (found.isEmpty()) {
            // A validly signed token for a row that no longer exists. Worth a line: it means either a
            // deleted account or a token minted against a different database.
            log.warn("AUTHENTICATION_REJECTED: reason=unknown_user, user_id={}", userId);
            return;
        }

        User user = found.get();

        // Previously missing, and the gap had teeth: status was checked when refreshing a token but never
        // when using one, so deactivating an account left it with full API access. Enforcing it here makes
        // deactivation effective on the next request rather than whenever the access token expires.
        if (user.getStatus() != UserStatus.ACTIVE) {
            log.warn("AUTHENTICATION_REJECTED: reason=inactive_account, user_id={}, status={}",
                    userId, user.getStatus());
            return;
        }

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(user, null, Collections.emptyList());
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || header.length() <= BEARER_PREFIX.length()) {
            return null;
        }
        if (!header.toLowerCase(Locale.ROOT).startsWith(BEARER_PREFIX)) {
            return null;
        }

        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
