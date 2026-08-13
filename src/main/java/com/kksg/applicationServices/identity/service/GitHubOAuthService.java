package com.kksg.applicationServices.identity.service;

import com.kksg.applicationServices.common.exception.ApiException;
import com.kksg.applicationServices.identity.dto.response.AuthResponse;
import com.kksg.applicationServices.identity.entity.*;
import com.kksg.applicationServices.identity.repository.UserLoginRepository;
import com.kksg.applicationServices.identity.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class GitHubOAuthService {

    private static final Logger log = LoggerFactory.getLogger(GitHubOAuthService.class);

    private static final String GITHUB_AUTHORIZE_URL = "https://github.com/login/oauth/authorize";
    private static final String GITHUB_TOKEN_URL = "https://github.com/login/oauth/access_token";
    private static final String GITHUB_USER_URL = "https://api.github.com/user";
    private static final String GITHUB_USER_EMAILS_URL = "https://api.github.com/user/emails";

    @Value("${github.oauth.client-id}")
    private String clientId;

    @Value("${github.oauth.client-secret}")
    private String clientSecret;

    @Value("${github.oauth.redirect-uri}")
    private String redirectUri;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    private final UserRepository userRepository;
    private final UserLoginRepository userLoginRepository;
    private final AuthService authService;
    private final RestTemplate restTemplate;
    private final SecureRandom secureRandom = new SecureRandom();

    public GitHubOAuthService(UserRepository userRepository,
                              UserLoginRepository userLoginRepository,
                              AuthService authService) {
        this.userRepository = userRepository;
        this.userLoginRepository = userLoginRepository;
        this.authService = authService;
        this.restTemplate = new RestTemplate();
    }

    /**
     * Build the GitHub authorization URL and redirect the user to GitHub.
     */
    public String buildAuthorizationUrl() {
        validateConfiguration();

        String state = generateState();

        String authUrl = UriComponentsBuilder.fromHttpUrl(GITHUB_AUTHORIZE_URL)
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", redirectUri)
                .queryParam("scope", "user:email read:user")
                .queryParam("state", state)
                .build()
                .toUriString();

        log.info("GITHUB_AUTH_INITIATED: redirecting user to GitHub");
        return authUrl;
    }

    /**
     * Handle the GitHub OAuth callback: exchange code for tokens, find/create user,
     * and return the frontend redirect URL with tokens.
     */
    @Transactional
    public String handleCallback(String code, String error) {
        if (error != null && !error.isEmpty()) {
            log.warn("GITHUB_OAUTH_DENIED: user denied access, error={}", error);
            return buildOAuthErrorUrl("GitHub authorization was denied");
        }

        if (code == null || code.isEmpty()) {
            log.warn("GITHUB_OAUTH_FAILED: missing authorization code");
            return buildOAuthErrorUrl("Missing authorization code");
        }

        try {
            // Exchange code for access token
            String accessToken = exchangeCodeForToken(code);

            // Get user info from GitHub
            Map<String, Object> githubUser = getGitHubUser(accessToken);
            String providerUserId = String.valueOf(githubUser.get("id"));
            String name = (String) githubUser.get("name");
            String login = (String) githubUser.get("login");
            String avatarUrl = (String) githubUser.get("avatar_url");

            // Get primary email
            String email = getPrimaryEmail(accessToken);
            if (email == null || email.isEmpty()) {
                log.warn("GITHUB_OAUTH_FAILED: no verified email found");
                return buildOAuthErrorUrl("Could not retrieve a verified email from GitHub");
            }

            // Find or create user
            User user = findOrCreateUser(email, name != null ? name : login, avatarUrl);

            // Find or create UserLogin
            UserLogin userLogin = findOrCreateUserLogin(user, providerUserId, accessToken);

            // Update last login
            user.setLastLoginAt(Instant.now());
            userRepository.save(user);

            log.info("USER_LOGIN_SUCCESS: user_id={}, provider=GITHUB", user.getId());

            // Generate application tokens
            AuthResponse authResponse = authService.generateTokenPair(userLogin);

            // Build success redirect URL
            return buildOAuthSuccessUrl(authResponse);

        } catch (ApiException ex) {
            log.warn("GITHUB_OAUTH_FAILED: {}", ex.getMessage());
            return buildOAuthErrorUrl(ex.getMessage());
        } catch (Exception ex) {
            log.error("GITHUB_OAUTH_FAILED: unexpected error", ex);
            return buildOAuthErrorUrl("GitHub authentication failed due to an unexpected error");
        }
    }

    private void validateConfiguration() {
        if (clientId == null || clientId.trim().isEmpty()) {
            throw new ApiException("GitHub Client ID is not configured");
        }
        if (clientSecret == null || clientSecret.trim().isEmpty()) {
            throw new ApiException("GitHub Client Secret is not configured");
        }
    }

    private String generateState() {
        byte[] bytes = new byte[16];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String buildOAuthSuccessUrl(AuthResponse authResponse) {
        String tokenParam = URLEncoder.encode(authResponse.getAccessToken(), StandardCharsets.UTF_8);
        String refreshParam = URLEncoder.encode(authResponse.getRefreshToken(), StandardCharsets.UTF_8);

        return UriComponentsBuilder.fromHttpUrl(frontendUrl)
                .path("/oauth-success")
                .queryParam("access_token", tokenParam)
                .queryParam("refresh_token", refreshParam)
                .build()
                .toUriString();
    }

    private String buildOAuthErrorUrl(String message) {
        String encodedMessage = URLEncoder.encode(message, StandardCharsets.UTF_8);

        return UriComponentsBuilder.fromHttpUrl(frontendUrl)
                .path("/oauth-error")
                .queryParam("message", encodedMessage)
                .build()
                .toUriString();
    }

    @SuppressWarnings("null")
    private String exchangeCodeForToken(String code) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        Map<String, String> body = Map.of(
                "client_id", clientId,
                "client_secret", clientSecret,
                "code", code,
                "redirect_uri", redirectUri
        );

        HttpEntity<Map<String, String>> entity = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(GITHUB_TOKEN_URL, HttpMethod.POST, entity, Map.class);

            @SuppressWarnings("unchecked")
            Map<String, Object> responseBody = response.getBody();
            if (responseBody == null || responseBody.containsKey("error")) {
                String error = responseBody != null ? String.valueOf(responseBody.get("error_description")) : "empty response";
                log.warn("USER_LOGIN_FAILED: GitHub token exchange failed - {}", error);
                throw new ApiException("Failed to exchange GitHub code for access token");
            }

            return (String) responseBody.get("access_token");
        } catch (RestClientException ex) {
            log.error("USER_LOGIN_FAILED: GitHub token exchange request failed", ex);
            throw new ApiException("Unable to communicate with GitHub for token exchange", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getGitHubUser(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(GITHUB_USER_URL, HttpMethod.GET, entity, Map.class);
            Map<String, Object> body = response.getBody();
            if (body == null) {
                throw new ApiException("GitHub returned empty user profile");
            }
            return body;
        } catch (RestClientException ex) {
            log.error("Failed to fetch GitHub user profile", ex);
            throw new ApiException("Unable to retrieve user profile from GitHub", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private String getPrimaryEmail(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<List> response = restTemplate.exchange(GITHUB_USER_EMAILS_URL, HttpMethod.GET, entity, List.class);
            List<Map<String, Object>> emails = response.getBody();

            if (emails != null) {
                for (Map<String, Object> emailObj : emails) {
                    Boolean primary = (Boolean) emailObj.get("primary");
                    Boolean verified = (Boolean) emailObj.get("verified");
                    if (Boolean.TRUE.equals(primary) && Boolean.TRUE.equals(verified)) {
                        return (String) emailObj.get("email");
                    }
                }
                // Fallback to first verified email
                for (Map<String, Object> emailObj : emails) {
                    Boolean verified = (Boolean) emailObj.get("verified");
                    if (Boolean.TRUE.equals(verified)) {
                        return (String) emailObj.get("email");
                    }
                }
            }

            return null;
        } catch (RestClientException ex) {
            log.error("Failed to fetch GitHub user emails", ex);
            throw new ApiException("Unable to retrieve email addresses from GitHub", ex);
        }
    }

    private User findOrCreateUser(String email, String name, String avatarUrl) {
        Optional<User> existingUser = userRepository.findByEmail(email);
        if (existingUser.isPresent()) {
            return existingUser.get();
        }

        User newUser = new User();
        newUser.setEmail(email);
        newUser.setFullName(name);
        newUser.setProfilePicture(avatarUrl);
        newUser.setStatus(UserStatus.ACTIVE);
        newUser.setEmailVerified(true); // GitHub email is verified
        newUser.setLastLoginAt(Instant.now());

        User savedUser = userRepository.save(newUser);
        log.info("USER_REGISTERED: user_id={}, provider=GITHUB", savedUser.getId());
        return savedUser;
    }

    private UserLogin findOrCreateUserLogin(User user, String providerUserId, String accessToken) {
        Optional<UserLogin> existing = userLoginRepository.findByUserAndProvider(user, LoginProvider.GITHUB);

        if (existing.isPresent()) {
            UserLogin login = existing.get();
            login.setAccessToken(accessToken);
            login.setProviderUserId(providerUserId);
            login.setExpiresAt(Instant.now().plusSeconds(3600));
            return userLoginRepository.save(login);
        } else {
            UserLogin login = new UserLogin();
            login.setUser(user);
            login.setProvider(LoginProvider.GITHUB);
            login.setProviderUserId(providerUserId);
            login.setAccessToken(accessToken);
            login.setExpiresAt(Instant.now().plusSeconds(3600));
            return userLoginRepository.save(login);
        }
    }
}
