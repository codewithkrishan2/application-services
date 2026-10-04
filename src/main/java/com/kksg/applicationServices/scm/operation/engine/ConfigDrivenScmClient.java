package com.kksg.applicationServices.scm.operation.engine;

import com.kksg.applicationServices.scm.common.http.OutboundUrlPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kksg.applicationServices.scm.adapter.ScmAdapterRegistry;
import com.kksg.applicationServices.scm.capability.service.ScmProviderCapabilityService;
import com.kksg.applicationServices.scm.common.exception.ScmErrorCode;
import com.kksg.applicationServices.scm.common.exception.ScmException;
import com.kksg.applicationServices.scm.common.http.ScmHttpExecutor;
import com.kksg.applicationServices.scm.common.model.ScmCapabilityCode;
import com.kksg.applicationServices.scm.common.model.ScmOperationRequest;
import com.kksg.applicationServices.scm.common.model.ScmOperationResponse;
import com.kksg.applicationServices.scm.common.model.ScmPagination;
import com.kksg.applicationServices.scm.connection.entity.ScmConnection;
import com.kksg.applicationServices.scm.connection.service.ScmTokenService;
import com.kksg.applicationServices.scm.operation.service.ResolvedOperation;
import com.kksg.applicationServices.scm.operation.service.ScmProviderOperationService;
import com.kksg.applicationServices.scm.provider.config.ProviderConfiguration;
import com.kksg.applicationServices.scm.provider.entity.ScmProvider;
import com.kksg.applicationServices.scm.provider.service.ScmProviderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The generic SCM engine: executes any normalized operation against any configured provider.
 *
 * <p><b>There is no provider name anywhere in this class.</b> That is the single most important
 * property of the module, and the reason it is worth reading this class to understand the design. The
 * sequence below is identical for GitHub, Bitbucket, and any provider added later by inserting rows:
 *
 * <pre>
 *   normalized request
 *        |
 *        v
 *   provider + parsed configuration        (scm_providers.configuration)
 *        |
 *        v
 *   capability gate                        (scm_provider_capabilities)
 *        |
 *        v
 *   operation recipe                       (scm_provider_operations)
 *        |
 *        v
 *   access token, refreshed if needed      (ScmTokenService)
 *        |
 *        v
 *   build request -> [adapter hook] -> send
 *        |
 *        v
 *   classify status
 *        |
 *        v
 *   normalize response -> [adapter hook]
 *        |
 *        v
 *   resolve pagination -> [adapter hook]
 *        |
 *        v
 *   normalized response
 * </pre>
 *
 * <p><b>Not transactional, on purpose.</b> Each step reads through a service that manages its own
 * short transaction. Wrapping the whole method would hold a pooled database connection for the entire
 * duration of an external HTTP call, coupling database pool capacity to provider latency - the usual
 * way an integration layer takes down the rest of an application when a provider slows down.
 */
@Service
public class ConfigDrivenScmClient implements ScmClient {

    private static final Logger log = LoggerFactory.getLogger(ConfigDrivenScmClient.class);

    /** Connection metadata key that overrides the provider's configured API base URL. */
    private static final String METADATA_BASE_URL = "baseUrl";

    private final ScmProviderService providerService;
    private final ScmProviderCapabilityService capabilityService;
    private final ScmProviderOperationService operationService;
    private final ScmTokenService tokenService;
    private final ScmRequestBuilder requestBuilder;
    private final ScmHttpExecutor httpExecutor;
    private final ScmResponseNormalizer responseNormalizer;
    private final ScmPaginationResolver paginationResolver;
    private final ScmAdapterRegistry adapterRegistry;
    private final ObjectMapper objectMapper;

