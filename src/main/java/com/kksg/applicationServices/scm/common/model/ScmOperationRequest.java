package com.kksg.applicationServices.scm.common.model;

import java.util.HashMap;
import java.util.Map;

/**
 * A provider-independent request to perform one normalized SCM operation.
 *
 * <p>Built by higher modules and handed to
 * {@code com.kksg.applicationServices.scm.operation.engine.ScmClient}:
 *
 * <pre>{@code
 * ScmOperationRequest request = ScmOperationRequest.of(ScmOperationCode.LIST_PULL_REQUESTS)
 *         .parameter("owner", "acme")
 *         .parameter("repo", "api")
 *         .page(1, 50);
 * }</pre>
 *
 * <p>Parameters are untyped on purpose. They are the inputs referenced by {@code {{tokens}}} in a
 * provider's {@code endpoint_template} and {@code request_configuration}, and which tokens exist
 * is provider configuration rather than compiled knowledge. The engine validates that every token
 * a provider requires has been supplied, so a missing parameter fails with
 * {@code SCM_OPERATION_PARAMETER_MISSING} before any network call is made.
 *
 * <p>Mutable builder style (rather than Lombok {@code @Builder}) is used so that callers can add
 * parameters conditionally in a loop without an intermediate collection.
 */
public class ScmOperationRequest {

    /** Conventional parameter name for the requested page number. */
    public static final String PARAM_PAGE = "page";

    /** Conventional parameter name for the requested page size. */
    public static final String PARAM_PAGE_SIZE = "pageSize";

    /** Conventional parameter name for an opaque continuation token. */
    public static final String PARAM_CURSOR = "cursor";

    private final ScmOperationCode operation;
    private final Map<String, Object> parameters = new HashMap<>();
    private Object body;

    private ScmOperationRequest(ScmOperationCode operation) {
        if (operation == null) {
            throw new IllegalArgumentException("operation is required");
        }
        this.operation = operation;
    }

    public static ScmOperationRequest of(ScmOperationCode operation) {
        return new ScmOperationRequest(operation);
    }

    public ScmOperationRequest parameter(String name, Object value) {
        if (name != null && value != null) {
            parameters.put(name, value);
        }
        return this;
    }

    public ScmOperationRequest parameters(Map<String, Object> values) {
        if (values != null) {
            values.forEach(this::parameter);
        }
        return this;
    }

    /**
     * Convenience for page-based listing. Also accepted by cursor-based providers, whose
     * configuration simply ignores the page token.
     */
    public ScmOperationRequest page(Integer page, Integer pageSize) {
        return parameter(PARAM_PAGE, page).parameter(PARAM_PAGE_SIZE, pageSize);
    }

    public ScmOperationRequest cursor(String cursor) {
        return parameter(PARAM_CURSOR, cursor);
    }

    /**
     * Request body for write operations. Supplied in <b>normalized</b> form; the provider's
     * {@code request_configuration.bodyTemplate} reshapes it into the provider's expected
     * document, so callers never build provider-specific payloads.
     */
    public ScmOperationRequest body(Object body) {
        this.body = body;
        return this;
    }

    public ScmOperationCode getOperation() {
        return operation;
    }

    public Map<String, Object> getParameters() {
        return parameters;
    }

    public Object getBody() {
        return body;
    }
}
