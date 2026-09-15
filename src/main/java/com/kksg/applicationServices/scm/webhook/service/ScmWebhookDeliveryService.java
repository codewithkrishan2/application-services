package com.kksg.applicationServices.scm.webhook.service;

import com.kksg.applicationServices.scm.common.model.NormalizedEventType;
import com.kksg.applicationServices.scm.connection.entity.ScmConnection;
import com.kksg.applicationServices.scm.provider.entity.ScmProvider;
import com.kksg.applicationServices.scm.webhook.entity.ScmWebhookDelivery;
import com.kksg.applicationServices.scm.webhook.entity.ScmWebhookDeliveryStatus;
import com.kksg.applicationServices.scm.webhook.repository.ScmWebhookDeliveryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Owns the {@code scm_webhook_deliveries} table: claiming a delivery id and recording outcomes.
 *
 * <p>Split from {@code ScmWebhookService} because the two have different transactional needs. Claiming
 * must commit on its own so the delivery id is reserved before any processing begins; processing must run
 * outside a transaction so that publishing and downstream work do not hold a database connection. Keeping
 * both in one class would force one of those to be wrong.
 *
 * <p><b>The claim is the deduplication.</b> {@link #claim} attempts an insert and treats a unique-constraint
 * violation as "already claimed". A pre-check alone cannot work: two concurrent deliveries of the same
 * event both see no existing row before either commits. The database decides the winner, and the loser
 * learns it lost from the exception.
 */
@Service
public class ScmWebhookDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(ScmWebhookDeliveryService.class);

    private final ScmWebhookDeliveryRepository deliveryRepository;

    public ScmWebhookDeliveryService(ScmWebhookDeliveryRepository deliveryRepository) {
        this.deliveryRepository = deliveryRepository;
    }

    /**
     * Reserves a delivery id, or reports that it was already handled.
     *
     * <p>Runs in its own transaction so the row is durable before processing starts. If processing later
     * crashes, the row survives as evidence and can be replayed - and a provider retry in the meantime is
     * correctly recognised as a duplicate rather than starting a second review.
     */
    @Transactional
    public ClaimOutcome claim(ScmProvider provider,
                              ScmConnection connection,
                              String deliveryId,
                              String eventType,
                              String action,
                              NormalizedEventType normalizedEventType,
                              String repositoryExternalId,
                              String repositoryFullName,
                              Map<String, Object> payload) {

        Optional<ScmWebhookDelivery> existing =
                deliveryRepository.findByProviderIdAndDeliveryId(provider.getId(), deliveryId);
        if (existing.isPresent()) {
            log.info("SCM_WEBHOOK_DUPLICATE: providerCode={}, deliveryId={}, existingStatus={}",
                    provider.getProviderCode(), deliveryId, existing.get().getStatus());
            return new ClaimOutcome(true, existing.get());
        }

        ScmWebhookDelivery delivery = new ScmWebhookDelivery();
        delivery.setProvider(provider);
        delivery.setConnection(connection);
        delivery.setDeliveryId(deliveryId);
        delivery.setEventType(eventType);
        delivery.setAction(action);
        delivery.setNormalizedEventType(normalizedEventType);
        delivery.setRepositoryExternalId(repositoryExternalId);
        delivery.setRepositoryFullName(repositoryFullName);
        delivery.setPayload(payload);
        delivery.setStatus(ScmWebhookDeliveryStatus.RECEIVED);
        delivery.setReceivedAt(Instant.now());

        try {
            ScmWebhookDelivery saved = deliveryRepository.saveAndFlush(delivery);
            log.info("SCM_WEBHOOK_RECEIVED: providerCode={}, deliveryId={}, eventType={}, action={}, "
                            + "repositoryExternalId={}, connectionId={}",
                    provider.getProviderCode(), deliveryId, eventType, action, repositoryExternalId,
                    connection != null ? connection.getId() : null);
            return new ClaimOutcome(false, saved);

        } catch (DataIntegrityViolationException ex) {
            // Lost the race: another thread or instance claimed this delivery id first.
            log.info("SCM_WEBHOOK_DUPLICATE_RACE: providerCode={}, deliveryId={}",
                    provider.getProviderCode(), deliveryId);
            return deliveryRepository.findByProviderIdAndDeliveryId(provider.getId(), deliveryId)
                    .map(winner -> new ClaimOutcome(true, winner))
                    .orElseThrow(() -> ex);
        }
    }

    @Transactional
    public void markProcessing(Integer deliveryRecordId) {
        updateStatus(deliveryRecordId, ScmWebhookDeliveryStatus.PROCESSING, null, false);
    }

    @Transactional
    public void markProcessed(Integer deliveryRecordId) {
        updateStatus(deliveryRecordId, ScmWebhookDeliveryStatus.PROCESSED, null, true);
    }

    /**
     * @param reason short, non-sensitive explanation. Never a payload excerpt.
     */
    @Transactional
    public void markIgnored(Integer deliveryRecordId, String reason) {
        updateStatus(deliveryRecordId, ScmWebhookDeliveryStatus.IGNORED, reason, true);
    }

    /**
     * @param reason error code or short diagnostic. Never a token, secret or payload excerpt.
     */
    @Transactional
    public void markFailed(Integer deliveryRecordId, String reason) {
        updateStatus(deliveryRecordId, ScmWebhookDeliveryStatus.FAILED, reason, true);
    }

    @Transactional(readOnly = true)
    public Optional<ScmWebhookDelivery> find(Integer providerId, String deliveryId) {
        return deliveryRepository.findByProviderIdAndDeliveryId(providerId, deliveryId);
    }

    private void updateStatus(Integer deliveryRecordId,
                              ScmWebhookDeliveryStatus status,
                              String reason,
                              boolean terminal) {
        deliveryRepository.findById(deliveryRecordId).ifPresent(delivery -> {
            delivery.setStatus(status);
            if (reason != null) {
                delivery.setErrorMessage(truncate(reason));
            }
            if (terminal) {
                delivery.setProcessedAt(Instant.now());
            }
            deliveryRepository.save(delivery);
        });
    }

    /** Keeps the message within the column bound; an over-long diagnostic must not fail the write. */
    private String truncate(String value) {
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    /**
     * @param duplicate true when this delivery id had already been claimed, in which case the caller must
     *                  acknowledge and do nothing further.
     * @param delivery  the winning row, whether newly created or pre-existing.
     */
    public record ClaimOutcome(boolean duplicate, ScmWebhookDelivery delivery) {
    }
}
