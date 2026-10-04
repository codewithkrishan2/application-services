package com.kksg.applicationServices.scm.connection.service;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Settings for proactive token refresh.
 *
 * <p>Separate from the on-demand path, which needs no configuration: that one runs when a request
 * finds an expiring credential and has no choice about timing. These values describe the background
 * sweep, which does.
 */
@Component
@ConfigurationProperties(prefix = "scm.token-refresh")
@Getter
@Setter
public class ScmTokenRefreshProperties {

    /**
     * Whether the background sweep runs.
     *
     * <p>On by default, because the alternative is the behaviour this replaced: a connection's token
     * expires, and the next user request pays the refresh latency or - if the refresh token has gone
     * stale in the meantime - fails. Can be switched off where something external owns renewal, or in
     * a test that wants deterministic timing.
     *
     * <p>Note that disabling it does <b>not</b> disable refresh: the on-demand path in
     * {@code ScmTokenService} still renews a credential it finds expiring. This only decides whether
     * renewal is also attempted ahead of time.
     */
    private boolean enabled = true;

    /**
     * How often the sweep runs.
     *
     * <p>Five minutes is short relative to the shortest provider token lifetime observed (two hours)
     * and long enough that the query is inconsequential. It must be comfortably shorter than
     * {@link #leadTime} or a token could expire inside one interval and be found only after the fact.
     */
    private Duration interval = Duration.ofMinutes(5);

    /**
     * How far ahead of expiry a token is renewed.
     *
     * <p>Fifteen minutes gives several sweeps' worth of opportunities to recover from a transient
     * token-endpoint failure before any user request is affected, which is the entire value of
     * refreshing proactively. Too large and tokens are replaced needlessly - each refresh is a
     * provider call, and some providers rotate the refresh token on every use.
     */
    private Duration leadTime = Duration.ofMinutes(15);

    /**
     * Most connections renewed per sweep.
     *
     * <p>A bound, not a target. Each refresh is an outbound provider call, so an unbounded sweep on a
     * deployment where many tokens expire together would compete with live traffic for the same rate
     * limit. A backlog is simply drained by the next tick, in expiry order.
     */
    private int batchSize = 50;
}
