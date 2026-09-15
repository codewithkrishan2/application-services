package com.kksg.applicationServices.scm.event.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kksg.applicationServices.scm.common.exception.ScmErrorCode;
import com.kksg.applicationServices.scm.common.exception.ScmException;
import com.kksg.applicationServices.scm.event.entity.ScmProviderEvent;
import com.kksg.applicationServices.scm.event.repository.ScmProviderEventRepository;
import com.kksg.applicationServices.scm.operation.engine.ResponseMapping;
import com.kksg.applicationServices.scm.provider.entity.ScmProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves a provider's raw webhook event to a mapping row, and parses that row's payload rules.
 *
 * <p>Lookup is by {@code (provider, eventName, action)} with the action normalized to {@code ""} when
 * absent, matching how the rows are stored.
 *
 * <p>An unmapped event is <b>not</b> an error. Providers send events nobody subscribed to, and GitHub in
 * particular sends a {@code ping} on webhook creation. Returning {@link Optional#empty()} lets the
 * webhook pipeline acknowledge such deliveries with 200 and ignore them, which is what stops a provider
 * from retrying forever and eventually disabling the webhook.
 */
@Service
public class ScmProviderEventService {

    private static final Logger log = LoggerFactory.getLogger(ScmProviderEventService.class);

    private static final String CONFIGURATION_PAYLOAD_KEY = "payload";

    private final ScmProviderEventRepository eventRepository;
    private final ObjectMapper objectMapper;

    public ScmProviderEventService(ScmProviderEventRepository eventRepository, ObjectMapper objectMapper) {
        this.eventRepository = eventRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * @param providerEventName provider's event name from its configured event header.
     * @param providerAction    provider's action, or {@code null} when it sends none.
     * @return the active mapping row, or empty when this event is not mapped.
     */
    @Transactional(readOnly = true)
    public Optional<ScmProviderEvent> findMapping(ScmProvider provider, String providerEventName,
                                                  String providerAction) {
        if (provider == null || provider.getId() == null || providerEventName == null
                || providerEventName.isBlank()) {
            return Optional.empty();
        }
        String normalizedAction = providerAction == null ? "" : providerAction.trim();

        return eventRepository
                .findByProviderIdAndProviderEventNameIgnoreCaseAndProviderActionIgnoreCase(
                        provider.getId(), providerEventName.trim(), normalizedAction)
                .filter(ScmProviderEvent::isActive);
    }

    /**
     * Parses the mapping's payload extraction rules.
     *
     * <p>Falls back to a pass-through mapping when a row declares none, so that a delivery is still
     * recorded and forwarded with whatever the payload contained, rather than failing because the
     * configuration was incomplete.
     */
    @Transactional(readOnly = true)
    public ResponseMapping resolvePayloadMapping(ScmProviderEvent event) {
        Map<String, Object> configuration = event.getConfiguration();
        if (configuration == null || !(configuration.get(CONFIGURATION_PAYLOAD_KEY) instanceof Map<?, ?> payload)) {
            return ResponseMapping.raw();
        }
        try {
            return objectMapper.convertValue(payload, ResponseMapping.class);
        } catch (IllegalArgumentException ex) {
            log.error("SCM_EVENT_PAYLOAD_MAPPING_INVALID: providerEventName={}, providerAction={}",
                    event.getProviderEventName(), event.getProviderAction());
            throw new ScmException(ScmErrorCode.SCM_PROVIDER_CONFIGURATION_INVALID,
                    "event mapping for '%s' has an invalid payload block".formatted(event.getProviderEventName()),
                    ex);
        }
    }

    @Transactional(readOnly = true)
    public List<ScmProviderEvent> findAll(ScmProvider provider) {
        if (provider == null || provider.getId() == null) {
            return List.of();
        }
        return eventRepository.findByProviderId(provider.getId());
    }
}
