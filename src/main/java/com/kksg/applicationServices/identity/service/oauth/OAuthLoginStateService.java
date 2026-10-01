package com.kksg.applicationServices.identity.service.oauth;

import com.kksg.applicationServices.common.exception.ApiException;
import com.kksg.applicationServices.identity.entity.LoginProvider;
import com.kksg.applicationServices.identity.security.JwtProperties;
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
 * Issues and verifies the {@code state} parameter for the sign-in flow.
 *
 * <p><b>Why this is not just a random string.</b> The callback is a plain browser navigation initiated
 * by the provider: it carries no bearer token, so the only thing the application can trust about it is
 * what it can verify cryptographically. A random state that is generated and then never checked proves
 * nothing - it leaves the callback willing to process any authorization code anyone sends it, which is
 * the login-CSRF shape where an attacker makes a victim's browser complete a sign-in the victim never
 * started. Signing the state and requiring it back means a callback is only honoured if this application
 * actually began that flow.
 *
 * <p>The state is signed rather than stored so the flow stays stateless, mirroring
 * {@code ScmOAuthStateService}. It carries the provider it was issued for, which stops a state minted
 * for one provider's callback from being replayed against another's.
 *
 * <p><b>Known limitation.</b> A stateless state cannot be marked consumed, so it is replayable until it
 * expires ten minutes later. The exposure is bounded because the provider's authorization code is itself
 * single-use, so a replay fails at the token exchange. True single-use would need a persisted state row.
 *
 * <p>The signing key is the application's existing JWT secret: one managed secret to rotate rather than
 * two. These tokens are not interchangeable with access tokens because they carry a distinct issuer and
 * are only ever parsed here.
 */
@Component
public class OAuthLoginStateService {

    private static final Logger log = LoggerFactory.getLogger(OAuthLoginStateService.class);

    private static final String ISSUER = "identity-oauth-login-state";
    private static final String CLAIM_NONCE = "nonce";
    private static final long VALIDITY_SECONDS = 600;

    private final SecretKey signingKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public OAuthLoginStateService(JwtProperties jwtProperties) {
        this.signingKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtProperties.getSecret()));
    }

    /** @return an opaque, signed state binding this sign-in attempt to {@code provider}. */
    public String issue(LoginProvider provider) {
        Date now = new Date();
        byte[] nonce = new byte[12];
        secureRandom.nextBytes(nonce);

        return Jwts.builder()
                .issuer(ISSUER)
                .subject(provider.name())
                .claim(CLAIM_NONCE, Base64.getUrlEncoder().withoutPadding().encodeToString(nonce))
                .issuedAt(now)
                .expiration(new Date(now.getTime() + VALIDITY_SECONDS * 1000))
                .signWith(signingKey)
                .compact();
    }

    /**
     * @throws ApiException when the state is missing, tampered with, expired, or was issued for a
     *         different provider. The message is user-facing, so it names neither the state nor the cause.
     */
    public void verify(String state, LoginProvider expectedProvider) {
        if (!hasText(state)) {
            log.warn("OAUTH_LOGIN_STATE_REJECTED: provider={}, reason=missing", expectedProvider);
            throw new ApiException("This sign-in link is invalid. Please start again.");
        }

        try {
            Claims claims = Jwts.parser()
                    .requireIssuer(ISSUER)
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(state)
                    .getPayload();

            if (!expectedProvider.name().equals(claims.getSubject())) {
                log.warn("OAUTH_LOGIN_STATE_REJECTED: provider={}, reason=provider_mismatch", expectedProvider);
                throw new ApiException("This sign-in link is invalid. Please start again.");
            }
        } catch (JwtException | IllegalArgumentException ex) {
            // Never echo the state value: it is a bearer-style artefact for the duration of the flow.
            log.warn("OAUTH_LOGIN_STATE_REJECTED: provider={}, cause={}",
                    expectedProvider, ex.getClass().getSimpleName());
            throw new ApiException("This sign-in link has expired. Please start again.");
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
