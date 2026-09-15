package com.kksg.applicationServices.scm.operation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kksg.applicationServices.scm.common.exception.ScmErrorCode;
import com.kksg.applicationServices.scm.common.exception.ScmException;
import com.kksg.applicationServices.scm.common.model.ScmOperationCode;
import com.kksg.applicationServices.scm.operation.engine.RequestConfiguration;
import com.kksg.applicationServices.scm.operation.engine.ResponseMapping;
import com.kksg.applicationServices.scm.operation.entity.ScmProviderOperation;
import com.kksg.applicationServices.scm.operation.repository.ScmProviderOperationRepository;
import com.kksg.applicationServices.scm.provider.entity.ScmProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads operation definitions and parses their declarative JSONB documents.
 *
 * <p>Sits between the repository and the engine so the engine deals only in
 * {@link ResolvedOperation} values and never in raw maps. Parsed results are cached per operation
 * row and invalidated by {@code updated_at}, matching {@code ProviderConfigurationFactory}, so
 * changing an endpoint in the database takes effect without a redeploy.
 */
@Service
public class ScmProviderOperationService {

    private static final Logger log = LoggerFactory.getLogger(ScmProviderOperationService.class);

    private final ScmProviderOperationRepository operationRepository;
    private final ObjectMapper objectMapper;
    private final Map<Integer, CachedOperation> cache = new ConcurrentHashMap<>();

    public ScmProviderOperationService(ScmProviderOperationRepository operationRepository,
                                       ObjectMapper objectMapper) {
        this.operationRepository = operationRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * @throws ScmException {@link ScmErrorCode#SCM_OPERATION_NOT_CONFIGURED} when no row exists or
     *         the row is deactivated. Distinct from {@code SCM_OPERATION_NOT_SUPPORTED}, which means
     *         the provider itself cannot do it - the two demand different fixes (add configuration
     *         vs. accept the limitation), so they are not collapsed.
     */
    @Transactional(readOnly = true)
    public ResolvedOperation require(ScmProvider provider, ScmOperationCode operationCode) {
        if (provider == null || provider.getId() == null) {
            throw new ScmException(ScmErrorCode.SCM_PROVIDER_NOT_FOUND);
        }
        ScmProviderOperation operation = operationRepository
                .findByProviderIdAndOperationCode(provider.getId(), operationCode)
                .orElseThrow(() -> {
                    log.warn("SCM_OPERATION_NOT_CONFIGURED: providerCode={}, operationCode={}",
                            provider.getProviderCode(), operationCode);
                    return new ScmException(ScmErrorCode.SCM_OPERATION_NOT_CONFIGURED,
                            "providerCode=%s operationCode=%s".formatted(provider.getProviderCode(), operationCode));
                });

        if (!operation.isActive()) {
            log.warn("SCM_OPERATION_INACTIVE: providerCode={}, operationCode={}",
                    provider.getProviderCode(), operationCode);
            throw new ScmException(ScmErrorCode.SCM_OPERATION_NOT_CONFIGURED,
                    "providerCode=%s operationCode=%s is inactive"
                            .formatted(provider.getProviderCode(), operationCode));
        }
        return resolve(operation);
    }

    @Transactional(readOnly = true)
    public List<ScmProviderOperation> findAll(ScmProvider provider) {
        if (provider == null || provider.getId() == null) {
            return List.of();
        }
        return operationRepository.findByProviderId(provider.getId());
    }

    @Transactional(readOnly = true)
    public List<ScmOperationCode> findConfiguredCodes(ScmProvider provider) {
        if (provider == null || provider.getId() == null) {
            return List.of();
        }
        return operationRepository.findByProviderIdAndActiveTrue(provider.getId()).stream()
                .map(ScmProviderOperation::getOperationCode)
                .sorted()
                .toList();
    }

    /** Parses the operation's JSONB documents, reusing a cached result while the row is unchanged. */
    public ResolvedOperation resolve(ScmProviderOperation operation) {
        Integer operationId = operation.getId();
        Instant version = operation.getUpdatedAt() != null ? operation.getUpdatedAt() : Instant.EPOCH;

        if (operationId != null) {
            CachedOperation cached = cache.get(operationId);
            if (cached != null && cached.version().equals(version)) {
                return cached.resolved();
            }
        }

        ResolvedOperation resolved = new ResolvedOperation(
                operation,
                parseRequestConfiguration(operation),
                parseResponseMapping(operation));

        if (operationId != null) {
            cache.put(operationId, new CachedOperation(version, resolved));
        }
        return resolved;
    }

    public void evictAll() {
        cache.clear();
    }

    private RequestConfiguration parseRequestConfiguration(ScmProviderOperation operation) {
        Map<String, Object> raw = operation.getRequestConfiguration();
        if (raw == null || raw.isEmpty()) {
            return RequestConfiguration.empty();
        }
        try {
            return objectMapper.convertValue(raw, RequestConfiguration.class);
        } catch (IllegalArgumentException ex) {
            throw new ScmException(ScmErrorCode.SCM_PROVIDER_CONFIGURATION_INVALID,
                    "operationCode=%s has an invalid request_configuration".formatted(operation.getOperationCode()),
                    ex);
        }
    }

    private ResponseMapping parseResponseMapping(ScmProviderOperation operation) {
        Map<String, Object> raw = operation.getResponseMapping();
        if (raw == null || raw.isEmpty()) {
            return ResponseMapping.raw();
        }
        try {
            return objectMapper.convertValue(raw, ResponseMapping.class);
        } catch (IllegalArgumentException ex) {
            throw new ScmException(ScmErrorCode.SCM_PROVIDER_CONFIGURATION_INVALID,
                    "operationCode=%s has an invalid response_mapping".formatted(operation.getOperationCode()),
                    ex);
        }
    }

    private record CachedOperation(Instant version, ResolvedOperation resolved) {
    }
}
