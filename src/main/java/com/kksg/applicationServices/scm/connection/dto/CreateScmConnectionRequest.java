package com.kksg.applicationServices.scm.connection.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Completes a connection for a client that captured the provider's authorization code itself.
 *
 * <p>Used by {@code POST /api/v1/scm/connections}. The caller is already authenticated, so the user
 * identity comes from the bearer token and no {@code state} is required - a verified token is stronger
 * evidence of identity than a state parameter. The browser-redirect variant of the flow, which has no
 * bearer token, is handled by the callback endpoint and does verify state.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CreateScmConnectionRequest {

    /** Stable provider code, e.g. {@code GITHUB}. */
    @NotBlank(message = "providerCode is required")
    private String providerCode;

    /**
     * One-time authorization code from the provider.
     *
     * <p>Treated as credential material: it is never logged, and validation failures report only that
     * it was absent.
     */
    @NotBlank(message = "code is required")
    private String code;
}
