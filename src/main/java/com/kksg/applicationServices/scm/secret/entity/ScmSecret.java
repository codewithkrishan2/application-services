package com.kksg.applicationServices.scm.secret.entity;

import com.kksg.applicationServices.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Envelope-encrypted credential row used by {@code EncryptedDatabaseSecretStore}.
 *
 * <p>This table is an implementation detail of one {@code ScmSecretStore} implementation, not part
 * of the SCM domain model. Nothing outside the {@code scm.secret} package should query it; domain
 * code holds references and asks the store.
 *
 * <p>No Lombok {@code @ToString}/{@code @Data} here, and no {@code @AllArgsConstructor} exposing
 * the ciphertext positionally - the class deliberately offers no convenient way to print itself,
 * because the most common credential leak is an entity landing in a debug log.
 *
 * <p>{@code keyVersion} and {@code algorithm} are stored per row so that a key rotation can decrypt
 * old rows with the previous key while writing new rows with the new one.
 */
@Entity
@Table(name = "scm_secrets", indexes = {
        @Index(name = "ux_scm_secrets_reference", columnList = "reference", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
public class ScmSecret extends BaseEntity {

    /** Opaque handle held by the owning entity; the public identity of this credential. */
    @Column(name = "reference", nullable = false, unique = true, length = 100)
    private String reference;

    /** Non-sensitive role label, safe to log. */
    @Column(name = "logical_name", nullable = false, length = 150)
    private String logicalName;

    /** Base64 of {@code iv || ciphertext || authTag}. */
    @Column(name = "cipher_text", nullable = false, columnDefinition = "TEXT")
    private String cipherText;

    @Column(name = "algorithm", nullable = false, length = 50)
    private String algorithm;

    @Column(name = "key_version", nullable = false)
    private int keyVersion;

    @Override
    public String toString() {
        // Never render cipherText or anything derived from the credential.
        return "ScmSecret{reference=%s, logicalName=%s}".formatted(reference, logicalName);
    }
}
