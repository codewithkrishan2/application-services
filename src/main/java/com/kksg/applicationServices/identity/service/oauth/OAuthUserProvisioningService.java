package com.kksg.applicationServices.identity.service.oauth;

import com.kksg.applicationServices.identity.dto.response.AuthResponse;
import com.kksg.applicationServices.identity.entity.LoginProvider;
import com.kksg.applicationServices.identity.entity.User;
import com.kksg.applicationServices.identity.entity.UserLogin;
import com.kksg.applicationServices.identity.entity.UserStatus;
import com.kksg.applicationServices.identity.repository.UserLoginRepository;
import com.kksg.applicationServices.identity.repository.UserRepository;
import com.kksg.applicationServices.identity.service.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Turns a verified provider account into a local user, a provider login row, and an application token pair.
 *
 * <p>This is the half of sign-in that is identical for every provider, so it is written once and shared.
 * It is a separate bean from {@link OAuthLoginService} for a specific reason: the transaction must cover
 * only the database work, not the several seconds of outbound HTTP calls that precede it. Keeping them in
 * one class would either hold a connection open across those calls or - because {@code @Transactional} is
 * proxy-based and self-invocation bypasses the proxy - silently not apply at all.
 *
 * <p><b>Identity model.</b> Users are keyed on email, so signing in with Bitbucket using the same address
 * as an existing GitHub account attaches a second {@code UserLogin} to that same user rather than creating
 * a duplicate. That is the intended linking behaviour, and it is why callers must only reach this point
 * with an address the provider states is confirmed: accepting an unconfirmed address would let someone
 * claim an existing account by registering that address at another provider.
 */
@Service
public class OAuthUserProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(OAuthUserProvisioningService.class);

    private final UserRepository userRepository;
    private final UserLoginRepository userLoginRepository;
    private final AuthService authService;

    public OAuthUserProvisioningService(UserRepository userRepository,
                                        UserLoginRepository userLoginRepository,
                                        AuthService authService) {
        this.userRepository = userRepository;
        this.userLoginRepository = userLoginRepository;
        this.authService = authService;
    }

    @Transactional
    public AuthResponse provision(LoginProvider provider, OAuthUserProfile profile, OAuthTokenSet tokens) {
        User user = findOrCreateUser(provider, profile);
        UserLogin userLogin = upsertUserLogin(provider, user, profile, tokens);

        user.setLastLoginAt(Instant.now());
        userRepository.save(user);

        log.info("USER_LOGIN_SUCCESS: user_id={}, provider={}", user.getId(), provider);
        return authService.generateTokenPair(userLogin);
    }

    private User findOrCreateUser(LoginProvider provider, OAuthUserProfile profile) {
        Optional<User> existing = userRepository.findByEmail(profile.email());
        if (existing.isPresent()) {
            User user = existing.get();
            // An existing user's name and picture are left alone: they may have been edited in-app, and a
            // second provider's copy is not more authoritative than the user's own choice.
            if (user.getProfilePicture() == null && profile.avatarUrl() != null) {
                user.setProfilePicture(profile.avatarUrl());
            }
            return user;
        }

        User user = new User();
        user.setEmail(profile.email());
        user.setFullName(profile.fullName());
        user.setProfilePicture(profile.avatarUrl());
        user.setStatus(UserStatus.ACTIVE);
        user.setEmailVerified(profile.emailVerified());
        user.setLastLoginAt(Instant.now());

        User saved = userRepository.save(user);
        log.info("USER_REGISTERED: user_id={}, provider={}", saved.getId(), provider);
        return saved;
    }

    /**
     * One row per (user, provider), matching the table's unique constraint.
     *
     * <p><b>The provider's access and refresh tokens are deliberately not persisted.</b> Sign-in needs them
     * only for the few seconds it takes to read the account and its email, and nothing in the application
     * ever read them back: calls to a provider's API are made by the SCM module, which holds its own copies
     * encrypted at rest. Keeping a second, unencrypted copy of a repository-scoped credential here bought
     * nothing and was the largest piece of sensitive data in this table.
     *
     * <p>Only the grant's expiry is kept, as a record of when the user last consented.
     */
    private UserLogin upsertUserLogin(LoginProvider provider,
                                      User user,
                                      OAuthUserProfile profile,
                                      OAuthTokenSet tokens) {
        UserLogin userLogin = userLoginRepository.findByUserAndProvider(user, provider)
                .orElseGet(() -> {
                    UserLogin created = new UserLogin();
                    created.setUser(user);
                    created.setProvider(provider);
                    return created;
                });

        userLogin.setProviderUserId(profile.providerUserId());
        userLogin.setExpiresAt(tokens.expiresAt());

        return userLoginRepository.save(userLogin);
    }
}
