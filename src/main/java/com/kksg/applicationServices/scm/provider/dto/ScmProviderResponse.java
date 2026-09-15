package com.kksg.applicationServices.scm.provider.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Summary of a provider, as returned by {@code GET /api/v1/scm/providers}.
 *
 * <p>Carries only what a client needs to render a provider picker. The provider's JSONB
 * configuration is not exposed: it names the configuration properties that hold OAuth client
 * credentials, and publishing internal configuration topology serves no client purpose.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScmProviderResponse {

    private Integer id;
    private String providerCode;
    private String providerName;
    private String providerType;
    private boolean active;
    private int displayOrder;
}
