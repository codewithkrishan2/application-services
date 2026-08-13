package com.kksg.applicationServices.identity.service;

import com.kksg.applicationServices.identity.entity.User;
import com.kksg.applicationServices.identity.entity.UserLogin;
import com.kksg.applicationServices.identity.repository.UserLoginRepository;
import com.kksg.applicationServices.identity.security.JwtProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    private final UserLoginRepository userLoginRepository;
    private final JwtProperties jwtProperties;

    public RefreshTokenService(UserLoginRepository userLoginRepository, JwtProperties jwtProperties) {
        this.userLoginRepository = userLoginRepository;
        this.jwtProperties = jwtProperties;
    }

    @Transactional
    public UserLogin createRefreshToken(UserLogin userLogin) {
        userLogin.setAppRefreshToken(UUID.randomUUID().toString());
        userLogin.setAppRefreshTokenExpiresAt(Instant.now().plusSeconds(jwtProperties.getRefreshTokenExpiration()));
        userLogin.setAppRefreshTokenRevoked(false);
        userLogin.setAppRefreshTokenRevokedAt(null);

        return userLoginRepository.save(userLogin);
    }

    public Optional<UserLogin> findByRefreshToken(String token) {
        return userLoginRepository.findByAppRefreshToken(token);
    }

    @Transactional
    public void revokeToken(UserLogin userLogin) {
        userLogin.setAppRefreshTokenRevoked(true);
        userLogin.setAppRefreshTokenRevokedAt(Instant.now());
        userLoginRepository.save(userLogin);
    }

    @Transactional
    public void revokeAllUserTokens(User user) {
        userLoginRepository.revokeAllRefreshTokensByUser(user);
        log.info("LOGOUT_SUCCESS: All tokens revoked for user_id={}", user.getId());
    }
}
