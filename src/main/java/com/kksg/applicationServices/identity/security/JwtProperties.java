package com.kksg.applicationServices.identity.security;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "security.jwt")
@Getter
@Setter
public class JwtProperties {

    private String secret;
    private long accessTokenExpiration = 900; // 15 minutes in seconds
    private long refreshTokenExpiration = 2592000; // 30 days in seconds

    /**
     * The signing secret with all whitespace removed.
     *
     * <p>Normalized here rather than at each use because three classes decode this value -
     * {@link JwtTokenProvider}, {@code OAuthLoginStateService} and {@code ScmOAuthStateService} - and a
     * rule applied in one of them is a rule the other two get wrong.
     *
     * <p>The whitespace matters: {@code openssl rand -base64 64}, the natural way to generate a key long
     * enough for HS512, wraps its output at 64 characters. That newline sits in the middle of the value,
     * so a plain trim leaves it there and Base64 decoding fails with an error that points at the format
     * rather than at the line break. Stripping makes a correctly generated key work as typed.
     */
    public String getSecret() {
        return secret == null ? null : secret.replaceAll("\\s", "");
    }
}
