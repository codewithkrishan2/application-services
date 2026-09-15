package com.kksg.applicationServices.scm.common.model;

/**
 * Normalized, provider-independent catalogue of SCM operations the platform can perform.
 *
 * <p>These codes are the vocabulary that higher modules (Repository Management, Review
 * Orchestration, Code Analysis, AI Review) speak. A caller asks for {@code LIST_REPOSITORIES};
 * it never asks for {@code GET /user/repos}. The translation from a code to a concrete HTTP
 * request lives entirely in {@code scm_provider_operations} rows keyed by
 * {@code (provider_id, operation_code)}.
 *
 * <p><b>Why an enum and not a free-form string?</b> The set of operations the platform
 * understands is a property of <i>our</i> domain, not of any provider. Adding a provider must
 * never add an operation code; adding a <i>platform feature</i> may. Keeping this closed gives
 * us compile-time safety in Modules 3-8 and lets seed data be validated on load.
 *
 * <p>Only the operations required by the current MVP are listed. The persistence model
 * ({@code operation_code VARCHAR}) tolerates unknown codes so that a future release can add
 * values here without a schema change.
 */
public enum ScmOperationCode {

    /**
     * Resolves the account that owns the authorization (login/uuid/username). Used during the
     * connect flow so that {@code external_account_id} is discovered declaratively rather than
     * by provider-specific code.
     */
    GET_CURRENT_ACCOUNT,

    LIST_REPOSITORIES,
    GET_REPOSITORY,

    LIST_PULL_REQUESTS,
    GET_PULL_REQUEST,
    GET_PULL_REQUEST_FILES,

    /** Returns unified diff text rather than a JSON document. */
    GET_PULL_REQUEST_DIFF,

    CREATE_WEBHOOK,
    DELETE_WEBHOOK,

    CREATE_PR_COMMENT,
    CREATE_PR_REVIEW;

    /**
     * The capability that must be declared supported before this operation may be dispatched.
     *
     * <p>Resolved by name because the two vocabularies intentionally overlap for every operation
     * that represents a user-visible feature. {@link #GET_CURRENT_ACCOUNT} has no counterpart: it is
     * an internal step of the connect flow, not a feature to advertise or gate, so it returns
     * {@code null} and the engine skips the capability check for it.
     */
    public ScmCapabilityCode requiredCapability() {
        return ScmCapabilityCode.fromCode(name());
    }

    /**
     * Lenient lookup used when reading persisted/seeded values, so that an unrecognised code
     * surfaces as a domain error instead of an {@link IllegalArgumentException}.
     */
    public static ScmOperationCode fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (ScmOperationCode value : values()) {
            if (value.name().equalsIgnoreCase(code.trim())) {
                return value;
            }
        }
        return null;
    }
}
