package com.kksg.applicationServices.scm.secret;

import java.util.Optional;

/**
 * Indirection between an SCM connection and the OAuth credentials it authorises with.
 *
 * <p>{@code scm_connections} stores only {@code access_token_reference} /
 * {@code refresh_token_reference} - opaque handles. The credential itself lives wherever this
 * interface's implementation puts it. That indirection buys three things:
 * <ul>
 *   <li>A row dump, an accidental {@code SELECT *} in a log, or a JPA {@code toString()} cannot
 *       leak a usable token.</li>
 *   <li>The backing store can be replaced (HashiCorp Vault, AWS Secrets Manager, KMS-wrapped
 *       column) without touching the connection entity or any calling code.</li>
 *   <li>Revoking a connection can destroy the credential outright rather than leaving ciphertext
 *       behind.</li>
 * </ul>
 *
 * <p><b>Reference format.</b> Implementations must return references prefixed with a store
 * identifier, e.g. {@code local:9f1c...}. Storing the prefix alongside the handle means a future
 * migration can route each reference to whichever backend created it, so two stores can coexist
 * while credentials are moved.
 *
 * <p><b>Implementation contract.</b> Implementations must never log, wrap into an exception
 * message, or otherwise emit the plaintext value. Failures are reported with
 * {@code SCM_SECRET_STORAGE_FAILED} / {@code SCM_SECRET_NOT_FOUND} and no payload detail.
 */
public interface ScmSecretStore {

    /**
     * Persists a new credential.
     *
     * @param logicalName non-sensitive label describing the credential's role, safe to log
     *                    (e.g. {@code scm.connection.access-token}). Used for operability only.
     * @param secretValue the plaintext credential.
     * @return the reference to persist on the owning entity.
     */
    String store(String logicalName, String secretValue);

    /**
     * Replaces the value behind an existing reference, creating one if {@code reference} is
     * {@code null} or unknown.
     *
     * <p>Rotation reuses the reference so that a token refresh does not have to update the owning
     * row's reference column, which keeps refresh free of write contention on the connection row.
     *
     * @return the reference to persist (may equal {@code reference}).
     */
    String update(String reference, String logicalName, String secretValue);

    /**
     * @return the plaintext credential, or empty when the reference is unknown. Empty rather than
     *         an exception, because a caller frequently needs to distinguish "never connected"
     *         from "connection broken" and treat the former as a normal state.
     */
    Optional<String> retrieve(String reference);

    /**
     * Destroys the credential. Must be idempotent - disconnecting twice is not an error.
     */
    void delete(String reference);
}
