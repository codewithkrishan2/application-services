package com.kksg.applicationServices.scm.event.entity;

import com.kksg.applicationServices.common.entity.BaseEntity;
import com.kksg.applicationServices.scm.common.model.NormalizedEventType;
import com.kksg.applicationServices.scm.provider.entity.ScmProvider;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps a provider's webhook event onto the platform's normalized event vocabulary.
 *
 * <p>Providers describe the same occurrence incompatibly:
 * <pre>
 *   GitHub     event "pull_request", action "opened"
 *   Bitbucket  event "pullrequest:created", no action
 *              -&gt; both become PULL_REQUEST_OPENED
 * </pre>
 * Because the mapping is a row, supporting a new provider's events is an insert.
 *
 * <p><b>{@code providerAction} uses {@code ""} rather than {@code NULL} for "this event has no
 * action".</b> PostgreSQL treats NULLs as distinct in a unique index, so a nullable column would allow
 * unlimited duplicate {@code (provider, event, NULL)} rows and the constraint would not constrain
 * anything. The empty string keeps the uniqueness guarantee real.
 *
 * <p>{@code configuration} holds a {@code payload} block using the same declarative vocabulary as
 * {@code scm_provider_operations.response_mapping}, describing where in this provider's payload to find
 * the repository, the pull request number and the owning account. Reusing that vocabulary means the
 * webhook pipeline shares the response normalizer instead of growing a parallel implementation.
 */
@Entity
@Table(name = "scm_provider_events", uniqueConstraints = {
        @UniqueConstraint(name = "ux_scm_event_provider_name_action",
                columnNames = {"provider_id", "provider_event_name", "provider_action"})
})
@Getter
@Setter
@NoArgsConstructor
public class ScmProviderEvent extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "provider_id", nullable = false)
    private ScmProvider provider;

    /** Provider's event name as delivered in its event header. */
    @Column(name = "provider_event_name", nullable = false, length = 100)
    private String providerEventName;

    /** Provider's action/sub-type, or {@code ""} when the event name already encodes it. */
    @Column(name = "provider_action", nullable = false, length = 100)
    private String providerAction = "";

    @Enumerated(EnumType.STRING)
    @Column(name = "normalized_event_type", nullable = false, length = 60)
    private NormalizedEventType normalizedEventType;

    /** Payload extraction rules; see the class comment. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "configuration", columnDefinition = "jsonb")
    private Map<String, Object> configuration = new LinkedHashMap<>();

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Override
    public String toString() {
        return "ScmProviderEvent{providerEventName=%s, providerAction=%s, normalizedEventType=%s}"
                .formatted(providerEventName, providerAction, normalizedEventType);
    }
}
