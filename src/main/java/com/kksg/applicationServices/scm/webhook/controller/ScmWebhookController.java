package com.kksg.applicationServices.scm.webhook.controller;

import com.kksg.applicationServices.common.response.ApiResponse;
import com.kksg.applicationServices.scm.webhook.dto.ScmWebhookAckResponse;
import com.kksg.applicationServices.scm.webhook.service.ScmWebhookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Receives webhook deliveries from every provider through one endpoint.
 *
 * <p><b>Unauthenticated at the HTTP layer, authenticated at the payload layer.</b> Providers cannot present
 * an application bearer token, so this path is excluded from JWT protection in {@code SecurityConfig}.
 * Authenticity is instead established by the signature check inside {@code ScmWebhookService}, which runs
 * before the payload is parsed and before anything is written. An unsigned or wrongly signed delivery is
 * rejected with {@code SCM_WEBHOOK_SIGNATURE_INVALID} (401) and leaves no trace in the database.
 *
 * <p><b>Why {@code byte[]} and not a parsed DTO.</b> The HMAC is computed over the exact bytes the provider
 * sent. Letting Spring deserialize and then re-serializing to verify would change whitespace and key order
 * and break every signature. The raw body is also what makes the digest fallback for providers without a
 * delivery-id header meaningful.
 *
 * <p>Thin, like the other controllers: it adapts the servlet request into
 * {@code (providerCode, headers, rawBody)} and delegates. It contains no signature logic, no event mapping
 * and no persistence.
 */
@RestController
@RequestMapping("/api/v1/scm/webhooks")
@Tag(name = "SCM Webhooks", description = "Inbound provider webhook endpoint")
public class ScmWebhookController {

    private final ScmWebhookService webhookService;

    public ScmWebhookController(ScmWebhookService webhookService) {
        this.webhookService = webhookService;
    }

    /**
     * Always answers 2xx once authenticity is established - including for duplicates, unmapped events and
     * internal failures.
     *
     * <p>That is intentional. A provider that does not receive a timely 2xx retries and, after enough
     * consecutive failures, disables the webhook. Since the delivery has already been recorded and
     * deduplicated, a retry could not be processed again anyway; a {@code FAILED} row is the replay
     * mechanism instead. The body reports which outcome occurred so it is visible rather than hidden.
     */
    @PostMapping("/{providerCode}")
    @Operation(summary = "Receive a provider webhook",
            description = "Verifies the provider signature, deduplicates by delivery id, records the "
                    + "delivery, normalizes the event and forwards it. Authenticated by signature, not by "
                    + "bearer token.")
    public ResponseEntity<ApiResponse<ScmWebhookAckResponse>> receive(
            @PathVariable String providerCode,
            @RequestBody(required = false) byte[] rawBody,
            HttpServletRequest request) {

        ScmWebhookService.WebhookProcessingResult result =
                webhookService.process(providerCode, extractHeaders(request), rawBody);

        ScmWebhookAckResponse body = ScmWebhookAckResponse.builder()
                .outcome(result.outcome().name())
                .deliveryId(result.deliveryId())
                .eventType(result.normalizedEventType() != null ? result.normalizedEventType().name() : null)
                .build();

        return ResponseEntity.ok(ApiResponse.success(body));
    }

    /**
     * Copies request headers into a lower-cased map.
     *
     * <p>Lower-casing once here means every downstream lookup (signature header, event header, delivery-id
     * header) is a plain map access, and provider configuration can name headers in whatever case the
     * provider's documentation uses. HTTP header names are case-insensitive, so this loses nothing.
     */
    private Map<String, String> extractHeaders(HttpServletRequest request) {
        Map<String, String> headers = new HashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        if (names == null) {
            return headers;
        }
        for (String name : Collections.list(names)) {
            headers.put(name.toLowerCase(Locale.ROOT), request.getHeader(name));
        }
        return headers;
    }
}
