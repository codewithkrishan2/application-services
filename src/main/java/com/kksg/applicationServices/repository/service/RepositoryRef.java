package com.kksg.applicationServices.repository.service;

import com.kksg.applicationServices.scm.common.exception.ScmErrorCode;
import com.kksg.applicationServices.scm.common.exception.ScmException;

/**
 * A validated owner-qualified repository address.
 *
 * <p><b>Why a repository is addressed by {@code owner} and {@code name} rather than by an id.</b> Every
 * configured provider operation that touches a repository substitutes {@code {{owner}}} and
 * {@code {{repo}}} into its endpoint template - that is what the provider APIs accept. Neither provider
 * exposes a "get repository by numeric id" route in its configuration, so an id-keyed route would have
 * had to resolve the id back to a name by walking the repository list, which is a paged provider call
 * per request and still wrong the moment a repository is renamed. The owner-qualified name is the
 * provider's real primary key for these calls, so it is the one this API uses.
 *
 * <p>Note also that a provider repository id is <b>not globally unique across providers</b>: one
 * provider's numeric ids and another's UUIDs share no namespace. Every reference is therefore resolved
 * in the context of a specific connection, never on the identifier alone.
 *
 * <p>Validation here is about keeping a path segment a path segment. The request builder already
 * percent-encodes values and rejects dot segments before they reach a URL, so this is the second of two
 * independent checks rather than the only one - but the first rejection should happen where the value
 * enters the application, with an error that names the caller's mistake.
 */
public record RepositoryRef(String owner, String name) {

    private static final int MAX_SEGMENT_LENGTH = 200;

    public RepositoryRef {
        owner = requireSegment(owner, "owner");
        name = requireSegment(name, "repo");
    }

    /**
     * Parses an owner-qualified name such as {@code acme/my-service}.
     *
     * <p>Splits on the <b>last</b> separator, not the first: an owner cannot contain a slash but a
     * provider could in principle nest a repository path, and taking the last separator keeps the owner
     * segment intact in both cases.
     */
    public static RepositoryRef parse(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            throw new ScmException(ScmErrorCode.SCM_REQUEST_INVALID, "repository full name is required");
        }
        String trimmed = fullName.trim();
        int separator = trimmed.lastIndexOf('/');
        if (separator <= 0 || separator == trimmed.length() - 1) {
            throw new ScmException(ScmErrorCode.SCM_REQUEST_INVALID,
                    "repository full name must be in 'owner/name' form");
        }
        return new RepositoryRef(trimmed.substring(0, separator), trimmed.substring(separator + 1));
    }

    /** @return {@code owner/name}, the form providers and clients both use. */
    public String fullName() {
        return owner + "/" + name;
    }

    private static String requireSegment(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new ScmException(ScmErrorCode.SCM_REQUEST_INVALID, "%s is required".formatted(label));
        }
        String trimmed = value.trim();
        if (trimmed.length() > MAX_SEGMENT_LENGTH) {
            throw new ScmException(ScmErrorCode.SCM_REQUEST_INVALID,
                    "%s is longer than %d characters".formatted(label, MAX_SEGMENT_LENGTH));
        }
        // A slash would split one path segment into two and move the request to a different endpoint;
        // a control character or line break would be an injection attempt against the outbound request.
        if (trimmed.indexOf('/') >= 0 || trimmed.indexOf('\\') >= 0) {
            throw new ScmException(ScmErrorCode.SCM_REQUEST_INVALID,
                    "%s must not contain a path separator".formatted(label));
        }
        for (int index = 0; index < trimmed.length(); index++) {
            if (Character.isISOControl(trimmed.charAt(index))) {
                throw new ScmException(ScmErrorCode.SCM_REQUEST_INVALID,
                        "%s contains an invalid character".formatted(label));
            }
        }
        return trimmed;
    }
}
