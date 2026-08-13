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
        UserLogin updated = refreshTokenService.createRefreshToken(userLogin);

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(updated.getAppRefreshToken())
                .tokenType("Bearer")
                .expiresIn(jwtTokenProvider.getAccessTokenExpirationSeconds())
                .build();
    }

    @Transactional
    public AuthResponse refreshToken(RefreshTokenRequest request) {
        UserLogin userLogin = refreshTokenService.findByRefreshToken(request.getRefreshToken())
                .orElseThrow(() -> new ApiException("Invalid refresh token"));

        if (userLogin.isAppRefreshTokenRevoked()) {
            log.warn("Attempt to use revoked refresh token for user_id={}", userLogin.getUser().getId());
            throw new ApiException("Refresh token has been revoked");
        }

        if (userLogin.isAppRefreshTokenExpired()) {
            log.warn("Attempt to use expired refresh token for user_id={}", userLogin.getUser().getId());
            throw new ApiException("Refresh token has expired");
        }

        User user = userLogin.getUser();
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new ApiException("User account is not active");
        }

        // Revoke old token (rotation)
        refreshTokenService.revokeToken(userLogin);

        // Generate new pair on the same UserLogin
        AuthResponse response = generateTokenPair(userLogin);
        log.info("REFRESH_TOKEN_ROTATED: user_id={}", user.getId());

        return response;
    }

    @Transactional
    public void logout(String refreshTokenValue) {
        refreshTokenService.findByRefreshToken(refreshTokenValue).ifPresent(userLogin -> {
            refreshTokenService.revokeToken(userLogin);
            log.info("LOGOUT_SUCCESS: user_id={}", userLogin.getUser().getId());
        });
        // Idempotent: no error if token doesn't exist
    }
}
