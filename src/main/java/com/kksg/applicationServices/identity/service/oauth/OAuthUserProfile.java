package com.kksg.applicationServices.identity.service.oauth;

/**
 * A provider account reduced to the fields this application needs to establish an identity.
 *
 * <p>Each provider exposes these under different names and shapes - GitHub's numeric {@code id} and
 * Bitbucket's braced {@code uuid}, GitHub's {@code verified} email flag and Bitbucket's
 * {@code is_confirmed}. Normalizing at the edge means the sign-in flow itself contains no
 * provider-specific branching.
 *
 * @param providerUserId the provider's stable identifier for the account, as a string
 * @param email          a confirmed email address; the key this application matches users on
 * @param emailVerified  whether the provider states the address is confirmed
 * @param fullName       a human-readable name, falling back to the username when no name is set
 * @param avatarUrl      profile image URL, or {@code null}
 */
public record OAuthUserProfile(String providerUserId,
                               String email,
                               boolean emailVerified,
                               String fullName,
                               String avatarUrl) {
}
