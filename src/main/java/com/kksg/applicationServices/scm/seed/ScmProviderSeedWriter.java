package com.kksg.applicationServices.scm.seed;

import com.kksg.applicationServices.scm.capability.entity.ScmProviderCapability;
import com.kksg.applicationServices.scm.capability.repository.ScmProviderCapabilityRepository;
import com.kksg.applicationServices.scm.common.exception.ScmErrorCode;
import com.kksg.applicationServices.scm.common.exception.ScmException;
import com.kksg.applicationServices.scm.common.model.NormalizedEventType;
import com.kksg.applicationServices.scm.common.model.ScmCapabilityCode;
import com.kksg.applicationServices.scm.common.model.ScmOperationCode;
import com.kksg.applicationServices.scm.common.model.ScmProviderType;
import com.kksg.applicationServices.scm.event.entity.ScmProviderEvent;
import com.kksg.applicationServices.scm.event.repository.ScmProviderEventRepository;
import com.kksg.applicationServices.scm.operation.entity.ScmProviderOperation;
import com.kksg.applicationServices.scm.operation.repository.ScmProviderOperationRepository;
import com.kksg.applicationServices.scm.operation.service.ScmProviderOperationService;
import com.kksg.applicationServices.scm.provider.config.ProviderConfigurationFactory;
import com.kksg.applicationServices.scm.provider.entity.ScmProvider;
import com.kksg.applicationServices.scm.provider.repository.ScmProviderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Writes one seed document into the database, reconciling it to the declared state.
 *
 * <p><b>Desired-state reconciliation, not insert-if-absent.</b> The seed file is authoritative: rows it
 * declares are created or updated, and rows it no longer declares are removed. That is what makes
 * configuration fixes shippable - correcting an endpoint template in the JSON and redeploying applies
 * it - whereas insert-if-absent would silently keep serving the old, broken configuration forever.
 *
 * <p>Idempotent by construction: reconciling twice produces the same result, so running on every startup
 * is safe.
 *
 * <p><b>The {@code seed_managed} escape hatch.</b> A provider row whose {@code seed_managed} is
 * {@code false} is skipped entirely, along with its children. An operator who has hand-tuned a provider
 * in the database - to work around a provider incident, say - would otherwise have that work silently
 * reverted by the next deployment.
 *
 * <p>Separate bean from {@link ScmProviderSeeder} so {@link Transactional} applies: the seeder loops over
 * files and calls this through the Spring proxy, giving each provider its own transaction. One malformed
 * file therefore cannot leave another provider half-written.
 */
@Component
public class ScmProviderSeedWriter {

    private static final Logger log = LoggerFactory.getLogger(ScmProviderSeedWriter.class);

    private final ScmProviderRepository providerRepository;
    private final ScmProviderCapabilityRepository capabilityRepository;
    private final ScmProviderOperationRepository operationRepository;
    private final ScmProviderEventRepository eventRepository;
    private final ProviderConfigurationFactory configurationFactory;
    private final ScmProviderOperationService operationService;

    public ScmProviderSeedWriter(ScmProviderRepository providerRepository,
                                 ScmProviderCapabilityRepository capabilityRepository,
                                 ScmProviderOperationRepository operationRepository,
                                 ScmProviderEventRepository eventRepository,
                                 ProviderConfigurationFactory configurationFactory,
                                 ScmProviderOperationService operationService) {
        this.providerRepository = providerRepository;
        this.capabilityRepository = capabilityRepository;
        this.operationRepository = operationRepository;
        this.eventRepository = eventRepository;
        this.configurationFactory = configurationFactory;
        this.operationService = operationService;
    }

