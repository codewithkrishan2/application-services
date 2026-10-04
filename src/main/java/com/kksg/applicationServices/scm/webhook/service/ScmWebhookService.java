package com.kksg.applicationServices.scm.webhook.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kksg.applicationServices.scm.adapter.ScmAdapterRegistry;
import com.kksg.applicationServices.scm.common.exception.ScmErrorCode;
import com.kksg.applicationServices.scm.common.exception.ScmException;
import com.kksg.applicationServices.scm.common.model.NormalizedEventType;
import com.kksg.applicationServices.scm.common.model.NormalizedWebhookEvent;
import com.kksg.applicationServices.scm.common.util.JsonNodePaths;
import com.kksg.applicationServices.scm.connection.entity.ScmConnection;
import com.kksg.applicationServices.scm.connection.service.ScmConnectionService;
import com.kksg.applicationServices.scm.event.ScmEventPublisher;
import com.kksg.applicationServices.scm.event.ScmWebhookEventDispatcher;
import com.kksg.applicationServices.scm.event.entity.ScmProviderEvent;
import com.kksg.applicationServices.scm.event.service.ScmProviderEventService;
import com.kksg.applicationServices.scm.operation.engine.ResponseMapping;
import com.kksg.applicationServices.scm.operation.engine.ScmResponseNormalizer;
import com.kksg.applicationServices.scm.provider.config.ProviderConfiguration;
import com.kksg.applicationServices.scm.provider.config.ProviderCredentialResolver;
import com.kksg.applicationServices.scm.provider.entity.ScmProvider;
import com.kksg.applicationServices.scm.provider.service.ScmProviderService;
import com.kksg.applicationServices.scm.webhook.entity.ScmWebhookDelivery;
import com.kksg.applicationServices.scm.webhook.entity.ScmWebhookDeliveryStatus;
import com.kksg.applicationServices.scm.webhook.verification.WebhookSignatureVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The generic webhook pipeline. One implementation serves every provider.
 *
 * <pre>
 *   receive
 *      -&gt; identify provider          (path)
 *      -&gt; load configuration         (scm_providers.configuration)
 *      -&gt; verify signature           (HMAC per configuration, or adapter hook)
 *      -&gt; identify event + delivery  (configured headers)
 *      -&gt; map event                  (scm_provider_events)   unmapped -&gt; IGNORED
 *      -&gt; claim delivery id          (unique constraint)      duplicate -&gt; acknowledge
 *      -&gt; normalize payload          (shared response normalizer)
 *      -&gt; publish normalized event   (ScmEventPublisher -&gt; ScmWebhookEventDispatcher)
 * </pre>
 *
 * <p><b>Order is deliberate.</b> Signature verification comes before anything is parsed or persisted, so
 * an unauthenticated caller cannot cause a database write or make us interpret hostile JSON. Claiming the
 * delivery id happens before publishing, so a provider retry that arrives mid-processing is recognised as
 * a duplicate rather than starting duplicate work.
 *
 * <p><b>Failure is acknowledged, not propagated.</b> When processing fails after the claim, the delivery is
 * recorded {@code FAILED} and the caller still receives a 2xx. Returning an error instead would be worse
 * than useless: the provider's retry carries the same delivery id, which the claim would reject as a
 * duplicate, so the retry could never succeed - and providers disable webhooks that keep failing. Instead
 * a {@code FAILED} row is replayable, and a provider retry is explicitly allowed to reprocess it.
 *
 * <p>This module does not review code. It produces normalized events and hands them to Module 4.
 */
@Service
public class ScmWebhookService {

    private static final Logger log = LoggerFactory.getLogger(ScmWebhookService.class);

    /**
     * Field names an event's {@code payload} mapping is expected to produce. This is the contract between
     * {@code scm_provider_events.configuration} and this pipeline.
     */
    private static final String FIELD_REPOSITORY_EXTERNAL_ID = "repositoryExternalId";
    private static final String FIELD_REPOSITORY_FULL_NAME = "repositoryFullName";
    private static final String FIELD_PULL_REQUEST_NUMBER = "pullRequestNumber";
    private static final String FIELD_ACCOUNT_EXTERNAL_ID = "accountExternalId";

    private final ScmProviderService providerService;
    private final ProviderCredentialResolver credentialResolver;
    private final WebhookSignatureVerifier signatureVerifier;
    private final ScmProviderEventService eventService;
    private final ScmResponseNormalizer responseNormalizer;
    private final ScmConnectionService connectionService;
    private final ScmWebhookDeliveryService deliveryService;
    private final ScmEventPublisher eventPublisher;
    private final ScmAdapterRegistry adapterRegistry;
    private final ObjectMapper objectMapper;

