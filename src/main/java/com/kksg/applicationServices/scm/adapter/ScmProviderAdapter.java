package com.kksg.applicationServices.scm.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.kksg.applicationServices.scm.common.model.ScmPagination;
import com.kksg.applicationServices.scm.common.model.ScmTokenSet;
import com.kksg.applicationServices.scm.operation.engine.ScmHttpRequest;
import com.kksg.applicationServices.scm.operation.engine.ScmHttpResponse;
import com.kksg.applicationServices.scm.operation.engine.ScmOperationContext;

import java.util.Optional;

/**
 * Optional escape hatch for provider behaviour that configuration genuinely cannot express.
 *
 * <p><b>Configuration first.</b> The rule this module is built on is that a new provider is added by
 * inserting rows, and an adapter is written only when the generic engine cannot represent the
 * provider's behaviour. An adapter must never be written merely because a provider is
 * "important" - that reintroduces the {@code GitHubService} / {@code BitbucketService} duplication
 * the design exists to prevent.
 *
 * <p><b>Neither GitHub nor Bitbucket ships an adapter.</b> Both are fully described by database
 * configuration in the current implementation. The differences one might expect to need code - GitHub
 * sending client credentials in a JSON body while Bitbucket uses HTTP Basic with a form body, GitHub
 * returning a bare array where Bitbucket wraps results in {@code values} with a {@code next} URL,
 * diffs requiring a media-type header on one and a different path on the other, Bitbucket's diffstat
 * splitting a file path across {@code new.path} and {@code old.path} - are all handled by
 * {@code TokenRequest.authStyle}/{@code encoding}, {@code itemsPath}, per-operation {@code headers},
 * and fallback field paths respectively. This interface exists as a proven extension point, not as
 * scaffolding awaiting an implementation.
 *
 * <p><b>When an adapter is the right answer.</b> Every hook below returns an "unhandled" value by
 * default, so an implementation overrides only what it needs:
 * <ul>
 *   <li>{@link #customizeRequest} - request signing that depends on the request itself (an HMAC over
 *       method, path and body, as some enterprise gateways require). Not expressible as a static
 *       header.</li>
 *   <li>{@link #exchangeAuthorizationCode} - a token endpoint that is not standard OAuth 2.0
 *       authorization-code: JWT bearer assertions, or a two-call exchange.</li>
 *   <li>{@link #resolvePagination} - paging that needs computation, e.g. deriving a cursor by
 *       parsing an opaque token, rather than reading a field or a page number.</li>
 *   <li>{@link #customizeResponse} - a transformation that needs a conditional. The known example in
 *       this codebase: GitHub reports a merged pull request as {@code state: "closed"} with a
 *       non-null {@code merged_at}, so distinguishing MERGED from CLOSED requires looking at two
 *       fields together. Declarative mappings have no conditionals by design, and the MVP does not
 *       need the distinction, so it is documented as a limitation rather than solved with code.</li>
 *   <li>{@link #verifyWebhookSignature} - a signature scheme that is not "HMAC over the raw body",
 *       such as one covering a canonicalised subset of headers.</li>
 * </ul>
 *
 * <p>Implementations are discovered as Spring beans by {@link ScmAdapterRegistry} and matched on
 * {@link #providerCode()}. Adding one requires no change to the engine.
 */
public interface ScmProviderAdapter {

    /**
     * @return the {@code scm_providers.provider_code} this adapter serves. Matched case-insensitively.
     */
    String providerCode();

    /**
     * Last chance to adjust a request after the engine has fully resolved it from configuration.
     *
     * @return the request to send; return the argument unchanged to opt out.
     */
    default ScmHttpRequest customizeRequest(ScmHttpRequest request, ScmOperationContext context) {
        return request;
    }

    /**
     * Adjusts the normalized payload after declarative mapping has run.
     *
     * @param normalized result of the declarative mapping.
     * @param response   the raw provider response, for fields the mapping did not extract.
     * @return the payload to expose; return {@code normalized} unchanged to opt out.
     */
    default JsonNode customizeResponse(JsonNode normalized, ScmHttpResponse response, ScmOperationContext context) {
        return normalized;
    }

    /**
     * Supplies paging state the declarative resolver could not derive.
     *
     * @return empty to let the configuration-driven resolver decide.
     */
    default Optional<ScmPagination> resolvePagination(ScmHttpResponse response, ScmOperationContext context) {
        return Optional.empty();
    }

    /**
     * Replaces the standard authorization-code exchange.
     *
     * @return empty to use the configuration-driven exchange.
     */
    default Optional<ScmTokenSet> exchangeAuthorizationCode(String code, ScmOperationContext context) {
        return Optional.empty();
    }

    /**
     * Replaces the standard HMAC webhook verification.
     *
     * @param rawBody the exact bytes received; any re-serialisation would invalidate the MAC.
     * @return empty to use the configuration-driven verifier, or a decision to override it.
     */
    default Optional<Boolean> verifyWebhookSignature(byte[] rawBody, java.util.Map<String, String> headers,
                                                     String secret) {
        return Optional.empty();
    }
}
