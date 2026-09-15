package com.kksg.applicationServices.scm.connection.service;

import com.kksg.applicationServices.identity.entity.User;
import com.kksg.applicationServices.identity.repository.UserRepository;
import com.kksg.applicationServices.scm.common.exception.ScmErrorCode;
import com.kksg.applicationServices.scm.common.exception.ScmException;
import com.kksg.applicationServices.scm.common.model.NormalizedAccount;
import com.kksg.applicationServices.scm.common.model.ScmOperationCode;
import com.kksg.applicationServices.scm.common.model.ScmOperationRequest;
import com.kksg.applicationServices.scm.common.model.ScmTokenSet;
import com.kksg.applicationServices.scm.connection.entity.ScmConnection;
import com.kksg.applicationServices.scm.connection.entity.ScmConnectionStatus;
import com.kksg.applicationServices.scm.connection.repository.ScmConnectionRepository;
import com.kksg.applicationServices.scm.operation.engine.ScmClient;
import com.kksg.applicationServices.scm.provider.config.ProviderConfiguration;
import com.kksg.applicationServices.scm.provider.config.ProviderCredentialResolver;
import com.kksg.applicationServices.scm.provider.entity.ScmProvider;
import com.kksg.applicationServices.scm.provider.service.ScmProviderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Drives the OAuth authorization-code flow that establishes an {@code scm_connections} row.
 *
 * <p><b>Provider-agnostic by construction.</b> Nothing here knows GitHub from Bitbucket. The
 * authorization URL, scopes, token-request packaging and the call that identifies the authorized
 * account all come from provider configuration and from the {@code GET_CURRENT_ACCOUNT} operation row.
 * Adding a provider adds no code to this class.
 *
 * <p>Separated from {@code ScmConnectionService} because establishing a connection and managing
 * existing connections are different concerns with different collaborators: this one talks to the
 * provider, that one only to the database.
 */
