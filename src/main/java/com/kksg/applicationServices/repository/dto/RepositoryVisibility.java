package com.kksg.applicationServices.repository.dto;

/**
 * Whether a repository is readable by anyone or only by authorized accounts.
 *
 * <p>An enum rather than the normalized {@code Boolean isPrivate} it is derived from, because a
 * boolean has no way to say "the provider did not tell us". That third case is real: a provider may
 * omit the flag on a reduced payload, and rendering such a repository as public would be a
 * misstatement about access control - the one place in this response where guessing is unacceptable.
 */
public enum RepositoryVisibility {

    PUBLIC,
    PRIVATE,

    /** The provider did not report visibility. Clients should not imply either state. */
    UNKNOWN;

    public static RepositoryVisibility fromIsPrivate(Boolean isPrivate) {
        if (isPrivate == null) {
            return UNKNOWN;
        }
        return isPrivate ? PRIVATE : PUBLIC;
    }
}