    public ScmWebhookService(ScmProviderService providerService,
                             ProviderCredentialResolver credentialResolver,
                             WebhookSignatureVerifier signatureVerifier,
                             ScmProviderEventService eventService,
                             ScmResponseNormalizer responseNormalizer,
                             ScmConnectionService connectionService,
                             ScmWebhookDeliveryService deliveryService,
                             ScmEventPublisher eventPublisher,
                             ScmAdapterRegistry adapterRegistry,
                             ObjectMapper objectMapper) {
        this.providerService = providerService;
        this.credentialResolver = credentialResolver;
        this.signatureVerifier = signatureVerifier;
        this.eventService = eventService;
        this.responseNormalizer = responseNormalizer;
        this.connectionService = connectionService;
        this.deliveryService = deliveryService;
        this.eventPublisher = eventPublisher;
        this.adapterRegistry = adapterRegistry;
        this.objectMapper = objectMapper;
    }

    /**
     * @param providerCode from the request path.
     * @param headers      request headers with lower-cased names.
     * @param rawBody      exact received bytes - required for signature verification.
     * @throws ScmException {@link ScmErrorCode#SCM_WEBHOOK_SIGNATURE_INVALID} when authenticity cannot be
     *         established, or {@link ScmErrorCode#SCM_PROVIDER_NOT_FOUND} for an unknown provider. These
     *         are the only cases that reject the request outright, because in both the caller has not
     *         proven it is the provider.
     */
    public WebhookProcessingResult process(String providerCode, Map<String, String> headers, byte[] rawBody) {
        ScmProvider provider = providerService.requireActiveByCode(providerCode);
        ProviderConfiguration configuration = providerService.getConfiguration(provider);
        ProviderConfiguration.Webhook webhookConfiguration = configuration.webhookOrEmpty();

        verifySignature(provider, webhookConfiguration, headers, rawBody);

        JsonNode payload = parsePayload(provider, rawBody);
        String eventName = readHeader(headers, webhookConfiguration.eventHeader());
        String action = readAction(payload, webhookConfiguration);
        String deliveryId = resolveDeliveryId(headers, webhookConfiguration, rawBody);

        Optional<ScmProviderEvent> mapping = eventService.findMapping(provider, eventName, action);
        if (mapping.isEmpty()) {
            return recordUnmapped(provider, deliveryId, eventName, action, payload);
        }

        ScmProviderEvent event = mapping.get();
        ResponseMapping payloadMapping = eventService.resolvePayloadMapping(event);
        ObjectNode normalizedPayload = responseNormalizer.normalizeObject(payload, payloadMapping);

        String repositoryExternalId = readText(normalizedPayload, FIELD_REPOSITORY_EXTERNAL_ID);
        String repositoryFullName = readText(normalizedPayload, FIELD_REPOSITORY_FULL_NAME);
        String accountExternalId = readText(normalizedPayload, FIELD_ACCOUNT_EXTERNAL_ID);
        Integer pullRequestNumber = readInt(normalizedPayload, FIELD_PULL_REQUEST_NUMBER);

        ScmConnection connection =
                connectionService.findConnectionForDelivery(provider.getId(), accountExternalId);

        ScmWebhookDeliveryService.ClaimOutcome claim = deliveryService.claim(
                provider, connection, deliveryId, eventName, action, event.getNormalizedEventType(),
                repositoryExternalId, repositoryFullName, toMap(payload));

        // A FAILED row is the one duplicate worth reprocessing: the provider's retry is a free chance to
        // recover from a transient fault. Any other status means the delivery is already accounted for.
        if (claim.duplicate() && claim.delivery().getStatus() != ScmWebhookDeliveryStatus.FAILED) {
            return new WebhookProcessingResult(WebhookOutcome.DUPLICATE, deliveryId,
                    claim.delivery().getId(), event.getNormalizedEventType());
        }

        return forward(provider, event, claim.delivery(), connection, deliveryId, eventName, action,
                repositoryExternalId, repositoryFullName, pullRequestNumber, payload);
    }

