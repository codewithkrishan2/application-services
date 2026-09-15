package com.kksg.applicationServices.scm.connection.service;

import com.kksg.applicationServices.identity.security.JwtProperties;
import com.kksg.applicationServices.scm.common.exception.ScmErrorCode;
import com.kksg.applicationServices.scm.common.exception.ScmException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;

/**
 * Issues and verifies the OAuth {@code state} parameter.
 *
 * <p><b>What state is for here.</b> The provider redirects the user's browser back to a callback that
 * cannot be authenticated with the application's bearer token - it is a plain navigation initiated by
 * the provider, carrying no {@code Authorization} header. The callback therefore has to learn two
 * things from the request itself: which user began the flow, and that the flow really was begun by
 * that user rather than forged by an attacker. A signed state carries the first and proves the second.
 *
 * <p>Without a signed state, an attacker could send a victim a crafted callback URL containing the
 * attacker's own authorization code and silently attach the attacker's provider account to the
 * victim's platform account.
 *
 * <p><b>Design choices.</b> A signed token rather than a database row keeps the flow stateless. The
 * 10-minute lifetime bounds the window in which a captured state is useful. A random nonce is included
 * so two flows started in the same second are distinguishable in logs.
 *
 * <p><b>Known limitation.</b> Being stateless, a state cannot be marked consumed, so it is replayable
 * until it expires. The consequence is bounded: a replay re-runs the same authorization for the same
 * user and provider, and since the provider's authorization code is itself single-use, the replay
 * fails at the token exchange. Enforcing true single-use would require a persisted state row, which is
 * noted as a follow-up rather than built now.
 *
 * <p>The signing key is the application's configured JWT secret. This is deliberate reuse of one
 * managed secret rather than introducing a second one to rotate; the tokens are not interchangeable
 * with access tokens because they carry a distinct issuer and are only ever parsed by this class.
 */
@Component
public class ScmOAuthStateService {

    private static final Logger log = LoggerFactory.getLogger(ScmOAuthStateService.class);

    private static final String ISSUER = "scm-oauth-state";
    private static final String CLAIM_PROVIDER_ID = "providerId";
    private static final String CLAIM_NONCE = "nonce";
    private static final long VALIDITY_SECONDS = 600;

    private final SecretKey signingKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public ScmOAuthStateService(JwtProperties jwtProperties) {
        this.signingKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtProperties.getSecret()));
    }

    /**
     * @return an opaque, signed state binding this authorization attempt to {@code userId} and
     *         {@code providerId}.
     */
    public String issue(Integer userId, Integer providerId) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + VALIDITY_SECONDS * 1000);

        byte[] nonceBytes = new byte[12];
        secureRandom.nextBytes(nonceBytes);

        return Jwts.builder()
                .issuer(ISSUER)
                .subject(String.valueOf(userId))
                .claim(CLAIM_PROVIDER_ID, providerId)
                .claim(CLAIM_NONCE, Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes))
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey)
                .compact();
    }

    /**
     * @return the user and provider the state was issued for.
     * @throws ScmException {@link ScmErrorCode#SCM_OAUTH_STATE_INVALID} when the state is missing,
     *         tampered with, expired, or not issued for this purpose.
     */
    public VerifiedState verify(String state) {
        if (state == null || state.isBlank()) {
            throw new ScmException(ScmErrorCode.SCM_OAUTH_STATE_INVALID, "state is missing");
        }
        try {
            Claims claims = Jwts.parser()
                    .requireIssuer(ISSUER)
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(state)
                    .getPayload();

            Integer userId = Integer.valueOf(claims.getSubject());
            Integer providerId = claims.get(CLAIM_PROVIDER_ID, Integer.class);
            if (providerId == null) {
                throw new ScmException(ScmErrorCode.SCM_OAUTH_STATE_INVALID, "state is incomplete");
            }
            return new VerifiedState(userId, providerId);

        } catch (JwtException | IllegalArgumentException ex) {
            // Never echo the state value back: it is a bearer-style artefact for the duration of the flow.
            log.warn("SCM_OAUTH_STATE_REJECTED: cause={}", ex.getClass().getSimpleName());
            throw new ScmException(ScmErrorCode.SCM_OAUTH_STATE_INVALID, "state could not be verified");
        }
    }

    /** The verified contents of an OAuth state parameter. */
    public record VerifiedState(Integer userId, Integer providerId) {
    }
}
