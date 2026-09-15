package com.kksg.applicationServices.scm.webhook.verification;

import com.kksg.applicationServices.scm.provider.config.ProviderConfiguration;

import java.util.Map;

/**
 * Verifies that a webhook delivery genuinely came from the provider.
 *
 * <p>A webhook endpoint has to be reachable by an unauthenticated third party, which makes it the most
 * exposed surface in this module. Without verification, anyone who learns the URL could fabricate a
 * "pull request opened" event and cause the platform to fetch code, run analysis, and post comments as
 * the connected user.
 *
 * <p>An interface rather than a single class, because the scheme is provider-dependent and this is the
 * natural extension point for one that the configuration-driven HMAC implementation cannot express - for
 * example a signature covering canonicalised headers rather than just the body. A provider adapter's
 * {@code verifyWebhookSignature} hook can also override verification entirely.
 */
public interface WebhookSignatureVerifier {

    /**
     * @param rawBody the exact bytes received. Must not be a re-serialisation of a parsed payload: any
     *                whitespace or key-order difference changes the MAC and would break verification.
     * @param headers request headers, lower-cased keys.
     * @param secret  the shared secret, or {@code null} when none is configured.
     * @return whether the delivery is authentic.
     */
    boolean verify(byte[] rawBody,
                   Map<String, String> headers,
                   ProviderConfiguration.Webhook webhookConfiguration,
                   String secret);
}