    private WebhookProcessingResult forward(ScmProvider provider,
                                            ScmProviderEvent event,
                                            ScmWebhookDelivery delivery,
                                            ScmConnection connection,
                                            String deliveryId,
                                            String eventName,
                                            String action,
                                            String repositoryExternalId,
                                            String repositoryFullName,
                                            Integer pullRequestNumber,
                                            JsonNode payload) {

        deliveryService.markProcessing(delivery.getId());
        try {
            ScmWebhookEventDispatcher.DispatchResult dispatch =
                    eventPublisher.publish(NormalizedWebhookEvent.builder()
                    .eventType(event.getNormalizedEventType())
                    .providerCode(provider.getProviderCode())
                    .deliveryRecordId(delivery.getId())
                    .deliveryId(deliveryId)
                    .connectionId(connection != null ? connection.getId() : null)
                    .repositoryExternalId(repositoryExternalId)
                    .repositoryFullName(repositoryFullName)
                    .pullRequestNumber(pullRequestNumber)
                    .providerEventName(eventName)
                    .providerAction(action)
                    .rawPayload(toMap(payload))
                    .build());

            // A consumer failure is reported as a value, not thrown, so this record is reached. The
            // delivery is marked FAILED - which is what makes it replayable - but the request is still
            // acknowledged, because a non-2xx would make the provider retry with the same delivery id and
            // the claim would reject that retry as a duplicate forever.
            if (dispatch.hasFailures()) {
                deliveryService.markFailed(delivery.getId(), dispatch.failureReason());
                log.error("SCM_WEBHOOK_CONSUMER_FAILED: providerCode={}, deliveryId={}, eventType={}, "
                                + "consumers={}, succeeded={}, failures={}",
                        provider.getProviderCode(), deliveryId, event.getNormalizedEventType(),
                        dispatch.consumersInvoked(), dispatch.succeeded(), dispatch.failures());
                return new WebhookProcessingResult(WebhookOutcome.FAILED, deliveryId, delivery.getId(),
                        event.getNormalizedEventType());
            }

            // Zero consumers is still PROCESSED: this module's job is to normalize and publish, and it
            // did. Recording FAILED would alert on a deployment that simply has no orchestration
            // installed, and recording IGNORED would collide with the meaning it already carries for
            // unmapped provider events. The dispatcher logs SCM_EVENT_NO_CONSUMER so the state is visible.
            deliveryService.markProcessed(delivery.getId());
            return new WebhookProcessingResult(WebhookOutcome.ACCEPTED, deliveryId, delivery.getId(),
                    event.getNormalizedEventType());

        } catch (ScmException ex) {
            // Records the enumerated error code, which is safe; the full message stays in the log.
            deliveryService.markFailed(delivery.getId(), ex.getErrorCode().name());
            log.error("SCM_WEBHOOK_PROCESSING_FAILED: providerCode={}, deliveryId={}, errorCode={}",
                    provider.getProviderCode(), deliveryId, ex.getErrorCode());
            return new WebhookProcessingResult(WebhookOutcome.FAILED, deliveryId, delivery.getId(),
                    event.getNormalizedEventType());

        } catch (Exception ex) {
            deliveryService.markFailed(delivery.getId(), ex.getClass().getSimpleName());
            log.error("SCM_WEBHOOK_PROCESSING_ERROR: providerCode={}, deliveryId={}",
                    provider.getProviderCode(), deliveryId, ex);
            return new WebhookProcessingResult(WebhookOutcome.FAILED, deliveryId, delivery.getId(),
                    event.getNormalizedEventType());
        }
    }

    /**
     * Verification, with the adapter hook taking precedence.
     *
     * <p>Runs before parsing and before any write: an unauthenticated caller must not be able to cause
     * either.
     */
    private void verifySignature(ScmProvider provider,
                                ProviderConfiguration.Webhook webhookConfiguration,
                                Map<String, String> headers,
                                byte[] rawBody) {

        String secret = credentialResolver.resolveWebhookSecret(provider).orElse(null);

        boolean valid = adapterRegistry.find(provider.getProviderCode())
                .flatMap(adapter -> adapter.verifyWebhookSignature(rawBody, headers, secret))
                .orElseGet(() -> signatureVerifier.verify(rawBody, headers, webhookConfiguration, secret));

        if (!valid) {
            log.warn("SCM_WEBHOOK_REJECTED: providerCode={}, reason=signature", provider.getProviderCode());
            throw new ScmException(ScmErrorCode.SCM_WEBHOOK_SIGNATURE_INVALID,
                    "providerCode=%s".formatted(provider.getProviderCode()));
        }
    }

