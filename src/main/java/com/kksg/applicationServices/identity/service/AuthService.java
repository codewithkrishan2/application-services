package com.kksg.applicationServices.identity.service;

import com.kksg.applicationServices.common.exception.ApiException;
import com.kksg.applicationServices.identity.dto.request.RefreshTokenRequest;
import com.kksg.applicationServices.identity.dto.response.AuthResponse;
import com.kksg.applicationServices.identity.entity.User;
import com.kksg.applicationServices.identity.entity.UserLogin;
import com.kksg.applicationServices.identity.entity.UserStatus;
import com.kksg.applicationServices.identity.security.JwtTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenService refreshTokenService;

    public AuthService(JwtTokenProvider jwtTokenProvider, RefreshTokenService refreshTokenService) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.refreshTokenService = refreshTokenService;
    }

    @Transactional
    public AuthResponse generateTokenPair(UserLogin userLogin) {
        User user = userLogin.getUser();
        String accessToken = jwtTokenProvider.generateAccessToken(user);
        RefreshTokenService.IssuedRefreshToken issued = refreshTokenService.issueRefreshToken(userLogin);

        return AuthResponse.builder()
                .accessToken(accessToken)
                // The plaintext token, straight from the issuer. Only its digest was persisted.
                .refreshToken(issued.token())
                .tokenType("Bearer")
                .expiresIn(jwtTokenProvider.getAccessTokenExpirationSeconds())
                .build();
    }

    /**
     * Exchanges a refresh token for a new pair, rotating the refresh token in the process.
     *
     * <p>Rotation alone does not make a stolen token safe - it only shortens the window. What closes it is
     * noticing the theft: a token this service has already rotated away from should never come back, so if
     * one does, two parties hold the same credential and the legitimate client is no longer the only
     * holder. That case cannot be distinguished from an unknown token unless the superseded digest is
     * remembered, which is why it is, and the response is to cut every session for the user rather than
     * just refuse this request.
     */
    @Transactional
    public AuthResponse refreshToken(RefreshTokenRequest request) {
        String presented = request.getRefreshToken();

        Optional<UserLogin> active = refreshTokenService.findByPresentedToken(presented);
        if (active.isEmpty()) {
            handlePossibleReuse(presented);
            throw new ApiException("Invalid refresh token");
        }

        UserLogin userLogin = active.get();
        User user = userLogin.getUser();

        if (userLogin.isAppRefreshTokenRevoked()) {
            // Someone is using a credential that was explicitly retired, most likely by a logout they did
            // not perform. Treated as compromise, not as a stale client.
            log.warn("REFRESH_TOKEN_REVOKED_REUSE: user_id={}", user.getId());
            refreshTokenService.revokeAllUserTokens(user);
            throw new ApiException("Refresh token has been revoked");
        }

        if (userLogin.isAppRefreshTokenExpired()) {
            log.warn("REFRESH_TOKEN_EXPIRED: user_id={}", user.getId());
            throw new ApiException("Refresh token has expired");
        }

        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new ApiException("User account is not active");
        }

        AuthResponse response = generateTokenPair(userLogin);
        log.info("REFRESH_TOKEN_ROTATED: user_id={}", user.getId());

        return response;
    }

    @Transactional
    public void logout(String refreshTokenValue) {
        refreshTokenService.findByPresentedToken(refreshTokenValue).ifPresent(userLogin -> {
            refreshTokenService.revokeToken(userLogin);
            log.info("LOGOUT_SUCCESS: user_id={}", userLogin.getUser().getId());
        });
        // Idempotent: an unknown token still reports success, so this is not an existence oracle.
    }

    /**
     * Checks whether an unrecognised token is one that was already rotated away, and if so treats it as a
     * compromise of the whole session chain.
     */
    private void handlePossibleReuse(String presented) {
        refreshTokenService.findBySupersededToken(presented).ifPresent(compromised -> {
            User user = compromised.getUser();
            log.warn("REFRESH_TOKEN_REUSE_DETECTED: user_id={}, revoking all sessions", user.getId());
            refreshTokenService.revokeAllUserTokens(user);
        });
    }
}
