package com.kksg.applicationServices.scm.webhook.entity;

import com.kksg.applicationServices.common.entity.BaseEntity;
import com.kksg.applicationServices.scm.common.model.NormalizedEventType;
import com.kksg.applicationServices.scm.connection.entity.ScmConnection;
import com.kksg.applicationServices.scm.provider.entity.ScmProvider;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An inbound webhook delivery, recorded for idempotency and audit.
 *
 * <p><b>The unique constraint on {@code (provider_id, delivery_id)} is the idempotency mechanism.</b>
 * Providers retry deliveries whenever they do not see a timely 2xx - including when our processing
 * actually succeeded but the response was slow - so the same event arrives more than once as a matter of
 * routine. Without deduplication, one pushed commit could start several code reviews and post duplicate
 * comments.
 *
 * <p>Enforcement is in the database rather than in a service check, because two deliveries can be handled
 * concurrently by different threads or different instances: both would pass a
 * "does this delivery_id exist?" query before either had committed. The insert is therefore the claim,
 * and losing that insert is how a duplicate is detected.
 *
 * <p><b>Deliveries are recorded even when they cannot be attributed to a connection</b>
 * ({@code connection_id} is nullable) and even when the event is not mapped. A delivery whose repository
 * is not connected is exactly the kind of thing worth being able to see afterwards, and discarding it
 * would also discard the idempotency marker.
 *
 * <p>{@code payload} retains the provider document so a failed delivery can be diagnosed and replayed
 * without asking the provider to resend.
 */
@Entity
@Table(name = "scm_webhook_deliveries",
        uniqueConstraints = {
                @UniqueConstraint(name = "ux_scm_webhook_delivery_provider_delivery",
                        columnNames = {"provider_id", "delivery_id"})
        },
        indexes = {
                @Index(name = "ix_scm_webhook_deliveries_status", columnList = "status"),
                @Index(name = "ix_scm_webhook_deliveries_repository", columnList = "repository_external_id"),
                @Index(name = "ix_scm_webhook_deliveries_received", columnList = "received_at")
        })
@Getter
@Setter
@NoArgsConstructor
public class ScmWebhookDelivery extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "provider_id", nullable = false)
    private ScmProvider provider;

    /** Null when the delivery could not be attributed to a known connection. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "connection_id")
    private ScmConnection connection;

    /**
     * Provider's delivery identifier, or a {@code sha256:} digest of the raw body when the provider sends
     * none - so deduplication still works rather than being silently disabled.
     */
    @Column(name = "delivery_id", nullable = false, length = 200)
    private String deliveryId;

    /** Provider's raw event name, retained for diagnostics. */
    @Column(name = "event_type", length = 100)
    private String eventType;

    /** Provider's raw action, retained for diagnostics. */
    @Column(name = "action", length = 100)
    private String action;

    /** Normalized event, null when the provider event was not mapped. */
    @Enumerated(EnumType.STRING)
    @Column(name = "normalized_event_type", length = 60)
    private NormalizedEventType normalizedEventType;

    @Column(name = "repository_external_id", length = 200)
    private String repositoryExternalId;

    @Column(name = "repository_full_name", length = 300)
    private String repositoryFullName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb")
    private Map<String, Object> payload = new LinkedHashMap<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private ScmWebhookDeliveryStatus status = ScmWebhookDeliveryStatus.RECEIVED;

    /**
     * Why processing failed, or why a delivery was ignored.
     *
     * <p>Populated from {@code ScmErrorCode} values and short diagnostics only. Never from a provider
     * payload excerpt, which could contain a webhook secret echoed back by a misconfigured integration.
     */
    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt = Instant.now();

    @Column(name = "processed_at")
    private Instant processedAt;

    @Override
    public String toString() {
        // Excludes payload: it is large and provider-controlled.
        return "ScmWebhookDelivery{id=%s, deliveryId=%s, eventType=%s, status=%s}"
                .formatted(getId(), deliveryId, eventType, status);
    }
}