    /**
     * Records a delivery whose event the platform does not subscribe to.
     *
     * <p>Persisted rather than dropped so that the delivery id is still claimed - a retry of an ignorable
     * event should stay ignorable - and so an operator can see what a provider is actually sending.
     * GitHub's {@code ping} on webhook creation lands here.
     */
    private WebhookProcessingResult recordUnmapped(ScmProvider provider, String deliveryId,
                                                   String eventName, String action, JsonNode payload) {
        log.info("SCM_WEBHOOK_EVENT_UNMAPPED: providerCode={}, eventType={}, action={}, deliveryId={}",
                provider.getProviderCode(), eventName, action, deliveryId);

        ScmWebhookDeliveryService.ClaimOutcome claim = deliveryService.claim(
                provider, null, deliveryId, eventName, action, null, null, null, toMap(payload));

        if (!claim.duplicate()) {
            deliveryService.markIgnored(claim.delivery().getId(), "event not mapped");
        }
        return new WebhookProcessingResult(
                claim.duplicate() ? WebhookOutcome.DUPLICATE : WebhookOutcome.IGNORED,
                deliveryId, claim.delivery().getId(), null);
    }

    private JsonNode parsePayload(ScmProvider provider, byte[] rawBody) {
        if (rawBody == null || rawBody.length == 0) {
            throw new ScmException(ScmErrorCode.SCM_PROVIDER_API_ERROR, "webhook payload is empty");
        }
        try {
            return objectMapper.readTree(rawBody);
        } catch (Exception ex) {
            log.warn("SCM_WEBHOOK_PAYLOAD_UNPARSEABLE: providerCode={}", provider.getProviderCode());
            throw new ScmException(ScmErrorCode.SCM_PROVIDER_API_ERROR, "webhook payload is not valid JSON");
        }
    }

    private String readAction(JsonNode payload, ProviderConfiguration.Webhook webhookConfiguration) {
        // Providers whose event name already encodes the action declare no actionPath.
        if (webhookConfiguration.actionPath() == null || webhookConfiguration.actionPath().isBlank()) {
            return "";
        }
        String action = JsonNodePaths.textAt(payload, webhookConfiguration.actionPath());
        return action != null ? action : "";
    }

    /**
     * Determines the idempotency key.
     *
     * <p>Falls back to a digest of the raw body when the provider sends no delivery header. Deduplication
     * must not silently switch off: a body digest still collapses identical retries, which is the common
     * case, and is far better than treating every delivery as unique.
     */
    private String resolveDeliveryId(Map<String, String> headers,
                                     ProviderConfiguration.Webhook webhookConfiguration,
                                     byte[] rawBody) {
        String fromHeader = readHeader(headers, webhookConfiguration.deliveryIdHeader());
        if (fromHeader != null && !fromHeader.isBlank()) {
            return fromHeader.trim();
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(digest.digest(rawBody));
        } catch (Exception ex) {
            throw new ScmException(ScmErrorCode.SCM_PROVIDER_API_ERROR, "delivery id could not be determined");
        }
    }

    private String readHeader(Map<String, String> headers, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return headers.get(name.toLowerCase(Locale.ROOT));
    }

    private String readText(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private Integer readInt(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.asInt();
        }
        // Written as a statement rather than a conditional expression deliberately. A ternary mixing
        // the primitive from asInt() with the Integer from tryParseInt() promotes the whole expression
        // to int, so a null from tryParseInt is unboxed and throws - and this runs before the delivery
        // is claimed, so a provider sending a non-numeric pull request number would get a 500, retry,
        // and get a 500 again until the webhook was disabled.
        return tryParseInt(value.asText());
    }

    private Integer tryParseInt(String value) {
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(JsonNode payload) {
        if (payload == null || !payload.isObject()) {
            return Map.of();
        }
        return objectMapper.convertValue(payload, Map.class);
    }

    /** Outcome categories a webhook request can produce. */
    public enum WebhookOutcome {
        /** Normalized and published. */
        ACCEPTED,
        /** Already claimed; no further action taken. */
        DUPLICATE,
        /** Recognised but not subscribed to. */
        IGNORED,
        /** Claimed but processing failed; recorded for replay. */
        FAILED
    }

    /**
     * @param deliveryRecordId internal id of the {@code scm_webhook_deliveries} row, useful for support
     *                         correlation.
     */
    public record WebhookProcessingResult(WebhookOutcome outcome,
                                          String deliveryId,
                                          Integer deliveryRecordId,
                                          NormalizedEventType normalizedEventType) {
    }
}
