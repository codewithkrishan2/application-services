package com.kksg.applicationServices.scm.secret;

import com.kksg.applicationServices.scm.common.exception.ScmErrorCode;
import com.kksg.applicationServices.scm.common.exception.ScmException;
import com.kksg.applicationServices.scm.secret.entity.ScmSecret;
import com.kksg.applicationServices.scm.secret.repository.ScmSecretRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Default {@link ScmSecretStore}: AES-256-GCM envelope encryption with ciphertext held in
 * {@code scm_secrets}.
 *
 * <p><b>Why this exists rather than reusing {@code utils.EncryptionUtil}.</b> That helper uses a
 * hardcoded 16-character key compiled into the source, and {@code Cipher.getInstance("AES")} which
 * resolves to AES/ECB/PKCS5Padding. ECB is deterministic, so equal plaintexts produce equal
 * ciphertexts and it provides no integrity guarantee at all. Neither property is acceptable for
 * long-lived OAuth credentials, so this store uses an authenticated mode with a per-record random
 * IV and an externally supplied key. {@code EncryptionUtil} is left untouched because Module 1 code
 * depends on it; migrating it is out of scope for this module.
 *
 * <p><b>This is the MVP backend, not the recommended production one.</b> The key sits in
 * application configuration, so an attacker with both database access and configuration access can
 * still recover tokens. The point of {@link ScmSecretStore} is that swapping in a KMS/Vault-backed
 * implementation is a one-class change with no schema or domain impact - the {@code local:} prefix
 * on references lets both coexist during a migration.
 *
 * <p>To replace it, register an alternative {@link ScmSecretStore} bean annotated
 * {@code @Primary}; injection points depend on the interface, never on this class.
 */
@Component
public class EncryptedDatabaseSecretStore implements ScmSecretStore {

    private static final Logger log = LoggerFactory.getLogger(EncryptedDatabaseSecretStore.class);

    private static final String REFERENCE_PREFIX = "local:";
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final String KEY_ALGORITHM = "AES";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final int EXPECTED_KEY_LENGTH_BYTES = 32;

    /**
     * The key shipped in {@code application.yml} for local development. Matching it at runtime means
     * the deployment never set {@code SCM_SECRETS_ENCRYPTION_KEY}, which must be loud.
     */
    private static final String DEVELOPMENT_KEY = "c2NtLWRldi1vbmx5LWtleS0zMi1ieXRlcy1sb25nISE=";

    private final ScmSecretRepository secretRepository;
    private final ScmSecretProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    private SecretKey secretKey;

    public EncryptedDatabaseSecretStore(ScmSecretRepository secretRepository, ScmSecretProperties properties) {
        this.secretRepository = secretRepository;
        this.properties = properties;
    }

    /**
     * Validates the key at startup rather than on first use. A misconfigured key discovered during
     * an OAuth callback would strand a half-created connection; discovered at boot it is simply a
     * failed deployment.
     */
    @PostConstruct
    void initialiseKey() {
        String configured = properties.getEncryptionKey();
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(
                    "scm.secrets.encryption-key is not configured. Provide a Base64-encoded 256-bit key "
                            + "via the SCM_SECRETS_ENCRYPTION_KEY environment variable.");
        }

        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(configured.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("scm.secrets.encryption-key must be valid Base64", ex);
        }
        if (keyBytes.length != EXPECTED_KEY_LENGTH_BYTES) {
            throw new IllegalStateException(
                    "scm.secrets.encryption-key must decode to %d bytes (256-bit), got %d"
                            .formatted(EXPECTED_KEY_LENGTH_BYTES, keyBytes.length));
        }
        if (DEVELOPMENT_KEY.equals(configured.trim())) {
            log.warn("SCM_SECRET_STORE_INSECURE_KEY: using the built-in development encryption key. "
                    + "Set SCM_SECRETS_ENCRYPTION_KEY before handling real provider credentials.");
        }

        this.secretKey = new SecretKeySpec(keyBytes, KEY_ALGORITHM);
        log.info("SCM_SECRET_STORE_READY: algorithm={}, keyVersion={}", ALGORITHM, properties.getKeyVersion());
    }

    @Override
    @Transactional
    public String store(String logicalName, String secretValue) {
        if (secretValue == null || secretValue.isEmpty()) {
            throw new ScmException(ScmErrorCode.SCM_SECRET_STORAGE_FAILED, "empty credential");
        }
        String reference = REFERENCE_PREFIX + UUID.randomUUID();

        ScmSecret secret = new ScmSecret();
        secret.setReference(reference);
        secret.setLogicalName(logicalName);
        secret.setCipherText(encrypt(secretValue));
        secret.setAlgorithm(ALGORITHM);
        secret.setKeyVersion(properties.getKeyVersion());
        secretRepository.save(secret);

        log.debug("SCM_SECRET_STORED: reference={}, logicalName={}", reference, logicalName);
        return reference;
    }

    @Override
    @Transactional
    public String update(String reference, String logicalName, String secretValue) {
        if (reference == null || !reference.startsWith(REFERENCE_PREFIX)) {
            return store(logicalName, secretValue);
        }
        Optional<ScmSecret> existing = secretRepository.findByReference(reference);
        if (existing.isEmpty()) {
            return store(logicalName, secretValue);
        }

        ScmSecret secret = existing.get();
        secret.setCipherText(encrypt(secretValue));
        secret.setAlgorithm(ALGORITHM);
        secret.setKeyVersion(properties.getKeyVersion());
        secret.setLogicalName(logicalName);
        secretRepository.save(secret);

        log.debug("SCM_SECRET_ROTATED: reference={}, logicalName={}", reference, logicalName);
        return reference;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> retrieve(String reference) {
        if (reference == null || reference.isBlank()) {
            return Optional.empty();
        }
        return secretRepository.findByReference(reference).map(secret -> decrypt(secret.getCipherText(), reference));
    }

    @Override
    @Transactional
    public void delete(String reference) {
        if (reference == null || reference.isBlank()) {
            return;
        }
        secretRepository.findByReference(reference).ifPresent(secret -> {
            secretRepository.delete(secret);
            log.debug("SCM_SECRET_DELETED: reference={}", reference);
        });
    }

    private String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] cipherText = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] payload = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(cipherText, 0, payload, iv.length, cipherText.length);
            return Base64.getEncoder().encodeToString(payload);
        } catch (Exception ex) {
            // Deliberately does not include the exception message in the domain error, and never the plaintext.
            log.error("SCM_SECRET_ENCRYPT_FAILED: {}", ex.getClass().getSimpleName());
            throw new ScmException(ScmErrorCode.SCM_SECRET_STORAGE_FAILED);
        }
    }

    private String decrypt(String encoded, String reference) {
        try {
            byte[] payload = Base64.getDecoder().decode(encoded);
            if (payload.length <= IV_LENGTH_BYTES) {
                throw new IllegalStateException("ciphertext too short");
            }
            byte[] iv = new byte[IV_LENGTH_BYTES];
            System.arraycopy(payload, 0, iv, 0, IV_LENGTH_BYTES);
            byte[] cipherText = new byte[payload.length - IV_LENGTH_BYTES];
            System.arraycopy(payload, IV_LENGTH_BYTES, cipherText, 0, cipherText.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            log.error("SCM_SECRET_DECRYPT_FAILED: reference={}, cause={}", reference, ex.getClass().getSimpleName());
            throw new ScmException(ScmErrorCode.SCM_SECRET_NOT_FOUND, "credential could not be decrypted");
        }
    }
}
