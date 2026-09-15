package com.kksg.applicationServices.scm.provider.config;

import com.kksg.applicationServices.scm.common.exception.ScmErrorCode;
import com.kksg.applicationServices.scm.common.exception.ScmException;
import com.kksg.applicationServices.scm.provider.entity.ScmProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Resolves the secret values that provider configuration only <i>names</i>.
 *
 * <p>{@code scm_providers.configuration} stores {@code "clientSecretProperty":
 * "scm.providers.github.client-secret"} - a pointer, not a value. This component dereferences such
 * pointers against the Spring {@link Environment}, which means the secrets themselves live in
 * environment variables or an external config source and never in the database.
 *
 * <p>Two consequences worth stating, because they are the reason for the indirection:
 * <ul>
 *   <li>A database backup, a replica, or an admin API that returns provider configuration cannot
 *       expose a client secret - there is nothing to expose.</li>
 *   <li>Each environment (local, staging, production) points at its own OAuth application without
 *       any per-environment database difference.</li>
 * </ul>
 *
 * <p>Every method in this class returns credential material. None of them logs, and error messages
 * name only the <i>property</i> that is missing, never any value.
 */
@Component
public class ProviderCredentialResolver {

    private static final Logger log = LoggerFactory.getLogger(ProviderCredentialResolver.class);

    private final Environment environment;
    private final ProviderConfigurationFactory configurationFactory;

    public ProviderCredentialResolver(Environment environment, ProviderConfigurationFactory configurationFactory) {
        this.environment = environment;
        this.configurationFactory = configurationFactory;
    }

    public String resolveClientId(ScmProvider provider) {
        ProviderConfiguration.OAuth oauth = requireOAuth(provider);
        return requireProperty(provider, oauth.clientIdProperty(), "OAuth client id");
    }

    public String resolveClientSecret(ScmProvider provider) {
        ProviderConfiguration.OAuth oauth = requireOAuth(provider);
        return requireProperty(provider, oauth.clientSecretProperty(), "OAuth client secret");
    }

    public String resolveRedirectUri(ScmProvider provider) {
        ProviderConfiguration.OAuth oauth = requireOAuth(provider);
        return requireProperty(provider, oauth.redirectUriProperty(), "OAuth redirect URI");
    }

    /**
     * @return the shared webhook secret, or empty when the provider is configured for unsigned
     *         webhooks. Empty is a legitimate configuration, so this returns {@link Optional}
     *         instead of throwing; the signature verifier decides whether absence is acceptable.
     */
    public Optional<String> resolveWebhookSecret(ScmProvider provider) {
        ProviderConfiguration.Webhook webhook = configurationFactory.get(provider).webhookOrEmpty();
        if (webhook.secretProperty() == null || webhook.secretProperty().isBlank()) {
            return Optional.empty();
        }
        String value = environment.getProperty(webhook.secretProperty());
        if (value == null || value.isBlank()) {
            log.warn("SCM_WEBHOOK_SECRET_MISSING: providerCode={}, property={}",
                    provider.getProviderCode(), webhook.secretProperty());
            return Optional.empty();
        }
        return Optional.of(value);
    }

    private ProviderConfiguration.OAuth requireOAuth(ScmProvider provider) {
        ProviderConfiguration.OAuth oauth = configurationFactory.get(provider).oauth();
        if (oauth == null) {
            throw new ScmException(ScmErrorCode.SCM_PROVIDER_CONFIGURATION_INVALID,
                    "providerCode=%s has no oauth configuration".formatted(provider.getProviderCode()));
        }
        return oauth;
    }

    private String requireProperty(ScmProvider provider, String propertyName, String description) {
        String value = propertyName == null ? null : environment.getProperty(propertyName);
        if (value == null || value.isBlank()) {
            // Names the property so an operator can fix it; never echoes a value.
            throw new ScmException(ScmErrorCode.SCM_PROVIDER_CONFIGURATION_INVALID,
                    "providerCode=%s is missing %s (expected configuration property '%s')"
                            .formatted(provider.getProviderCode(), description, propertyName));
        }
        return value;
    }
}
