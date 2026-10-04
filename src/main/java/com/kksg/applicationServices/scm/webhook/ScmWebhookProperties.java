package com.kksg.applicationServices.scm.webhook;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Limits applied to inbound webhook deliveries.
 */
@Component
@ConfigurationProperties(prefix = "scm.webhook")
@Getter
@Setter
public class ScmWebhookProperties {

    /**
     * Largest delivery body accepted, in bytes.
     *
     * <p>1 MiB comfortably exceeds a real pull-request event - GitHub caps its own deliveries at 25 MB but
     * the events this application consumes are a few kilobytes - while bounding what an anonymous caller
     * can make the service allocate and store.
     */
    private int maxRequestBytes = 1024 * 1024;
}
