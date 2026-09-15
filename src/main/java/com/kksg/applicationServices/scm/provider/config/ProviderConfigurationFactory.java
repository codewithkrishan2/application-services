package com.kksg.applicationServices.scm.provider.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kksg.applicationServices.scm.common.exception.ScmErrorCode;
import com.kksg.applicationServices.scm.common.exception.ScmException;
import com.kksg.applicationServices.scm.provider.entity.ScmProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Parses, validates and caches {@code scm_providers.configuration} into
 * {@link ProviderConfiguration}.
 *
 * <p>Every provider-driven code path needs the typed configuration, and parsing a JSONB document on
 * each API call would be wasteful, so results are cached per provider and invalidated by the row's
 * {@code updated_at}. That specific invalidation key matters: an operator can change provider
 * configuration in the database and the next request picks it up without a restart, because
 * Hibernate's {@code @UpdateTimestamp} moves whenever the row is written.
 *
 * <p>Validation runs at parse time and fails with
 * {@link ScmErrorCode#SCM_PROVIDER_CONFIGURATION_INVALID}. Validating here rather than at each use
 * site means a broken document produces one clear error naming the offending field, instead of a
 * confusing {@code NullPointerException} deep inside request building.
 */
@Component
public class ProviderConfigurationFactory {

    private static final Logger log = LoggerFactory.getLogger(ProviderConfigurationFactory.class);

    private final ObjectMapper objectMapper;
    private final Map<Integer, CachedConfiguration> cache = new ConcurrentHashMap<>();

    public ProviderConfigurationFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * @return the validated configuration for {@code provider}, parsed on first use and reused until
     *         the provider row changes.
     * @throws ScmException with {@link ScmErrorCode#SCM_PROVIDER_CONFIGURATION_INVALID}
     */
    public ProviderConfiguration get(ScmProvider provider) {
        if (provider == null) {
            throw new ScmException(ScmErrorCode.SCM_PROVIDER_NOT_FOUND);
        }
        Integer providerId = provider.getId();
        Instant version = provider.getUpdatedAt() != null ? provider.getUpdatedAt() : Instant.EPOCH;

        if (providerId == null) {
            return parseAndValidate(provider);
        }

        CachedConfiguration cached = cache.get(providerId);
        if (cached != null && cached.version().equals(version)) {
            return cached.configuration();
        }

        ProviderConfiguration parsed = parseAndValidate(provider);
        cache.put(providerId, new CachedConfiguration(version, parsed));
        return parsed;
    }

    /**
     * Validates a candidate document without persisting or caching it. Used by the seeder and by
     * administrative updates so an invalid document is rejected before it reaches the database.
     */
    public ProviderConfiguration validate(String providerCode, Map<String, Object> rawConfiguration) {
        return parse(providerCode, rawConfiguration);
    }

    /** Drops cached configuration; called by the seeder after it rewrites provider rows. */
    public void evict(Integer providerId) {
        if (providerId != null) {
            cache.remove(providerId);
        }
    }

    public void evictAll() {
        cache.clear();
    }

    private ProviderConfiguration parseAndValidate(ScmProvider provider) {
        ProviderConfiguration configuration = parse(provider.getProviderCode(), provider.getConfiguration());
        log.debug("SCM_PROVIDER_CONFIG_PARSED: providerCode={}, baseUrl={}",
                provider.getProviderCode(), configuration.apiOrEmpty().baseUrl());
        return configuration;
    }

    private ProviderConfiguration parse(String providerCode, Map<String, Object> rawConfiguration) {
        if (rawConfiguration == null || rawConfiguration.isEmpty()) {
            throw invalid(providerCode, "configuration document is empty");
        }

        ProviderConfiguration configuration;
        try {
            configuration = objectMapper.convertValue(rawConfiguration, ProviderConfiguration.class);
        } catch (IllegalArgumentException ex) {
            // The message can name an unparseable enum/field, which is safe; it never contains a
            // credential because credentials are referenced by property name, not embedded.
            throw invalid(providerCode, "malformed configuration document: " + rootCauseMessage(ex));
        }

        validateApi(providerCode, configuration);
        validateOAuth(providerCode, configuration);
        validateWebhook(providerCode, configuration);
        validatePagination(providerCode, configuration);
        return configuration;
    }

    private void validateApi(String providerCode, ProviderConfiguration configuration) {
        ProviderConfiguration.Api api = configuration.api();
        if (api == null || api.baseUrl() == null || api.baseUrl().isBlank()) {
            throw invalid(providerCode, "api.baseUrl is required");
        }
        String baseUrl = api.baseUrl().trim();
        if (!baseUrl.startsWith("https://") && !baseUrl.startsWith("http://")) {
            throw invalid(providerCode, "api.baseUrl must be an absolute http(s) URL");
        }
        if (api.authentication() != null
                && api.authentication().schemeOrDefault() == ProviderConfiguration.AuthScheme.HEADER
                && (api.authentication().header() == null || api.authentication().header().isBlank())) {
            throw invalid(providerCode, "api.authentication.header is required when scheme is HEADER");
        }
    }

    private void validateOAuth(String providerCode, ProviderConfiguration configuration) {
        ProviderConfiguration.OAuth oauth = configuration.oauth();
        if (oauth == null) {
            // A provider could conceivably be configured for personal-access-token use only.
            return;
        }
        if (isBlank(oauth.authorizationUrl())) {
            throw invalid(providerCode, "oauth.authorizationUrl is required");
        }
        if (isBlank(oauth.tokenUrl())) {
            throw invalid(providerCode, "oauth.tokenUrl is required");
        }
        if (isBlank(oauth.clientIdProperty())) {
            throw invalid(providerCode, "oauth.clientIdProperty is required");
        }
        if (isBlank(oauth.clientSecretProperty())) {
            throw invalid(providerCode, "oauth.clientSecretProperty is required");
        }
        if (isBlank(oauth.redirectUriProperty())) {
            throw invalid(providerCode, "oauth.redirectUriProperty is required");
        }
    }

    private void validateWebhook(String providerCode, ProviderConfiguration configuration) {
        ProviderConfiguration.Webhook webhook = configuration.webhook();
        if (webhook == null) {
            return;
        }
        if (isBlank(webhook.deliveryIdHeader())) {
            throw invalid(providerCode, "webhook.deliveryIdHeader is required for idempotent delivery handling");
        }
        if (isBlank(webhook.eventHeader())) {
            throw invalid(providerCode, "webhook.eventHeader is required to identify the event");
        }
        if (webhook.signatureAlgorithmOrDefault() != ProviderConfiguration.SignatureAlgorithm.NONE) {
            if (isBlank(webhook.signatureHeader())) {
                throw invalid(providerCode, "webhook.signatureHeader is required when a signature algorithm is set");
            }
            if (isBlank(webhook.secretProperty())) {
                throw invalid(providerCode, "webhook.secretProperty is required when a signature algorithm is set");
            }
        }
    }

    private void validatePagination(String providerCode, ProviderConfiguration configuration) {
        ProviderConfiguration.Pagination pagination = configuration.pagination();
        if (pagination == null) {
            return;
        }
        switch (pagination.typeOrDefault()) {
            case PAGE -> {
                if (isBlank(pagination.pageParameter())) {
                    throw invalid(providerCode, "pagination.pageParameter is required for PAGE pagination");
                }
            }
            case CURSOR -> {
                if (isBlank(pagination.nextPath())) {
                    throw invalid(providerCode, "pagination.nextPath is required for CURSOR pagination");
                }
            }
            case NONE -> {
                // Nothing to validate.
            }
        }
    }

    private ScmException invalid(String providerCode, String detail) {
        return new ScmException(ScmErrorCode.SCM_PROVIDER_CONFIGURATION_INVALID,
                "providerCode=%s %s".formatted(providerCode, detail));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String rootCauseMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage();
    }

    private record CachedConfiguration(Instant version, ProviderConfiguration configuration) {
    }
}
