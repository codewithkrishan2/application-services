package com.kksg.applicationServices.scm.webhook.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.kksg.applicationServices.scm.webhook.entity.ScmWebhookDelivery;
import com.kksg.applicationServices.scm.webhook.entity.ScmWebhookDeliveryStatus;

public interface ScmWebhookDeliveryRepository extends JpaRepository<ScmWebhookDelivery, Integer> {

    /** Idempotency lookup, matching the {@code (provider_id, delivery_id)} unique constraint. */
    Optional<ScmWebhookDelivery> findByProviderIdAndDeliveryId(Integer providerId, String deliveryId);

    boolean existsByProviderIdAndDeliveryId(Integer providerId, String deliveryId);

    /** Supports replaying deliveries that were interrupted or failed. */
    List<ScmWebhookDelivery> findByStatusAndReceivedAtBefore(ScmWebhookDeliveryStatus status, Instant before);

    List<ScmWebhookDelivery> findByRepositoryExternalIdOrderByReceivedAtDesc(String repositoryExternalId);
}
