package com.kksg.applicationServices.scm.capability.entity;

import com.kksg.applicationServices.common.entity.BaseEntity;
import com.kksg.applicationServices.scm.common.model.ScmCapabilityCode;
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
 * Declares whether a provider supports a given platform capability.
 *
 * <p>Answers <b>"can this provider do this?"</b>. The sibling {@code ScmProviderOperation} answers
 * <b>"how?"</b>. The distinction earns its keep in two concrete places:
 * <ul>
 *   <li>The UI needs to know, before any API call, that a Bitbucket connection cannot host a formal
 *       multi-comment review, so the feature is hidden rather than offered and then failed.</li>
 *   <li>Module 4 needs to plan a review pipeline up front and choose a degraded strategy - post
 *       individual comments instead of one review - without discovering the limitation through a
 *       404 midway.</li>
 * </ul>
 *
 * <p>An explicit {@code isSupported = false} row is meaningful and is <b>not</b> the same as a
 * missing row: it records a deliberate, documented "we checked, the provider cannot do this",
 * whereas absence means "not configured yet". Keeping both representable is why this is a boolean
 * column rather than row presence.
 *
 * <p>{@code configuration} carries capability-scoped limits that callers must respect, for example
 * a maximum comment length or maximum files per review.
 */
@Entity
@Table(name = "scm_provider_capabilities", uniqueConstraints = {
        @UniqueConstraint(name = "ux_scm_capability_provider_code", columnNames = {"provider_id", "capability_code"})
})
@Getter
@Setter
@NoArgsConstructor
public class ScmProviderCapability extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "provider_id", nullable = false)
    private ScmProvider provider;

    @Enumerated(EnumType.STRING)
    @Column(name = "capability_code", nullable = false, length = 60)
    private ScmCapabilityCode capabilityCode;

    @Column(name = "is_supported", nullable = false)
    private boolean supported = true;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "configuration", columnDefinition = "jsonb")
    private Map<String, Object> configuration = new LinkedHashMap<>();

    @Override
    public String toString() {
        return "ScmProviderCapability{capabilityCode=%s, supported=%s}".formatted(capabilityCode, supported);
    }
}
