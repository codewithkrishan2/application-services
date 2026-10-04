package com.kksg.applicationServices.scm.common.http;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Transport settings for outbound calls to provider APIs.
 *
 * <p>Timeouts are configuration rather than constants because provider latency varies and a
 * self-hosted instance behind a VPN can be far slower than a cloud API. Both default to values low
 * enough that a hung provider cannot exhaust the request thread pool: without an explicit read
 * timeout, {@code RestTemplate} waits indefinitely.
 */
@Component
@ConfigurationProperties(prefix = "scm.http")
@Getter
@Setter
public class ScmHttpProperties {

    private int connectTimeoutMs = 5_000;

    private int readTimeoutMs = 20_000;

    /**
     * Maximum response size to buffer, in bytes. Bounded because a diff for a very large pull request
     * is attacker-influenced input in the sense that any contributor can create one, and an unbounded
     * read would be a memory-exhaustion vector.
     */
    private int maxResponseBytes = 10 * 1024 * 1024;

    /**
     * Extra attempts after the first for a <b>retryable</b> failure.
     *
     * <p>Two, so one logical call makes at most three requests. Deliberately small: these retries sit
     * inside a user's request, so each one adds directly to the latency they experience, and a provider
     * that is genuinely down is not helped by being asked again.
     *
     * <p>What counts as retryable is narrow by design - see {@code ScmHttpExecutor}. It is not a
     * general-purpose retry: a 4xx is a wrong request and will be wrong again, and a 429 is explicitly
     * excluded because retrying a rate limit is what turns one into an outage.
     *
     * <p>Set to 0 to disable retries entirely.
     */
    private int maxRetries = 2;

    /**
     * Delay before the first retry, in milliseconds. Doubled for each subsequent attempt.
     *
     * <p>With the default of two retries that is a 250 ms then a 500 ms pause - enough to let a
     * momentary gateway blip or a dropped connection clear, and bounded so the worst case adds under a
     * second rather than seconds.
     */
    private long retryBackoffMs = 250;
}
