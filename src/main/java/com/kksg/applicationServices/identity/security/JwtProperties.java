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
}