    public ConfigDrivenScmClient(ScmProviderService providerService,
                                 ScmProviderCapabilityService capabilityService,
                                 ScmProviderOperationService operationService,
                                 ScmTokenService tokenService,
                                 ScmRequestBuilder requestBuilder,
                                 ScmHttpExecutor httpExecutor,
                                 ScmResponseNormalizer responseNormalizer,
                                 ScmPaginationResolver paginationResolver,
                                 ScmAdapterRegistry adapterRegistry,
                                 ObjectMapper objectMapper) {
        this.providerService = providerService;
        this.capabilityService = capabilityService;
        this.operationService = operationService;
        this.tokenService = tokenService;
        this.requestBuilder = requestBuilder;
        this.httpExecutor = httpExecutor;
        this.responseNormalizer = responseNormalizer;
        this.paginationResolver = paginationResolver;
        this.adapterRegistry = adapterRegistry;
        this.objectMapper = objectMapper;
    }

    @Override
    public ScmOperationResponse execute(ScmConnection connection, ScmOperationRequest request) {
        if (connection == null) {
            throw new ScmException(ScmErrorCode.SCM_CONNECTION_NOT_FOUND);
        }
        // Re-read the provider by id rather than dereferencing the lazy association: the caller may
        // have loaded the connection outside a session, and the identifier is available on the proxy
        // without triggering initialization.
        ScmProvider provider = providerService.requireById(connection.getProvider().getId());
        providerService.requireActive(provider);

        String accessToken = tokenService.resolveAccessToken(connection, provider);
        return dispatch(provider, connection, accessToken, request);
    }

    @Override
    public ScmOperationResponse executeWithToken(ScmProvider provider, String accessToken,
                                                 ScmOperationRequest request) {
        providerService.requireActive(provider);
        return dispatch(provider, null, accessToken, request);
    }

    @Override
    public boolean supports(ScmProvider provider, ScmCapabilityCode capabilityCode) {
        return capabilityService.isSupported(provider, capabilityCode);
    }

    private ScmOperationResponse dispatch(ScmProvider provider,
                                          ScmConnection connection,
                                          String accessToken,
                                          ScmOperationRequest request) {

        ProviderConfiguration configuration = providerService.getConfiguration(provider);

        // Capability gate first: refusing an unsupported operation before touching the network turns a
        // confusing provider 404 into a precise SCM_OPERATION_NOT_SUPPORTED.
        ScmCapabilityCode requiredCapability = request.getOperation().requiredCapability();
        if (requiredCapability != null) {
            capabilityService.requireSupported(provider, requiredCapability);
        }

        ResolvedOperation resolved = operationService.require(provider, request.getOperation());
        String baseUrl = resolveBaseUrl(configuration, connection);

        ScmRequestBuilder.BuiltRequest built =
                requestBuilder.build(request, configuration, resolved, baseUrl, accessToken);

        ScmOperationContext context = new ScmOperationContext(
                provider, configuration, connection, request.getOperation(), resolved, built.parameters());

        ScmHttpRequest httpRequest = adapterRegistry.find(provider.getProviderCode())
                .map(adapter -> adapter.customizeRequest(built.request(), context))
                .orElse(built.request());

        long startedAt = System.nanoTime();
        ScmHttpResponse httpResponse = httpExecutor.execute(httpRequest);
        long durationMs = (System.nanoTime() - startedAt) / 1_000_000;

        if (!httpResponse.isSuccessful()) {
            throw classifyFailure(provider, request, httpResponse, durationMs);
        }

        String operationLabel = String.valueOf(request.getOperation());
        JsonNode declarativelyMapped =
                responseNormalizer.normalize(httpResponse, resolved.responseMapping(), operationLabel);

        JsonNode normalized = adapterRegistry.find(provider.getProviderCode())
                .map(adapter -> adapter.customizeResponse(declarativelyMapped, httpResponse, context))
                .orElse(declarativelyMapped);

        ScmPagination pagination = adapterRegistry.find(provider.getProviderCode())
                .flatMap(adapter -> adapter.resolvePagination(httpResponse, context))
                .orElseGet(() -> paginationResolver.resolve(
                        httpResponse, normalized, configuration, resolved.requestConfiguration(),
                        built.parameters()));

        log.info("SCM_OPERATION_COMPLETED: providerCode={}, operationCode={}, connectionId={}, status={}, "
                        + "itemCount={}, hasNext={}, durationMs={}",
                provider.getProviderCode(), operationLabel, context.connectionId(),
                httpResponse.statusCode(), pagination.getItemCount(), pagination.isHasNext(), durationMs);

        return ScmOperationResponse.builder()
                .operation(request.getOperation())
                .providerCode(provider.getProviderCode())
                .httpStatus(httpResponse.statusCode())
                .rawBody(httpResponse.bodyJson())
                .rawText(httpResponse.bodyText())
                .normalized(normalized)
                .pagination(pagination)
                .objectMapper(objectMapper)
                .build();
    }