    /**
     * @return an outcome describing what changed, for a single summary log line.
     * @throws ScmException {@link ScmErrorCode#SCM_PROVIDER_CONFIGURATION_INVALID} when the document is
     *         malformed. Validation runs before any write, so a bad file cannot leave partial rows.
     */
    @Transactional
    public SeedOutcome write(ScmProviderSeedDocument document) {
        String providerCode = requireProviderCode(document);

        // Validate before touching the database: a stored invalid configuration would break every
        // request for the provider, including the ones needed to diagnose it.
        configurationFactory.validate(providerCode, document.configuration());

        Optional<ScmProvider> existing = providerRepository.findByProviderCodeIgnoreCase(providerCode);
        if (existing.isPresent() && !existing.get().isSeedManaged()) {
            log.info("SCM_SEED_SKIPPED: providerCode={} is not seed-managed", providerCode);
            return new SeedOutcome(providerCode, true, 0, 0, 0);
        }

        ScmProvider provider = existing.orElseGet(ScmProvider::new);
        provider.setProviderCode(providerCode);
        provider.setProviderName(document.providerName() != null ? document.providerName() : providerCode);
        provider.setProviderType(resolveProviderType(document, providerCode));
        provider.setConfiguration(new LinkedHashMap<>(document.configuration()));
        provider.setActive(document.activeOrDefault());
        provider.setDisplayOrder(document.displayOrderOrDefault());
        provider.setSeedManaged(true);

        ScmProvider saved = providerRepository.saveAndFlush(provider);

        int capabilities = reconcileCapabilities(saved, document);
        int operations = reconcileOperations(saved, document);
        int events = reconcileEvents(saved, document);

        // Both caches key on the row's updated_at, so eviction is belt-and-braces rather than strictly
        // required. It matters when a reconcile changes JSONB without altering the timestamp resolution.
        configurationFactory.evict(saved.getId());
        operationService.evictAll();

        log.info("SCM_SEED_APPLIED: providerCode={}, capabilities={}, operations={}, events={}",
                providerCode, capabilities, operations, events);
        return new SeedOutcome(providerCode, false, capabilities, operations, events);
    }

    private int reconcileCapabilities(ScmProvider provider, ScmProviderSeedDocument document) {
        Map<ScmCapabilityCode, ScmProviderCapability> existing = new LinkedHashMap<>();
        capabilityRepository.findByProviderId(provider.getId())
                .forEach(capability -> existing.put(capability.getCapabilityCode(), capability));

        List<ScmProviderCapability> toSave = new ArrayList<>();
        for (ScmProviderSeedDocument.CapabilitySeed seed : document.capabilitiesOrEmpty()) {
            ScmCapabilityCode code = ScmCapabilityCode.fromCode(seed.capabilityCode());
            if (code == null) {
                throw invalid(provider.getProviderCode(), "unknown capabilityCode '%s'"
                        .formatted(seed.capabilityCode()));
            }
            ScmProviderCapability capability = existing.remove(code);
            if (capability == null) {
                capability = new ScmProviderCapability();
                capability.setProvider(provider);
                capability.setCapabilityCode(code);
            }
            capability.setSupported(seed.supportedOrDefault());
            capability.setConfiguration(copyOrEmpty(seed.configuration()));
            toSave.add(capability);
        }

        capabilityRepository.saveAll(toSave);
        // Whatever remains was not declared by the document and is therefore no longer wanted.
        if (!existing.isEmpty()) {
            log.info("SCM_SEED_CAPABILITIES_REMOVED: providerCode={}, removed={}",
                    provider.getProviderCode(), existing.keySet());
            capabilityRepository.deleteAll(existing.values());
        }
        return toSave.size();
    }

    private int reconcileOperations(ScmProvider provider, ScmProviderSeedDocument document) {
        Map<ScmOperationCode, ScmProviderOperation> existing = new LinkedHashMap<>();
        operationRepository.findByProviderId(provider.getId())
                .forEach(operation -> existing.put(operation.getOperationCode(), operation));

        List<ScmProviderOperation> toSave = new ArrayList<>();
        for (ScmProviderSeedDocument.OperationSeed seed : document.operationsOrEmpty()) {
            ScmOperationCode code = ScmOperationCode.fromCode(seed.operationCode());
            if (code == null) {
                throw invalid(provider.getProviderCode(), "unknown operationCode '%s'"
                        .formatted(seed.operationCode()));
            }
            if (seed.endpointTemplate() == null || seed.endpointTemplate().isBlank()) {
                throw invalid(provider.getProviderCode(), "operation '%s' has no endpointTemplate"
                        .formatted(seed.operationCode()));
            }
            if (seed.httpMethod() == null || seed.httpMethod().isBlank()) {
                throw invalid(provider.getProviderCode(), "operation '%s' has no httpMethod"
                        .formatted(seed.operationCode()));
            }

            ScmProviderOperation operation = existing.remove(code);
            if (operation == null) {
                operation = new ScmProviderOperation();
                operation.setProvider(provider);
                operation.setOperationCode(code);
            }
            operation.setHttpMethod(seed.httpMethod().trim().toUpperCase(Locale.ROOT));
            operation.setEndpointTemplate(seed.endpointTemplate().trim());
            operation.setRequestConfiguration(copyOrEmpty(seed.requestConfiguration()));
            operation.setResponseMapping(copyOrEmpty(seed.responseMapping()));
            operation.setActive(seed.activeOrDefault());
            toSave.add(operation);
        }

        operationRepository.saveAll(toSave);
        if (!existing.isEmpty()) {
            log.info("SCM_SEED_OPERATIONS_REMOVED: providerCode={}, removed={}",
                    provider.getProviderCode(), existing.keySet());
            operationRepository.deleteAll(existing.values());
        }
        return toSave.size();
    }

