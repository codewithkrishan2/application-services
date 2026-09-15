package com.kksg.applicationServices.scm.provider.controller;

import com.kksg.applicationServices.common.response.ApiResponse;
import com.kksg.applicationServices.scm.provider.dto.ScmProviderDetailResponse;
import com.kksg.applicationServices.scm.provider.dto.ScmProviderResponse;
import com.kksg.applicationServices.scm.provider.service.ScmProviderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only catalogue of supported SCM providers.
 *
 * <p>Thin by design: each method delegates to {@code ScmProviderService} and wraps the result in the
 * project's {@code ApiResponse} envelope. Failures propagate to {@code GlobalExceptionHandler},
 * which maps {@code ScmException} through its {@code ScmErrorCode}, so there is no try/catch noise
 * here and error responses stay consistent across the module.
 */
@RestController
@RequestMapping("/api/v1/scm/providers")
@Tag(name = "SCM Providers", description = "Catalogue of supported source control providers")
@SecurityRequirement(name = "bearerAuth")
public class ScmProviderController {

    private final ScmProviderService providerService;

    public ScmProviderController(ScmProviderService providerService) {
        this.providerService = providerService;
    }

    @GetMapping
    @Operation(summary = "List providers",
            description = "Returns the active SCM providers a user may connect, in display order")
    public ResponseEntity<ApiResponse<List<ScmProviderResponse>>> listProviders() {
        return ResponseEntity.ok(ApiResponse.success(providerService.listActiveProviders()));
    }

    @GetMapping("/{providerId}")
    @Operation(summary = "Get provider",
            description = "Returns a provider with its declared capabilities, configured operations and OAuth scopes")
    public ResponseEntity<ApiResponse<ScmProviderDetailResponse>> getProvider(@PathVariable Integer providerId) {
        return ResponseEntity.ok(ApiResponse.success(providerService.getProviderDetail(providerId)));
    }
}