    /**
     * A connection may override the provider's base URL.
     *
     * <p>This is what lets a single {@code SELF_HOSTED} provider row serve many tenants: each
     * connection carries its own instance URL in {@code metadata.baseUrl}, so adding a customer's
     * private GitLab requires no new provider row and no code.
     */
    private String resolveBaseUrl(ProviderConfiguration configuration, ScmConnection connection) {
        if (connection != null && connection.getMetadata() != null) {
            Object override = connection.getMetadata().get(METADATA_BASE_URL);
            if (override instanceof String text && !text.isBlank()) {
                // Validated on use, not merely on write. This value is per-connection metadata rather than
                // reviewed provider configuration, and it decides the destination of a request that carries
                // the user's bearer token - so it is the one place an SSRF would be introduced.
                try {
                    return OutboundUrlPolicy.requireAllowed(text, "connection metadata baseUrl");
                } catch (IllegalArgumentException ex) {
                    throw new ScmException(ScmErrorCode.SCM_PROVIDER_CONFIGURATION_INVALID, ex.getMessage());
                }
            }
        }
        // The provider's own base URL was already checked when the configuration was loaded, so it is not
        // re-resolved here: that would add a DNS lookup to every provider call for no added safety.
        return configuration.apiOrEmpty().baseUrl();
    }

    /**
     * Maps a provider HTTP failure onto a domain error.
     *
     * <p>The status determines the caller's correct response, so the distinctions are preserved rather
     * than collapsed into one generic failure: 401 means the credential must be renewed or the user
     * must reauthorize; 403/429 with rate-limit signals means back off and retry later; anything else
     * is a provider or configuration fault.
     *
     * <p>The response body is logged but never placed in the exception message: provider error bodies
     * echo request content, and a webhook-creation call carries a webhook secret in its request body.
     */
    private ScmException classifyFailure(ScmProvider provider,
                                         ScmOperationRequest request,
                                         ScmHttpResponse response,
                                         long durationMs) {
        int status = response.statusCode();
        log.warn("SCM_OPERATION_FAILED: providerCode={}, operationCode={}, status={}, durationMs={}",
                provider.getProviderCode(), request.getOperation(), status, durationMs);

        if (status == 401) {
            return new ScmException(ScmErrorCode.SCM_CONNECTION_EXPIRED,
                    "providerCode=%s rejected the credential".formatted(provider.getProviderCode()));
        }
        if (status == 429 || (status == 403 && isRateLimited(response))) {
            return new ScmException(ScmErrorCode.SCM_PROVIDER_RATE_LIMITED,
                    "providerCode=%s".formatted(provider.getProviderCode()));
        }
        return new ScmException(ScmErrorCode.SCM_PROVIDER_API_ERROR,
                "providerCode=%s operationCode=%s status=%d"
                        .formatted(provider.getProviderCode(), request.getOperation(), status));
    }

    /**
     * Distinguishes a rate-limit 403 from an authorisation 403.
     *
     * <p>Some providers signal exhaustion with 403 plus a zeroed remaining-quota header rather than
     * 429. Checking the conventional headers is provider-neutral: a provider that does not send them
     * simply falls through to the generic classification.
     */
    private boolean isRateLimited(ScmHttpResponse response) {
        String remaining = response.header("x-ratelimit-remaining");
        if (remaining != null && "0".equals(remaining.trim())) {
            return true;
        }
        return response.header("retry-after") != null;
    }

}