@Service
public class ScmConnectionAuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(ScmConnectionAuthorizationService.class);

    private static final String METADATA_AVATAR_URL = "avatarUrl";
    private static final String METADATA_DISPLAY_NAME = "displayName";
    private static final String METADATA_DEFAULT_WORKSPACE = "defaultWorkspace";
    private static final String METADATA_GRANTED_SCOPES = "grantedScopes";

    private final ScmProviderService providerService;
    private final ScmConnectionRepository connectionRepository;
    private final ScmOAuthTokenExchanger tokenExchanger;
    private final ScmOAuthStateService stateService;
    private final ScmTokenService tokenService;
    private final ProviderCredentialResolver credentialResolver;
    private final ScmClient scmClient;
    private final UserRepository userRepository;

    public ScmConnectionAuthorizationService(ScmProviderService providerService,
                                             ScmConnectionRepository connectionRepository,
                                             ScmOAuthTokenExchanger tokenExchanger,
                                             ScmOAuthStateService stateService,
                                             ScmTokenService tokenService,
                                             ProviderCredentialResolver credentialResolver,
                                             ScmClient scmClient,
                                             UserRepository userRepository) {
        this.providerService = providerService;
        this.connectionRepository = connectionRepository;
        this.tokenExchanger = tokenExchanger;
        this.stateService = stateService;
        this.tokenService = tokenService;
        this.credentialResolver = credentialResolver;
        this.scmClient = scmClient;
        this.userRepository = userRepository;
    }

    /**
     * Builds the provider consent URL the user's browser must visit.
     *
     * @return the URL and the signed state embedded in it, so a client that prefers to keep the state
     *         itself can correlate the callback.
     */
    @Transactional(readOnly = true)
    public AuthorizationRedirect buildAuthorizationUrl(User user, String providerCode) {
        ScmProvider provider = providerService.requireActiveByCode(providerCode);
        ProviderConfiguration.OAuth oauth = providerService.getConfiguration(provider).oauth();
        if (oauth == null) {
            throw new ScmException(ScmErrorCode.SCM_PROVIDER_CONFIGURATION_INVALID,
                    "providerCode=%s does not support OAuth".formatted(provider.getProviderCode()));
        }

        String state = stateService.issue(user.getId(), provider.getId());
        String scopes = String.join(oauth.scopeSeparatorOrDefault(), oauth.scopesOrEmpty());

        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(oauth.authorizationUrl())
                .queryParam("client_id", credentialResolver.resolveClientId(provider))
                .queryParam("redirect_uri", credentialResolver.resolveRedirectUri(provider))
                .queryParam("response_type", "code")
                .queryParam("state", state);
        if (!scopes.isBlank()) {
            builder.queryParam("scope", scopes);
        }

        log.info("SCM_CONNECT_INITIATED: userId={}, providerCode={}", user.getId(), provider.getProviderCode());
        return new AuthorizationRedirect(
                builder.encode().build().toUriString(), provider.getProviderCode(), state);
    }

    /**
     * Completes the flow from the provider's redirect, deriving the user from the signed state.
     *
     * <p>The state is the only trustworthy source of user identity here, because the callback carries
     * no application credential - see {@code ScmOAuthStateService}.
     */
    @Transactional
    public ScmConnection completeFromCallback(String code, String state) {
        ScmOAuthStateService.VerifiedState verified = stateService.verify(state);
        ScmProvider provider = providerService.requireById(verified.providerId());
        providerService.requireActive(provider);

        User user = userRepository.findById(verified.userId())
                .orElseThrow(() -> new ScmException(ScmErrorCode.SCM_OAUTH_STATE_INVALID,
                        "state references an unknown user"));

        return complete(user, provider, code);
    }

    /**
     * Completes the flow for an already-authenticated caller that captured the code itself.
     *
     * <p>No state check: the user's identity comes from the verified bearer token, which is strictly
     * stronger evidence than a state parameter.
     */
    @Transactional
    public ScmConnection completeForUser(User user, String providerCode, String code) {
        ScmProvider provider = providerService.requireActiveByCode(providerCode);
        return complete(user, provider, code);
    }

    private ScmConnection complete(User user, ScmProvider provider, String code) {
        if (code == null || code.isBlank()) {
            throw new ScmException(ScmErrorCode.SCM_OAUTH_EXCHANGE_FAILED, "authorization code is missing");
        }

        ScmTokenSet tokens = tokenExchanger.exchangeAuthorizationCode(provider, code);
        NormalizedAccount account = fetchAccount(provider, tokens.getAccessToken());

        if (account.getExternalId() == null || account.getExternalId().isBlank()) {
            log.warn("SCM_CONNECT_ACCOUNT_UNIDENTIFIED: providerCode={}", provider.getProviderCode());
            throw new ScmException(ScmErrorCode.SCM_OAUTH_EXCHANGE_FAILED,
                    "providerCode=%s did not identify the authorized account"
                            .formatted(provider.getProviderCode()));
        }

        ScmConnection connection = upsert(user, provider, account, tokens);
        log.info("SCM_CONNECTED: userId={}, providerCode={}, connectionId={}, externalAccountId={}",
                user.getId(), provider.getProviderCode(), connection.getId(), connection.getExternalAccountId());
        return connection;
    }

    /**
     * Identifies the authorized account through the configured {@code GET_CURRENT_ACCOUNT} operation.
     *
     * <p>Uses {@code executeWithToken} because no connection row exists yet - the account id this call
     * returns is part of that row's unique key.
     */
    private NormalizedAccount fetchAccount(ScmProvider provider, String accessToken) {
        return scmClient.executeWithToken(provider, accessToken,
                        ScmOperationRequest.of(ScmOperationCode.GET_CURRENT_ACCOUNT))
                .as(NormalizedAccount.class);
    }

    /**
     * Creates the connection, or updates it when this account is already connected.
     *
     * <p>Re-running the flow must renew credentials rather than accumulate rows, which is why this is
     * an upsert keyed the same way as the database's unique constraint.
     *
     * <p>The {@link DataIntegrityViolationException} branch handles two callbacks arriving
     * concurrently: both pass the existence check, one insert wins, and the loser re-reads and updates
     * instead of failing the user's connect attempt. The application-level check alone cannot prevent
     * this, which is why the constraint exists in the database.
     */
    private ScmConnection upsert(User user, ScmProvider provider, NormalizedAccount account, ScmTokenSet tokens) {
        ScmConnection connection = connectionRepository
                .findByUserIdAndProviderIdAndExternalAccountId(user.getId(), provider.getId(), account.getExternalId())
                .orElseGet(ScmConnection::new);

        applyAccount(connection, user, provider, account, tokens);

        try {
            return connectionRepository.saveAndFlush(connection);
        } catch (DataIntegrityViolationException ex) {
            log.info("SCM_CONNECT_RACE_RESOLVED: userId={}, providerCode={}, externalAccountId={}",
                    user.getId(), provider.getProviderCode(), account.getExternalId());
            ScmConnection existing = connectionRepository
                    .findByUserIdAndProviderIdAndExternalAccountId(
                            user.getId(), provider.getId(), account.getExternalId())
                    .orElseThrow(() -> new ScmException(ScmErrorCode.SCM_CONNECTION_ALREADY_EXISTS,
                            "providerCode=%s".formatted(provider.getProviderCode())));
            applyAccount(existing, user, provider, account, tokens);
            return connectionRepository.saveAndFlush(existing);
        }
    }

    private void applyAccount(ScmConnection connection, User user, ScmProvider provider,
                              NormalizedAccount account, ScmTokenSet tokens) {
        connection.setUser(user);
        connection.setProvider(provider);
        connection.setExternalAccountId(account.getExternalId());
        connection.setExternalAccountName(account.getUsername() != null
                ? account.getUsername() : account.getDisplayName());
        connection.setConnectionStatus(ScmConnectionStatus.ACTIVE);
        connection.setConnectedAt(Instant.now());
        connection.setLastUsedAt(Instant.now());
        connection.setMetadata(buildMetadata(connection.getMetadata(), account, tokens));

        // Writes ciphertext through the secret store and records only the references on the row.
        tokenService.storeTokens(connection, tokens);
    }

    /**
     * Non-secret provider details worth keeping.
     *
     * <p>Any existing {@code baseUrl} override is preserved: it identifies a self-hosted instance and
     * is not something the account response would restate, so reconnecting must not discard it.
     */
    private Map<String, Object> buildMetadata(Map<String, Object> existing, NormalizedAccount account,
                                              ScmTokenSet tokens) {
        Map<String, Object> metadata = existing != null ? new LinkedHashMap<>(existing) : new LinkedHashMap<>();
        putIfPresent(metadata, METADATA_AVATAR_URL, account.getAvatarUrl());
        putIfPresent(metadata, METADATA_DISPLAY_NAME, account.getDisplayName());
        putIfPresent(metadata, METADATA_DEFAULT_WORKSPACE, account.getDefaultWorkspace());
        // Granted scopes may be narrower than requested; recording them lets a later feature explain
        // a permission failure without another provider round trip. This is not a credential.
        putIfPresent(metadata, METADATA_GRANTED_SCOPES, tokens.getScope());
        return metadata;
    }

    private void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    /**
     * @param authorizationUrl provider consent URL to send the browser to.
     * @param providerCode     provider being connected.
     * @param state            signed state embedded in the URL.
     */
    public record AuthorizationRedirect(String authorizationUrl, String providerCode, String state) {
    }
}
