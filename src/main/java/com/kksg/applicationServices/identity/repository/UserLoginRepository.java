package com.kksg.applicationServices.identity.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.kksg.applicationServices.identity.entity.LoginProvider;
import com.kksg.applicationServices.identity.entity.User;
import com.kksg.applicationServices.identity.entity.UserLogin;

public interface UserLoginRepository extends JpaRepository<UserLogin, Integer> {

    Optional<UserLogin> findByUserAndProvider(User user, LoginProvider provider);

    Optional<UserLogin> findByProviderAndProviderUserId(LoginProvider provider, String providerUserId);

    /** Looks up the holder of a refresh token by its digest; the token itself is never stored. */
    Optional<UserLogin> findByAppRefreshTokenHash(String appRefreshTokenHash);

    /**
     * Looks up a login by the digest of the token it most recently rotated away from.
     *
     * <p>A match means a superseded token was presented, which is the signature of a stolen credential
     * being replayed - the legitimate client has already moved on to the new one.
     */
    Optional<UserLogin> findByAppRefreshTokenPreviousHash(String appRefreshTokenPreviousHash);

    @Modifying
    @Query("UPDATE UserLogin ul SET ul.appRefreshTokenRevoked = true, ul.appRefreshTokenRevokedAt = CURRENT_TIMESTAMP WHERE ul.user = :user AND ul.appRefreshTokenRevoked = false")
    void revokeAllRefreshTokensByUser(@Param("user") User user);
}
