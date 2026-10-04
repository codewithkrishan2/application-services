package com.kksg.applicationServices.identity.service;

import com.kksg.applicationServices.identity.entity.User;
import com.kksg.applicationServices.identity.entity.UserLogin;
import com.kksg.applicationServices.identity.repository.UserLoginRepository;
import com.kksg.applicationServices.identity.security.JwtProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Issues, rotates and revokes the application's refresh tokens.
 *
 * <p><b>Only a digest is persisted.</b> The token used to be written to the database verbatim, which made
 * a database dump, a read replica or a logged query a supply of directly usable 30-day credentials. Now the
 * value exists in plaintext only in the response that delivers it; the stored form is a SHA-256 digest, and
 * a lookup digests what the caller presents before comparing.
 *
 * <p><b>Why SHA-256 and not bcrypt.</b> Slow password hashes exist to make guessing a low-entropy,
 * human-chosen secret expensive. This token is 256 bits from {@link SecureRandom}, so there is nothing to
 * guess and no dictionary to run - a plain digest is the right tool, and it keeps the lookup a single
 * indexed query instead of a scan-and-compare over every row.
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    private static final String DIGEST_ALGORITHM = "SHA-256";

    /** 256 bits, so the token is infeasible to guess and needs no stretching. */
    private static final int TOKEN_BYTES = 32;

    private final UserLoginRepository userLoginRepository;
    private final JwtProperties jwtProperties;
    private final SecureRandom secureRandom = new SecureRandom();

    public RefreshTokenService(UserLoginRepository userLoginRepository, JwtProperties jwtProperties) {
        this.userLoginRepository = userLoginRepository;
        this.jwtProperties = jwtProperties;
    }

    /**
     * Mints a new refresh token for this login, superseding any existing one.
     *
     * <p>The outgoing token's digest is moved to {@code appRefreshTokenPreviousHash} so that a later
     * attempt to use it is recognisable as a replay rather than merely unknown.
     *
     * @return the persisted login together with the plaintext token, which the caller must return to the
     *         client immediately and must not store anywhere.
     */
    @Transactional
    public IssuedRefreshToken issueRefreshToken(UserLogin userLogin) {
        String token = generateToken();

        userLogin.setAppRefreshTokenPreviousHash(userLogin.getAppRefreshTokenHash());
        userLogin.setAppRefreshTokenHash(digest(token));
        userLogin.setAppRefreshTokenExpiresAt(
                Instant.now().plusSeconds(jwtProperties.getRefreshTokenExpiration()));
        userLogin.setAppRefreshTokenRevoked(false);
        userLogin.setAppRefreshTokenRevokedAt(null);

        return new IssuedRefreshToken(userLoginRepository.save(userLogin), token);
    }

    /** Finds the login a presented token currently belongs to. */
    public Optional<UserLogin> findByPresentedToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return userLoginRepository.findByAppRefreshTokenHash(digest(token));
    }

    /**
     * Finds the login a presented token was rotated away from.
     *
     * <p>A hit here is a security event, not a lookup miss: see {@code AuthService.refreshToken}.
     */
    public Optional<UserLogin> findBySupersededToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return userLoginRepository.findByAppRefreshTokenPreviousHash(digest(token));
    }

    @Transactional
    public void revokeToken(UserLogin userLogin) {
        userLogin.setAppRefreshTokenRevoked(true);
        userLogin.setAppRefreshTokenRevokedAt(Instant.now());
        userLoginRepository.save(userLogin);
    }

    /**
     * Revokes every session for a user.
     *
     * <p>The breach response: when one token is known to be compromised, the holder may hold others, so
     * every provider login for that user is cut. This existed before but had no callers, which meant the
     * system could detect nothing and respond to nothing.
     */
    @Transactional
    public void revokeAllUserTokens(User user) {
        userLoginRepository.revokeAllRefreshTokensByUser(user);
        log.warn("REFRESH_TOKENS_REVOKED_ALL: user_id={}", user.getId());
    }

    private String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String digest(String token) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance(DIGEST_ALGORITHM);
            return HexFormat.of().formatHex(messageDigest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is mandated by the JDK; if it is missing the platform is broken, and silently
            // degrading to a weaker comparison would be worse than failing.
            throw new IllegalStateException("SHA-256 is not available in this JVM", ex);
        }
    }

    /**
     * A freshly issued refresh token.
     *
     * @param userLogin the persisted login carrying the new digest
     * @param token     the plaintext token; this is the only moment it exists outside the client
     */
    public record IssuedRefreshToken(UserLogin userLogin, String token) {
    }
}
