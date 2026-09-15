package com.kksg.applicationServices.scm.provider.service;

import com.kksg.applicationServices.scm.capability.entity.ScmProviderCapability;
import com.kksg.applicationServices.scm.capability.service.ScmProviderCapabilityService;
import com.kksg.applicationServices.scm.common.exception.ScmErrorCode;
import com.kksg.applicationServices.scm.common.exception.ScmException;
import com.kksg.applicationServices.scm.common.model.ScmCapabilityCode;
import com.kksg.applicationServices.scm.common.model.ScmOperationCode;
import com.kksg.applicationServices.scm.operation.service.ScmProviderOperationService;
import com.kksg.applicationServices.scm.provider.config.ProviderConfiguration;
import com.kksg.applicationServices.scm.provider.config.ProviderConfigurationFactory;
import com.kksg.applicationServices.scm.provider.dto.ScmProviderDetailResponse;
import com.kksg.applicationServices.scm.provider.dto.ScmProviderResponse;
import com.kksg.applicationServices.scm.provider.entity.ScmProvider;
import com.kksg.applicationServices.scm.provider.mapper.ScmProviderMapper;
import com.kksg.applicationServices.scm.provider.repository.ScmProviderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Provider lookup, activation rules, and configuration persistence.
 *
 * <p>Responsibility boundary: this service owns the {@code scm_providers} row and nothing else. It
 * does not build requests, exchange tokens or verify webhooks. Where a detail view needs capability
 * and operation data it delegates to their own services rather than querying their tables, so each
 * table keeps a single owner.
 */
@Service
public class ScmProviderService {

    private static final Logger log = LoggerFactory.getLogger(ScmProviderService.class);

    private final ScmProviderRepository providerRepository;
    private final ProviderConfigurationFactory configurationFactory;
    private final ScmProviderCapabilityService capabilityService;
    private final ScmProviderOperationService operationService;

    public ScmProviderService(ScmProviderRepository providerRepository,
                              ProviderConfigurationFactory configurationFactory,
                              ScmProviderCapabilityService capabilityService,
                              ScmProviderOperationService operationService) {
        this.providerRepository = providerRepository;
        this.configurationFactory = configurationFactory;
        this.capabilityService = capabilityService;
        this.operationService = operationService;
    }

    @Transactional(readOnly = true)
    public List<ScmProviderResponse> listActiveProviders() {
        return providerRepository.findByActiveTrueOrderByDisplayOrderAscProviderNameAsc().stream()
                .map(ScmProviderMapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ScmProviderDetailResponse getProviderDetail(Integer providerId) {
        ScmProvider provider = requireById(providerId);
        ProviderConfiguration configuration = configurationFactory.get(provider);

        List<ScmProviderCapability> capabilities = capabilityService.findAll(provider);
        List<String> supported = capabilities.stream()
                .filter(ScmProviderCapability::isSupported)
                .map(capability -> capability.getCapabilityCode().name())
                .sorted()
                .toList();
        List<String> unsupported = capabilities.stream()
                .filter(capability -> !capability.isSupported())
                .map(capability -> capability.getCapabilityCode().name())
                .sorted()
                .toList();
        List<String> operations = operationService.findConfiguredCodes(provider).stream()
                .map(ScmOperationCode::name)
                .toList();

        return ScmProviderDetailResponse.builder()
                .id(provider.getId())
                .providerCode(provider.getProviderCode())
                .providerName(provider.getProviderName())
                .providerType(provider.getProviderType() != null ? provider.getProviderType().name() : null)
                .active(provider.isActive())
                .displayOrder(provider.getDisplayOrder())
                .apiBaseUrl(configuration.apiOrEmpty().baseUrl())
                .oauthScopes(configuration.oauthOrEmpty().scopesOrEmpty())
                .supportedCapabilities(supported)
                .unsupportedCapabilities(unsupported)
                .configuredOperations(operations)
                .build();
    }

    @Transactional(readOnly = true)
    public ScmProvider requireById(Integer providerId) {
        if (providerId == null) {
            throw new ScmException(ScmErrorCode.SCM_PROVIDER_NOT_FOUND);
        }
        return providerRepository.findById(providerId)
                .orElseThrow(() -> new ScmException(ScmErrorCode.SCM_PROVIDER_NOT_FOUND,
                        "id=%d".formatted(providerId)));
    }

    @Transactional(readOnly = true)
    public ScmProvider requireByCode(String providerCode) {
        if (providerCode == null || providerCode.isBlank()) {
            throw new ScmException(ScmErrorCode.SCM_PROVIDER_NOT_FOUND);
        }
        return providerRepository.findByProviderCodeIgnoreCase(providerCode.trim())
                .orElseThrow(() -> new ScmException(ScmErrorCode.SCM_PROVIDER_NOT_FOUND,
                        "providerCode=%s".formatted(providerCode)));
    }

    /**
     * Looks up a provider and asserts it may be used.
     *
     * <p>Used by the connect flow and the operation engine. Deactivating a provider must stop new
     * work immediately, which is why this check happens on every use rather than only at connect
     * time.
     *
     * @throws ScmException {@link ScmErrorCode#SCM_PROVIDER_INACTIVE}
     */
    @Transactional(readOnly = true)
    public ScmProvider requireActiveByCode(String providerCode) {
        ScmProvider provider = requireByCode(providerCode);
        requireActive(provider);
        return provider;
    }

    public void requireActive(ScmProvider provider) {
        if (!provider.isActive()) {
            log.warn("SCM_PROVIDER_INACTIVE: providerCode={}", provider.getProviderCode());
            throw new ScmException(ScmErrorCode.SCM_PROVIDER_INACTIVE,
                    "providerCode=%s".formatted(provider.getProviderCode()));
        }
    }

    /**
     * Creates or replaces a provider's configuration document.
     *
     * <p>Validates before writing so an invalid document can never reach the database; a stored
     * invalid configuration would break every request for that provider, including the ones needed
     * to diagnose it.
     *
     * <p>No HTTP endpoint is wired to this method. Module 1 issues JWTs without authorities, so an
     * administrative mutation endpoint would currently be reachable by any authenticated user.
     * Provider mutation is therefore available to the seeder and to tests only, until a role model
     * exists. See "Known limitations" in {@code docs/scm-provider-integration.md}.
     */
    @Transactional
    public ScmProvider saveConfiguration(String providerCode,
                                         String providerName,
                                         com.kksg.applicationServices.scm.common.model.ScmProviderType providerType,
                                         Map<String, Object> configuration,
                                         boolean active,
                                         int displayOrder) {
        configurationFactory.validate(providerCode, configuration);

        ScmProvider provider = providerRepository.findByProviderCodeIgnoreCase(providerCode)
                .orElseGet(ScmProvider::new);
        provider.setProviderCode(providerCode);
        provider.setProviderName(providerName);
        provider.setProviderType(providerType);
        provider.setConfiguration(configuration);
        provider.setActive(active);
        provider.setDisplayOrder(displayOrder);

        ScmProvider saved = providerRepository.save(provider);
        configurationFactory.evict(saved.getId());
        log.info("SCM_PROVIDER_SAVED: providerCode={}, active={}", saved.getProviderCode(), saved.isActive());
        return saved;
    }

    /** Typed configuration for a provider; the engine's entry point into the JSONB document. */
    @Transactional(readOnly = true)
    public ProviderConfiguration getConfiguration(ScmProvider provider) {
        return configurationFactory.get(provider);
    }

    @Transactional(readOnly = true)
    public boolean supports(ScmProvider provider, ScmCapabilityCode capabilityCode) {
        return capabilityService.isSupported(provider, capabilityCode);
    }
}
