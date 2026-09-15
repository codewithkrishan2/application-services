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
}
