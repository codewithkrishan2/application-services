package com.kksg.applicationServices.identity.service.oauth;

import com.kksg.applicationServices.common.exception.ApiException;
import com.kksg.applicationServices.identity.dto.response.AuthResponse;
import com.kksg.applicationServices.identity.entity.LoginProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Drives the sign-in flow for every OAuth provider.
 *
 * <p>Both methods are total: they return a frontend URL for success and for every failure, and never
 * throw. That is deliberate. These flows are browser navigations, so an exception escaping to the
 * container would show the user a raw error page and lose the session attempt; a redirect to the
 * frontend's error page with a readable message is the only useful outcome. Controllers are therefore
 * reduced to a single {@code sendRedirect} and carry no error handling of their own.
 *
 * <p>Providers are injected as a list and indexed by {@link LoginProvider}, so adding one is a matter of
 * contributing an {@link OAuthLoginProvider} bean plus a controller route - nothing here changes.
 */
@Service
public class OAuthLoginService {

    private static final Logger log = LoggerFactory.getLogger(OAuthLoginService.class);

    private final Map<LoginProvider, OAuthLoginProvider> providers = new EnumMap<>(LoginProvider.class);
    private final OAuthLoginStateService stateService;
    private final OAuthUserProvisioningService provisioningService;
    private final OAuthRedirectFactory redirects;

    public OAuthLoginService(List<OAuthLoginProvider> providerBeans,
                             OAuthLoginStateService stateService,
                             OAuthUserProvisioningService provisioningService,
                             OAuthRedirectFactory redirects) {
        for (OAuthLoginProvider provider : providerBeans) {
            OAuthLoginProvider previous = this.providers.put(provider.provider(), provider);
            if (previous != null) {
                // Two beans claiming one provider would make which one runs depend on bean ordering.
                throw new IllegalStateException("Duplicate OAuthLoginProvider for " + provider.provider()
                        + ": " + previous.getClass().getName() + " and " + provider.getClass().getName());
            }
        }
        this.stateService = stateService;
        this.provisioningService = provisioningService;
        this.redirects = redirects;
    }

    /**
     * @return the provider consent URL to redirect the browser to, or the frontend error URL when the
     *         provider is unconfigured or unknown.
     */
    public String startAuthorization(LoginProvider provider) {
        try {
            OAuthLoginProvider delegate = require(provider);
            String authorizationUrl = delegate.buildAuthorizationUrl(stateService.issue(provider));
            log.info("OAUTH_AUTH_INITIATED: provider={}", provider);
            return authorizationUrl;

        } catch (ApiException ex) {
            log.warn("OAUTH_AUTH_INITIATION_FAILED: provider={}, reason={}", provider, ex.getMessage());
            return redirects.error(ex.getMessage());

        } catch (Exception ex) {
            log.error("OAUTH_AUTH_INITIATION_FAILED: provider={}, unexpected error", provider, ex);
            return redirects.error("Could not start sign-in. Please try again.");
        }
    }

    /**
     * Completes the flow: verifies the state, exchanges the code, reads the account, provisions the user,
     * and issues this application's token pair.
     *
     * @param code  the single-use authorization code, absent when the provider reported an error
     * @param state the value round-tripped from {@link #startAuthorization(LoginProvider)}
     * @param error the provider's error code, set when the user declined consent
     * @return the frontend success or error URL
     */
    public String completeAuthorization(LoginProvider provider, String code, String state, String error) {
        String displayName = displayName(provider);

        try {
            OAuthLoginProvider delegate = require(provider);

            if (hasText(error)) {
                log.warn("OAUTH_DENIED: provider={}, error={}", provider, error);
                return redirects.error(displayName + " authorization was denied");
            }
            if (!hasText(code)) {
                log.warn("OAUTH_FAILED: provider={}, reason=missing_code", provider);
                return redirects.error("Missing authorization code");
            }

            stateService.verify(state, provider);

            OAuthTokenSet tokens = delegate.exchangeAuthorizationCode(code);
            OAuthUserProfile profile = delegate.fetchUserProfile(tokens);

            AuthResponse authResponse = provisioningService.provision(provider, profile, tokens);
            return redirects.success(authResponse);

        } catch (ApiException ex) {
            log.warn("OAUTH_FAILED: provider={}, reason={}", provider, ex.getMessage());
            return redirects.error(ex.getMessage());

        } catch (Exception ex) {
            log.error("OAUTH_FAILED: provider={}, unexpected error", provider, ex);
            return redirects.error(displayName + " authentication failed due to an unexpected error");
        }
    }

    private OAuthLoginProvider require(LoginProvider provider) {
        OAuthLoginProvider delegate = providers.get(provider);
        if (delegate == null) {
            throw new ApiException("Sign-in with this provider is not available");
        }
        return delegate;
    }

    private String displayName(LoginProvider provider) {
        OAuthLoginProvider delegate = providers.get(provider);
        return delegate == null ? "This provider" : delegate.displayName();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
