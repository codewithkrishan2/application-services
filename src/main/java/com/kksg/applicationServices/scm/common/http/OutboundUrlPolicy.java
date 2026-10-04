package com.kksg.applicationServices.scm.common.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

/**
 * Decides whether this service is willing to send a provider request to a given base URL.
 *
 * <p><b>Why this is needed.</b> Provider base URLs are not constants in this design: they come from
 * {@code scm_providers.configuration.api.baseUrl} and can be overridden per connection by
 * {@code scm_connections.metadata.baseUrl}, which is what lets one {@code SELF_HOSTED} provider row serve
 * many tenants. That flexibility makes the destination of an outbound call data rather than code, and every
 * such call carries a user's OAuth bearer token in an {@code Authorization} header. A URL pointing at
 * {@code http://169.254.169.254/} or at an internal address therefore turns the service into a confused
 * deputy: it would reach inside the deployment's network perimeter on an attacker's behalf and hand the
 * credential to whatever answered.
 *
 * <p>No HTTP endpoint currently writes {@code metadata.baseUrl}, so today this guards against a tampered
 * seed document or database row. It is deliberately in place before the self-hosted-URL endpoint that would
 * make it reachable by users, because that is the change that silently turns a latent issue into a live one.
 *
 * <p><b>Known limitation.</b> A hostname is resolved here and may resolve differently when the request is
 * actually made (DNS rebinding). Closing that requires pinning the resolved address into the connection
 * itself, which this HTTP client cannot express; an egress allow-list at the network layer is the real
 * answer. This check stops the direct cases - literal internal addresses and obvious metadata endpoints -
 * which is what makes a copy-pasted SSRF payload fail.
 */
public final class OutboundUrlPolicy {

    private static final Logger log = LoggerFactory.getLogger(OutboundUrlPolicy.class);

    /**
     * Cloud instance-metadata addresses, blocked by name as well as by range.
     *
     * <p>They are link-local and so already covered, but naming them documents the attack this exists to
     * stop and keeps the check honest if the range test is ever loosened.
     */
    private static final Set<String> BLOCKED_HOSTS = Set.of(
            "169.254.169.254",          // AWS / Azure / GCP IMDS
            "metadata.google.internal",
            "metadata.goog"
    );

    private OutboundUrlPolicy() {
    }

    /**
     * @param baseUrl the candidate provider base URL
     * @param context what the URL came from, for the failure message
     * @return the trimmed, accepted URL
     * @throws IllegalArgumentException when the URL is malformed or points somewhere this service must not
     *         be made to reach. The caller is expected to translate this into its own error type.
     */
    public static String requireAllowed(String baseUrl, String context) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException(context + " is required");
        }

        String trimmed = baseUrl.trim();
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException(context + " is not a valid URL");
        }

        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme) && !"http".equals(scheme)) {
            throw new IllegalArgumentException(context + " must be an absolute http(s) URL");
        }

        // Credentials in a URL would be sent to the host on every call and would show up in any log that
        // records the URI. There is no legitimate use for them here.
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException(context + " must not contain embedded credentials");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException(context + " must include a host");
        }

        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if (BLOCKED_HOSTS.contains(normalizedHost)) {
            throw new IllegalArgumentException(context + " resolves to a blocked address");
        }

        requireRoutableHost(normalizedHost, context);
        return trimmed;
    }

    /**
     * Rejects hosts that resolve into the deployment's own network.
     *
     * <p>An unresolvable host is allowed through: DNS may legitimately be unavailable at validation time or
     * resolve only inside the deployment, and failing here would make configuration validation depend on
     * name resolution. The request itself will fail if the host genuinely does not exist, and that failure
     * is harmless.
     */
    private static void requireRoutableHost(String host, String context) {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException ex) {
            log.debug("SCM_OUTBOUND_HOST_UNRESOLVED: host={}, allowing on the assumption of private DNS", host);
            return;
        }

        for (InetAddress address : addresses) {
            if (address.isLoopbackAddress()
                    || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress()
                    || address.isAnyLocalAddress()
                    || address.isMulticastAddress()) {
                log.warn("SCM_OUTBOUND_URL_BLOCKED: context={}, host={}, reason=internal_address", context, host);
                throw new IllegalArgumentException(
                        context + " must not point at a loopback, link-local or private address");
            }
        }
    }
}