    private int reconcileEvents(ScmProvider provider, ScmProviderSeedDocument document) {
        Map<String, ScmProviderEvent> existing = new LinkedHashMap<>();
        eventRepository.findByProviderId(provider.getId())
                .forEach(event -> existing.put(eventKey(event.getProviderEventName(), event.getProviderAction()),
                        event));

        List<ScmProviderEvent> toSave = new ArrayList<>();
        for (ScmProviderSeedDocument.EventSeed seed : document.eventsOrEmpty()) {
            NormalizedEventType normalizedType = NormalizedEventType.fromCode(seed.normalizedEventType());
            if (normalizedType == null) {
                throw invalid(provider.getProviderCode(), "unknown normalizedEventType '%s'"
                        .formatted(seed.normalizedEventType()));
            }
            if (seed.providerEventName() == null || seed.providerEventName().isBlank()) {
                throw invalid(provider.getProviderCode(), "an event mapping has no providerEventName");
            }

            String key = eventKey(seed.providerEventName(), seed.providerActionOrEmpty());
            ScmProviderEvent event = existing.remove(key);
            if (event == null) {
                event = new ScmProviderEvent();
                event.setProvider(provider);
                event.setProviderEventName(seed.providerEventName().trim());
                event.setProviderAction(seed.providerActionOrEmpty());
            }
            event.setNormalizedEventType(normalizedType);
            event.setConfiguration(copyOrEmpty(seed.configuration()));
            event.setActive(seed.activeOrDefault());
            toSave.add(event);
        }

        eventRepository.saveAll(toSave);
        if (!existing.isEmpty()) {
            log.info("SCM_SEED_EVENTS_REMOVED: providerCode={}, removed={}",
                    provider.getProviderCode(), existing.keySet());
            eventRepository.deleteAll(existing.values());
        }
        return toSave.size();
    }

    private String requireProviderCode(ScmProviderSeedDocument document) {
        if (document.providerCode() == null || document.providerCode().isBlank()) {
            throw new ScmException(ScmErrorCode.SCM_PROVIDER_CONFIGURATION_INVALID,
                    "seed document has no providerCode");
        }
        // Upper-cased so provider_code stays a stable, canonical key regardless of file formatting.
        return document.providerCode().trim().toUpperCase(Locale.ROOT);
    }

    private ScmProviderType resolveProviderType(ScmProviderSeedDocument document, String providerCode) {
        if (document.providerType() == null || document.providerType().isBlank()) {
            return ScmProviderType.CLOUD;
        }
        ScmProviderType type = ScmProviderType.fromCode(document.providerType());
        if (type == null) {
            throw invalid(providerCode, "unknown providerType '%s'".formatted(document.providerType()));
        }
        return type;
    }

    /** Case-insensitive natural key for an event mapping, matching the unique constraint's semantics. */
    private String eventKey(String eventName, String action) {
        return "%s|%s".formatted(
                eventName == null ? "" : eventName.trim().toLowerCase(Locale.ROOT),
                action == null ? "" : action.trim().toLowerCase(Locale.ROOT));
    }

    private Map<String, Object> copyOrEmpty(Map<String, Object> source) {
        return source != null ? new LinkedHashMap<>(source) : new LinkedHashMap<>();
    }

    private ScmException invalid(String providerCode, String detail) {
        return new ScmException(ScmErrorCode.SCM_PROVIDER_CONFIGURATION_INVALID,
                "providerCode=%s %s".formatted(providerCode, detail));
    }

    /**
     * @param skipped true when the provider was left alone because it is not seed-managed.
     */
    public record SeedOutcome(String providerCode, boolean skipped,
                              int capabilities, int operations, int events) {
    }
}
