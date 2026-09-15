package com.kksg.applicationServices.scm.capability.service;

import com.kksg.applicationServices.scm.capability.entity.ScmProviderCapability;
import com.kksg.applicationServices.scm.capability.repository.ScmProviderCapabilityRepository;
import com.kksg.applicationServices.scm.common.exception.ScmErrorCode;
import com.kksg.applicationServices.scm.common.exception.ScmException;
import com.kksg.applicationServices.scm.common.model.ScmCapabilityCode;
import com.kksg.applicationServices.scm.provider.entity.ScmProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Read model for provider capabilities.
 *
 * <p>Scoped narrowly on purpose: it answers support questions and nothing else. The operation
 * engine consults {@link #requireSupported} before building a request, so an unsupported operation
 * fails with a precise {@code SCM_OPERATION_NOT_SUPPORTED} instead of an opaque provider 404.
 */
@Service
public class ScmProviderCapabilityService {

    private static final Logger log = LoggerFactory.getLogger(ScmProviderCapabilityService.class);

    private final ScmProviderCapabilityRepository capabilityRepository;

    public ScmProviderCapabilityService(ScmProviderCapabilityRepository capabilityRepository) {
        this.capabilityRepository = capabilityRepository;
    }

    /**
     * @return true only when an explicit row says the capability is supported. Absence of a row is
     *         treated as unsupported: advertising a capability the platform has never configured an
     *         operation for would fail at call time anyway, and failing closed keeps that failure
     *         cheap and early.
     */
    @Transactional(readOnly = true)
    public boolean isSupported(ScmProvider provider, ScmCapabilityCode capabilityCode) {
        if (provider == null || provider.getId() == null || capabilityCode == null) {
            return false;
        }
        return capabilityRepository.findByProviderIdAndCapabilityCode(provider.getId(), capabilityCode)
                .map(ScmProviderCapability::isSupported)
                .orElse(false);
    }

    /**
     * Guard used by the engine before dispatching an operation.
     *
     * @throws ScmException with {@link ScmErrorCode#SCM_OPERATION_NOT_SUPPORTED}
     */
    @Transactional(readOnly = true)
    public void requireSupported(ScmProvider provider, ScmCapabilityCode capabilityCode) {
        if (!isSupported(provider, capabilityCode)) {
            log.warn("SCM_CAPABILITY_UNSUPPORTED: providerCode={}, capabilityCode={}",
                    provider != null ? provider.getProviderCode() : null, capabilityCode);
            throw new ScmException(ScmErrorCode.SCM_OPERATION_NOT_SUPPORTED,
                    "providerCode=%s capabilityCode=%s"
                            .formatted(provider != null ? provider.getProviderCode() : null, capabilityCode));
        }
    }

    @Transactional(readOnly = true)
    public List<ScmProviderCapability> findAll(ScmProvider provider) {
        if (provider == null || provider.getId() == null) {
            return List.of();
        }
        return capabilityRepository.findByProviderId(provider.getId());
    }

    @Transactional(readOnly = true)
    public List<ScmCapabilityCode> findSupportedCodes(ScmProvider provider) {
        if (provider == null || provider.getId() == null) {
            return List.of();
        }
        return capabilityRepository.findByProviderIdAndSupportedTrue(provider.getId()).stream()
                .map(ScmProviderCapability::getCapabilityCode)
                .sorted()
                .toList();
    }

    /**
     * Capability-scoped limits (max comment length, max files, ...) for callers that must respect
     * them before issuing a request.
     */
    @Transactional(readOnly = true)
    public Optional<ScmProviderCapability> find(ScmProvider provider, ScmCapabilityCode capabilityCode) {
        if (provider == null || provider.getId() == null || capabilityCode == null) {
            return Optional.empty();
        }
        return capabilityRepository.findByProviderIdAndCapabilityCode(provider.getId(), capabilityCode);
    }
}
