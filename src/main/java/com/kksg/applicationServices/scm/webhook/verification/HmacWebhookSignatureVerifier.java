package com.kksg.applicationServices.scm.webhook.verification;

import com.kksg.applicationServices.scm.provider.config.ProviderConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

/**
 * Configuration-driven HMAC verification, covering the scheme every MVP provider uses.
 *
 * <p>Providers differ only in the header name, the digest, and a prefix on the value:
 * <pre>
 *   x-hub-signature-256: sha256=&lt;hex&gt;      HMAC-SHA256 over the raw body
 *   x-hub-signature:     sha1=&lt;hex&gt;        HMAC-SHA1 over the raw body
 * </pre>
 * All three are configuration ({@code signatureHeader}, {@code signatureAlgorithm},
 * {@code signaturePrefix}), so one implementation serves every provider and a new one needs no code.
 *
 * <p><b>Constant-time comparison.</b> {@link MessageDigest#isEqual} is used rather than
 * {@code String.equals}, which returns as soon as it finds a differing byte. That timing difference is
 * enough to let an attacker recover a valid signature byte by byte over many requests, so the naive
 * comparison would defeat the verification it appears to perform.
 *
 * <p><b>Fail closed.</b> A missing secret, a missing header, a malformed value or an unexpected algorithm
 * all return false. The alternative - accepting a delivery whose authenticity could not be checked -
 * would make the whole mechanism advisory. The one exception is an explicit
 * {@code signatureAlgorithm: NONE}, which is a deliberate, documented decision recorded in provider
 * configuration and logged.
 */
@Component
public class HmacWebhookSignatureVerifier implements WebhookSignatureVerifier {

    private static final Logger log = LoggerFactory.getLogger(HmacWebhookSignatureVerifier.class);

    @Override
    public boolean verify(byte[] rawBody,
                          Map<String, String> headers,
                          ProviderConfiguration.Webhook webhookConfiguration,
                          String secret) {

        ProviderConfiguration.SignatureAlgorithm algorithm =
                webhookConfiguration.configuredSignatureAlgorithm().orElse(null);

        if (algorithm == null) {
            // Unconfigured is not the same as unsigned. A provider row with no webhook block, or one
            // missing this field, cannot have its deliveries verified, so the only safe answer is no.
            log.error("SCM_WEBHOOK_SIGNATURE_UNVERIFIABLE: no webhook.signatureAlgorithm configured for this "
                    + "provider, rejecting delivery");
            return false;
        }
        if (algorithm == ProviderConfiguration.SignatureAlgorithm.NONE) {
            log.warn("SCM_WEBHOOK_SIGNATURE_SKIPPED: provider is explicitly configured with "
                    + "signatureAlgorithm=NONE");
            return true;
        }
        if (secret == null || secret.isBlank()) {
            log.error("SCM_WEBHOOK_SECRET_UNAVAILABLE: cannot verify signature, rejecting delivery");
            return false;
        }
        if (rawBody == null) {
            return false;
        }

        String headerName = webhookConfiguration.signatureHeader();
        String presented = headerName == null ? null : headers.get(headerName.toLowerCase(java.util.Locale.ROOT));
        if (presented == null || presented.isBlank()) {
            log.warn("SCM_WEBHOOK_SIGNATURE_MISSING: expected header={}", headerName);
            return false;
        }

        String prefix = webhookConfiguration.signaturePrefixOrEmpty();
        if (!prefix.isEmpty()) {
            if (!presented.regionMatches(true, 0, prefix, 0, prefix.length())) {
                log.warn("SCM_WEBHOOK_SIGNATURE_MALFORMED: expected prefix={}", prefix);
                return false;
            }
            presented = presented.substring(prefix.length());
        }

        String computed = computeHmacHex(algorithm, secret, rawBody);
        if (computed == null) {
            return false;
        }

        boolean valid = MessageDigest.isEqual(
                computed.getBytes(StandardCharsets.UTF_8),
                presented.trim().toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8));

        if (!valid) {
            // Logs that verification failed, never the expected or presented digest: publishing the
            // expected value would hand an attacker exactly what they need.
            log.warn("SCM_WEBHOOK_SIGNATURE_INVALID: algorithm={}, header={}", algorithm, headerName);
        }
        return valid;
    }

    private String computeHmacHex(ProviderConfiguration.SignatureAlgorithm algorithm, String secret, byte[] body) {
        String macAlgorithm = switch (algorithm) {
            case HMAC_SHA256 -> "HmacSHA256";
            case HMAC_SHA1 -> "HmacSHA1";
            case NONE -> null;
        };
        if (macAlgorithm == null) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance(macAlgorithm);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), macAlgorithm));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception ex) {
            log.error("SCM_WEBHOOK_SIGNATURE_COMPUTE_FAILED: algorithm={}, cause={}",
                    algorithm, ex.getClass().getSimpleName());
            return null;
        }
    }
}
