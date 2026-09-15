package com.kksg.applicationServices.scm.webhook.verification;

import com.kksg.applicationServices.scm.provider.config.ProviderConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Webhook signature verification - the only thing protecting an endpoint that any third party can reach.
 *
 * <p>A forged delivery would make the platform fetch code, run analysis and post comments as the connected
 * user, so the fail-closed cases below matter as much as the happy path.
 */
class HmacWebhookSignatureVerifierTest {

    private final HmacWebhookSignatureVerifier verifier = new HmacWebhookSignatureVerifier();

    private static final String SECRET = "top-secret-webhook-key";
    private static final byte[] BODY =
            "{\"action\":\"opened\",\"number\":42}".getBytes(StandardCharsets.UTF_8);

    private ProviderConfiguration.Webhook githubStyle() {
        return new ProviderConfiguration.Webhook(
                ProviderConfiguration.SignatureAlgorithm.HMAC_SHA256,
                "x-hub-signature-256", "sha256=", "x-github-event", "x-github-delivery",
                "action", "scm.providers.github.webhook-secret");
    }

    private ProviderConfiguration.Webhook sha1Style() {
        return new ProviderConfiguration.Webhook(
                ProviderConfiguration.SignatureAlgorithm.HMAC_SHA1,
                "x-hub-signature", "sha1=", "x-github-event", "x-github-delivery",
                null, "secret.property");
    }

    private String hmacHex(String algorithm, String secret, byte[] body) throws Exception {
        Mac mac = Mac.getInstance(algorithm);
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), algorithm));
        return HexFormat.of().formatHex(mac.doFinal(body));
    }

    @Test
    @DisplayName("accepts a correctly signed payload")
    void acceptsValidSha256Signature() throws Exception {
        String signature = "sha256=" + hmacHex("HmacSHA256", SECRET, BODY);

        assertThat(verifier.verify(BODY, Map.of("x-hub-signature-256", signature), githubStyle(), SECRET))
                .isTrue();
    }

    @Test
    @DisplayName("accepts an upper-case hex digest")
    void acceptsUpperCaseHexDigest() throws Exception {
        String signature = "sha256=" + hmacHex("HmacSHA256", SECRET, BODY).toUpperCase();

        assertThat(verifier.verify(BODY, Map.of("x-hub-signature-256", signature), githubStyle(), SECRET))
                .isTrue();
    }

    @Test
    @DisplayName("accepts HMAC-SHA1 when the provider is configured for it")
    void acceptsValidSha1Signature() throws Exception {
        String signature = "sha1=" + hmacHex("HmacSHA1", SECRET, BODY);

        assertThat(verifier.verify(BODY, Map.of("x-hub-signature", signature), sha1Style(), SECRET))
                .isTrue();
    }

    @Test
    @DisplayName("rejects a payload modified after signing")
    void rejectsTamperedBody() throws Exception {
        String signature = "sha256=" + hmacHex("HmacSHA256", SECRET, BODY);
        byte[] tampered = "{\"action\":\"opened\",\"number\":99}".getBytes(StandardCharsets.UTF_8);

        assertThat(verifier.verify(tampered, Map.of("x-hub-signature-256", signature), githubStyle(), SECRET))
                .isFalse();
    }

    @Test
    @DisplayName("rejects a signature produced with a different secret")
    void rejectsWrongSecret() throws Exception {
        String signature = "sha256=" + hmacHex("HmacSHA256", "attacker-guess", BODY);

        assertThat(verifier.verify(BODY, Map.of("x-hub-signature-256", signature), githubStyle(), SECRET))
                .isFalse();
    }

    @Test
    @DisplayName("rejects when the signature header is absent")
    void rejectsMissingHeader() {
        assertThat(verifier.verify(BODY, Map.of(), githubStyle(), SECRET)).isFalse();
    }

    @Test
    @DisplayName("rejects when the expected algorithm prefix is missing")
    void rejectsMissingPrefix() throws Exception {
        String withoutPrefix = hmacHex("HmacSHA256", SECRET, BODY);

        assertThat(verifier.verify(BODY, Map.of("x-hub-signature-256", withoutPrefix), githubStyle(), SECRET))
                .isFalse();
    }

    @Test
    @DisplayName("fails closed when no secret is configured")
    void rejectsWhenSecretUnavailable() throws Exception {
        // Accepting an unverifiable delivery would make the whole mechanism advisory.
        String signature = "sha256=" + hmacHex("HmacSHA256", SECRET, BODY);

        assertThat(verifier.verify(BODY, Map.of("x-hub-signature-256", signature), githubStyle(), null))
                .isFalse();
        assertThat(verifier.verify(BODY, Map.of("x-hub-signature-256", signature), githubStyle(), "  "))
                .isFalse();
    }

    @Test
    @DisplayName("accepts unconditionally only when signatureAlgorithm is explicitly NONE")
    void allowsExplicitlyUnsignedProvider() {
        ProviderConfiguration.Webhook unsigned = new ProviderConfiguration.Webhook(
                ProviderConfiguration.SignatureAlgorithm.NONE,
                null, null, "x-event-key", "x-request-uuid", null, null);

        assertThat(verifier.verify(BODY, Map.of(), unsigned, null)).isTrue();
    }

    @Test
    @DisplayName("a webhook config with no algorithm defaults to NONE")
    void defaultsToNoneWhenAlgorithmAbsent() {
        ProviderConfiguration.Webhook noAlgorithm = new ProviderConfiguration.Webhook(
                null, null, null, "x-event-key", "x-request-uuid", null, null);

        assertThat(noAlgorithm.signatureAlgorithmOrDefault())
                .isEqualTo(ProviderConfiguration.SignatureAlgorithm.NONE);
    }

    @Test
    @DisplayName("rejects a null body")
    void rejectsNullBody() throws Exception {
        String signature = "sha256=" + hmacHex("HmacSHA256", SECRET, BODY);

        assertThat(verifier.verify(null, Map.of("x-hub-signature-256", signature), githubStyle(), SECRET))
                .isFalse();
    }
}
