package com.kksg.applicationServices.scm.operation.entity;

import com.kksg.applicationServices.common.entity.BaseEntity;
import com.kksg.applicationServices.scm.common.model.ScmOperationCode;
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
 * The HTTP recipe that implements one normalized operation for one provider.
 *
 * <p>This table is the heart of the configuration-driven design. {@code LIST_REPOSITORIES} is one
 * platform concept with two rows behind it:
 * <pre>
 *   GITHUB    GET /user/repos              response is a bare array
 *   BITBUCKET GET /repositories            response is {"values": [...], "next": "..."}
 * </pre>
 * Modules 3-8 reference only the {@code operationCode}; the difference lives here as data.
 *
 * <p>{@code endpointTemplate} is relative to the provider's {@code api.baseUrl} and may contain
 * {@code {{placeholder}}} tokens, e.g.
 * {@code /repos/{{owner}}/{{repo}}/pulls/{{pullRequestNumber}}/files}.
 *
 * <p>{@code isActive} allows an operation to be switched off without deleting its configuration -
 * useful when a provider deprecates an endpoint and the replacement is not yet configured. An
 * inactive operation fails with {@code SCM_OPERATION_NOT_CONFIGURED}, which is distinguishable from
 * "the provider cannot do this" ({@code SCM_OPERATION_NOT_SUPPORTED}, driven by capabilities).
 */
@Entity
@Table(name = "scm_provider_operations", uniqueConstraints = {
        @UniqueConstraint(name = "ux_scm_operation_provider_code", columnNames = {"provider_id", "operation_code"})
})
@Getter
@Setter
@NoArgsConstructor
public class ScmProviderOperation extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "provider_id", nullable = false)
    private ScmProvider provider;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation_code", nullable = false, length = 60)
    private ScmOperationCode operationCode;

    @Column(name = "http_method", nullable = false, length = 10)
    private String httpMethod;

    /** Path relative to {@code api.baseUrl}; may contain {@code {{placeholder}}} tokens. */
    @Column(name = "endpoint_template", nullable = false, length = 500)
    private String endpointTemplate;

    /** See {@code RequestConfiguration}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_configuration", columnDefinition = "jsonb")
    private Map<String, Object> requestConfiguration = new LinkedHashMap<>();

    /** See {@code ResponseMapping}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_mapping", columnDefinition = "jsonb")
    private Map<String, Object> responseMapping = new LinkedHashMap<>();

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Override
    public String toString() {
        return "ScmProviderOperation{operationCode=%s, httpMethod=%s, endpointTemplate=%s}"
                .formatted(operationCode, httpMethod, endpointTemplate);
    }
}
