package com.kksg.applicationServices.scm.connection.dto;

/**
 * Whether a connection can actually be used right now, and if not, what would fix it.
 *
 * <p><b>Why this is not just {@code ScmConnectionStatus}.</b> The status column records a lifecycle
 * fact; it does not answer "can I call the provider with this?" - and the gap between the two was
 * being papered over by every consumer independently. Two cases make them genuinely different:
 *
 * <ul>
 *   <li>An {@code EXPIRED} connection <b>whose provider issues refresh tokens is still usable</b>.
 *       The next call renews it transparently. Treating {@code EXPIRED} as broken - which the UI did,
 *       listing it under "needs attention" with a Reconnect button - asked the user to re-grant
 *       consent they had no need to re-grant.</li>
 *   <li>An {@code ACTIVE} connection whose token expires in thirty seconds is, for practical
 *       purposes, about to not be. Nothing in the status says so.</li>
 * </ul>
 *
 * <p>So readiness is <b>derived</b>, from the status, the token expiry and the provider's declared
 * refresh support. It is computed on read and never stored: it depends on the current time, so a
 * persisted copy would be wrong moments later.
 *
 * <p>Deliberately does not duplicate the status vocabulary. A client branching on readiness is asking
 * "what can I do?"; one branching on status is asking "what happened?". Both are reported.
 */
public enum ScmConnectionReadiness {

    /** Usable now. No action needed. */
    READY,

    /**
     * Usable, but the credential expires soon.
     *
     * <p>Informational rather than actionable where refresh is supported - the background sweep or the
     * next request will renew it. Worth surfacing only because it explains a connection that is about
     * to change state on its own.
     */
    EXPIRING,

    /**
     * The credential has expired but can be renewed without the user.
     *
     * <p>The case the previous model got wrong. A client should show this as transient, not as a
     * problem to act on, and must not offer Reconnect as though consent were required.
     */
    REFRESHABLE,

    /**
     * Fresh provider consent is required; nothing the platform can do will recover it.
     *
     * <p>Reached by a revoked authorization, by a refresh the provider rejected outright, or by an
     * expired credential on a provider that issues no refresh token. These differ in cause and are
     * distinguishable through {@code connectionStatus}, but the remedy is identical, so they share one
     * readiness value.
     */
    REAUTHORIZATION_REQUIRED,

    /** Removed by the user. Retained for history and must not be used. */
    DISCONNECTED,

    /** Held aside after repeated provider failures. Needs operator attention rather than the user's. */
    ERROR;

    /**
     * @return whether a provider call may be attempted. True for {@link #READY}, {@link #EXPIRING} and
     *         {@link #REFRESHABLE} - the last because renewal happens inside the call.
     */
    public boolean isUsable() {
        return this == READY || this == EXPIRING || this == REFRESHABLE;
    }

    /** @return whether the only remedy is the user granting consent again. */
    public boolean requiresUserAction() {
        return this == REAUTHORIZATION_REQUIRED;
    }
}
