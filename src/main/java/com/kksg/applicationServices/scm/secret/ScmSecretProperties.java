package com.kksg.applicationServices.scm.secret;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the local encrypted secret store.
 *
 * <p>Follows the same shape as {@code JwtProperties} so operators configure both the same way.
 *
 * <p>{@code encryptionKey} must be a Base64-encoded 256-bit key and must be supplied through the
 * environment in any deployed environment. It is the single value that protects every stored SCM
 * credential; if it leaks, every token must be treated as compromised and all connections revoked.
 */
@Component
@ConfigurationProperties(prefix = "scm.secrets")
@Getter
@Setter
public class ScmSecretProperties {

    /** Base64-encoded 256-bit AES key. */
    private String encryptionKey;

    /**
     * Identifies which key encrypted a row, so a rotation can decrypt old rows with the retired key
     * while encrypting new writes with the current one.
     */
    private int keyVersion = 1;
}
