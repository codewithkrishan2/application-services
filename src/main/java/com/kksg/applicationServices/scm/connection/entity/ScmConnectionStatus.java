package com.kksg.applicationServices.scm.connection.entity;

/**
 * Lifecycle of a user's authorization to a provider.
 *
 * <p>The distinction between {@link #EXPIRED}, {@link #REVOKED} and {@link #DISCONNECTED} is not
 * cosmetic - each implies a different recovery path, and collapsing them would leave the UI unable
 * to tell the user what to do:
 * <ul>
 *   <li>{@code EXPIRED} - credentials aged out and refresh is possible or a silent re-auth will fix
 *       it. Recoverable, often without user action.</li>
 *   <li>{@code REVOKED} - the user or an administrator withdrew access at the provider. Requires a
 *       fresh consent; retrying the old credential is pointless.</li>
 *   <li>{@code DISCONNECTED} - the user removed the connection here. The row is retained so history
 *       that references it stays readable, but it must not be used.</li>
 * </ul>
 */
public enum ScmConnectionStatus {

    /** Usable for API calls. */
    ACTIVE,

    /** Access token expired; refresh or re-authorization needed. */
    EXPIRED,

    /** Authorization withdrawn at the provider. */
    REVOKED,

    /** Removed by the user from this platform. */
    DISCONNECTED,

    /** Repeated provider failures; held aside so it is not retried indefinitely. */
    ERROR
}
