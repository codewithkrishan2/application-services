package com.kksg.applicationServices.identity.security;

import com.kksg.applicationServices.identity.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.MacAlgorithm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.Optional;

/**
 * Issues and verifies this application's access tokens.
 *
 * <p>Three properties are pinned rather than inferred, because in each case the convenient default lets
 * the verifier accept more than the issuer produces:
 * <ul>
 *   <li><b>Algorithm.</b> {@code signWith(key)} alone picks the strongest MAC the key length happens to
 *       support, so the algorithm silently changes with the secret - and the parser would still accept a
 *       weaker one signed with the same key. Both sides are fixed to HS512.</li>
 *   <li><b>Key strength.</b> Enforced at startup, so a short secret fails loudly instead of quietly
 *       downgrading the signature.</li>
 *   <li><b>Issuer.</b> The secret is one symmetric key shared by everything that signs with it, including
 *       the OAuth state tokens. Requiring {@code iss} keeps those token types from being interchangeable:
 *       without it, a state token would parse as an access token.</li>
 * </ul>
 *
 * <p>Verification failures are collapsed into an empty {@link Optional} on purpose. Callers must not
 * branch on <em>why</em> a token was rejected, and nothing from the exception reaches the client.
 */
@Component
public class JwtTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenProvider.class);

    private static final MacAlgorithm SIGNATURE_ALGORITHM = Jwts.SIG.HS512;

    /** HS512 requires a key at least as long as its output. */
    private static final int MINIMUM_KEY_LENGTH_BYTES = 64;

    private static final String ISSUER = "coderev-identity";
    private static final String CLAIM_EMAIL = "email";

    private final JwtProperties jwtProperties;
    private final SecretKey signingKey;

    public JwtTokenProvider(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
        this.signingKey = buildSigningKey(jwtProperties.getSecret());
    }

    public String generateAccessToken(User user) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + jwtProperties.getAccessTokenExpiration() * 1000);

        return Jwts.builder()
                .issuer(ISSUER)
                .subject(String.valueOf(user.getId()))
                .claim(CLAIM_EMAIL, user.getEmail())
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(signingKey, SIGNATURE_ALGORITHM)
                .compact();
    }

    /**
     * Verifies a token and returns its subject.
     *
     * @return the token's claims, or empty if the token is absent, malformed, expired, signed with the
     *         wrong key or algorithm, issued for another purpose, or carries an unusable subject.
     */
    public Optional<AccessTokenClaims> verifyAccessToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }

        try {
            Jws<Claims> jws = Jwts.parser()
                    .requireIssuer(ISSUER)
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token);

            // verifyWith(SecretKey) already confines verification to MAC algorithms, so this is not
            // guarding against algorithm confusion - it pins the exact strength so a token signed HS256
            // with this key cannot be presented to an HS512 issuer.
            String algorithm = jws.getHeader().getAlgorithm();
            if (!SIGNATURE_ALGORITHM.getId().equals(algorithm)) {
                log.warn("JWT_REJECTED: reason=unexpected_algorithm, algorithm={}", algorithm);
                return Optional.empty();
            }

            Claims claims = jws.getPayload();
            Integer userId = parseSubject(claims.getSubject());
            if (userId == null) {
                return Optional.empty();
            }

            return Optional.of(new AccessTokenClaims(userId, claims.get(CLAIM_EMAIL, String.class)));

        } catch (JwtException | IllegalArgumentException ex) {
            // One line, one class name: the message can quote token content, and the distinction between
            // "expired" and "bad signature" is not something a log reader needs at WARN.
            log.warn("JWT_REJECTED: reason={}", ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    public long getAccessTokenExpirationSeconds() {
        return jwtProperties.getAccessTokenExpiration();
    }

    /**
     * The subject is written as a numeric user id. A token whose subject is not one is not ours, so it is
     * rejected rather than allowed to throw - this runs inside a servlet filter, where an exception escapes
     * past {@code GlobalExceptionHandler} and surfaces as a raw 500.
     */
    private Integer parseSubject(String subject) {
        try {
            return Integer.valueOf(subject);
        } catch (NumberFormatException ex) {
            log.warn("JWT_REJECTED: reason=non_numeric_subject");
            return null;
        }
    }

    /**
     * Fails startup rather than at first request: a missing or undersized signing key is a deployment
     * mistake, and the only safe time to find out is before the service accepts traffic.
     */
    private static SecretKey buildSigningKey(String configuredSecret) {
        if (configuredSecret == null || configuredSecret.isEmpty()) {
            throw new IllegalStateException(
                    "security.jwt.secret is not configured. Set JWT_SECRET to a Base64-encoded key of at "
                            + "least " + MINIMUM_KEY_LENGTH_BYTES + " bytes, for example with "
                            + "`openssl rand -base64 " + MINIMUM_KEY_LENGTH_BYTES + "`.");
        }

        // Already whitespace-normalized by JwtProperties.getSecret(), which is where that rule lives so
        // that every consumer of the secret shares it.
        byte[] keyBytes;
        try {
            keyBytes = Decoders.BASE64.decode(configuredSecret);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("security.jwt.secret must be valid Base64", ex);
        }

        if (keyBytes.length < MINIMUM_KEY_LENGTH_BYTES) {
            throw new IllegalStateException(
                    "security.jwt.secret must decode to at least %d bytes for %s, got %d"
                            .formatted(MINIMUM_KEY_LENGTH_BYTES, SIGNATURE_ALGORITHM.getId(), keyBytes.length));
        }

        return Keys.hmacShaKeyFor(keyBytes);
    }

    /** The verified contents of an access token. */
    public record AccessTokenClaims(Integer userId, String email) {
    }
}
