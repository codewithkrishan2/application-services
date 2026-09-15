package com.kksg.applicationServices.scm.provider.entity;

import com.kksg.applicationServices.common.entity.BaseEntity;
import com.kksg.applicationServices.scm.common.model.ScmProviderType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An SCM provider the platform supports (GITHUB, BITBUCKET, ...).
 *
 * <p>This row, plus its related operation/capability/event rows, <i>is</i> the provider
 * integration. Adding a provider means inserting records here rather than writing a service.
 *
 * <p><b>{@code providerCode} is the stable identity.</b> It is what other tables reference
 * logically, what appears in logs, and what seed data keys on. It must never be renamed once
 * released. Note that it is <i>not</i> a switch discriminator: no business code should compare it
 * to a literal. Its purpose is to look up configuration, which is then obeyed generically.
 *
 * <p><b>{@code configuration} is JSONB</b> and holds everything that varies per provider without
 * varying per operation - API base URL, OAuth endpoints, webhook signature scheme, paging style.
 * Keeping it in one schemaless column is what makes the schema stable across new providers; the
 * alternative ({@code github_api_url}, {@code bitbucket_api_url}, ...) forces a migration per
 * provider. It is read through {@code ProviderConfigurationFactory}, which parses and validates it
 * into a typed {@code ProviderConfiguration}.
 *
 * <p><b>{@code seedManaged}</b> lets the bundled seeder reconcile this row toward the declared state
 * in {@code classpath:scm/seed/*.json} on every startup, which is how configuration fixes ship.
 * An operator who needs to hand-tune a provider in place sets it to {@code false} and the seeder
 * then leaves the row, and its children, untouched.
 */
@Entity
@Table(name = "scm_providers", indexes = {
        @Index(name = "ux_scm_providers_code", columnList = "provider_code", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
public class ScmProvider extends BaseEntity {

    @Column(name = "provider_code", nullable = false, unique = true, length = 50)
    private String providerCode;

    @Column(name = "provider_name", nullable = false, length = 100)
    private String providerName;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider_type", nullable = false, length = 30)
    private ScmProviderType providerType = ScmProviderType.CLOUD;

    /**
     * Provider settings as JSONB. {@code LinkedHashMap} preserves key order so that a row read back
     * from the database is byte-comparable with the seed document, which keeps reconciliation from
     * reporting spurious drift.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "configuration", columnDefinition = "jsonb")
    private Map<String, Object> configuration = new LinkedHashMap<>();

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    /** Presentation order for provider pickers; lower sorts first. */
    @Column(name = "display_order", nullable = false)
    private int displayOrder = 0;

    /** When true, the bundled seeder owns this row and its child configuration. */
    @Column(name = "seed_managed", nullable = false)
    private boolean seedManaged = true;

    @Override
    public String toString() {
        // Excludes configuration: it names credential properties and is large enough to be noise.
        return "ScmProvider{id=%s, providerCode=%s, active=%s}".formatted(getId(), providerCode, active);
    }
}
