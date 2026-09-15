package com.kksg.applicationServices.scm.provider.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * Detailed provider view, as returned by {@code GET /api/v1/scm/providers/{id}}.
 *
 * <p>Adds the two things a client legitimately needs beyond the summary: the capability list, so
 * unsupported features can be hidden rather than offered and then failed, and the OAuth scopes that
 * will be requested, so a consent screen can be described accurately before redirecting.
 *
 * <p>Deliberately absent: the raw configuration document, credential property names, and webhook
 * signature settings. Those are operational details with no client use and some disclosure risk.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScmProviderDetailResponse {

    private Integer id;
    private String providerCode;
    private String providerName;
    private String providerType;
    private boolean active;
    private int displayOrder;

    /** Root of the provider's REST API; useful for operator diagnostics and self-hosted setups. */
    private String apiBaseUrl;

    /** Scopes requested during authorization. */
    private List<String> oauthScopes;

    /** Capability codes declared supported. */
    private List<String> supportedCapabilities;

    /** Capability codes explicitly declared unsupported, so clients can explain a missing feature. */
    private List<String> unsupportedCapabilities;

    /** Operation codes that currently have an active configuration row. */
    private List<String> configuredOperations;
}
