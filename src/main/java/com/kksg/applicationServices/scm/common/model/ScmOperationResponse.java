package com.kksg.applicationServices.scm.common.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kksg.applicationServices.scm.common.exception.ScmErrorCode;
import com.kksg.applicationServices.scm.common.exception.ScmException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Result of executing one normalized SCM operation.
 *
 * <p>Exposes the response at three levels so that each consumer can pick the weakest coupling that
 * still does the job:
 * <ol>
 *   <li>{@link #as(Class)} / {@link #asList(Class)} - typed normalized models. This is what
 *       Modules 3-8 should use.</li>
 *   <li>{@link #getNormalized()} - the normalized tree, for generic traversal.</li>
 *   <li>{@link #getRawBody()} / {@link #getRawText()} - the untouched provider response. Present
 *       for diffs (which are text, not JSON) and for diagnostics. Reading provider-shaped fields
 *       from here re-introduces exactly the coupling this module removes, so it should be treated
 *       as a debugging affordance rather than an API.</li>
 * </ol>
 *
 * <p>The {@link ObjectMapper} is carried on the instance rather than being passed in per call so
 * that consuming code stays free of Jackson wiring. It is supplied by the engine, which owns a
 * configured mapper.
 */
public class ScmOperationResponse {

    private final ScmOperationCode operation;
    private final String providerCode;
    private final int httpStatus;
    private final JsonNode rawBody;
    private final String rawText;
    private final JsonNode normalized;
    private final ScmPagination pagination;
    private final ObjectMapper objectMapper;

    private ScmOperationResponse(Builder builder) {
        this.operation = builder.operation;
        this.providerCode = builder.providerCode;
        this.httpStatus = builder.httpStatus;
        this.rawBody = builder.rawBody;
        this.rawText = builder.rawText;
        this.normalized = builder.normalized;
        this.pagination = builder.pagination != null ? builder.pagination : ScmPagination.empty();
        this.objectMapper = builder.objectMapper;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Converts the normalized payload to a single typed model.
     *
     * @throws ScmException with {@link ScmErrorCode#SCM_RESPONSE_MAPPING_INVALID} if the normalized
     *                      shape cannot be bound to {@code type}, which indicates a defective
     *                      {@code response_mapping} rather than a caller error.
     */
    public <T> T as(Class<T> type) {
        if (normalized == null || normalized.isNull()) {
            return null;
        }
        JsonNode source = normalized.isArray() && normalized.size() == 1 ? normalized.get(0) : normalized;
        try {
            return objectMapper.treeToValue(source, type);
        } catch (Exception ex) {
            throw new ScmException(ScmErrorCode.SCM_RESPONSE_MAPPING_INVALID,
                    "operation=%s target=%s".formatted(operation, type.getSimpleName()), ex);
        }
    }

    /**
     * Converts the normalized payload to a typed list. A single normalized object is treated as a
     * one-element list so that callers do not need to special-case providers that return an object
     * where another returns an array.
     */
    public <T> List<T> asList(Class<T> type) {
        if (normalized == null || normalized.isNull()) {
            return Collections.emptyList();
        }
        try {
            if (!normalized.isArray()) {
                return List.of(objectMapper.treeToValue(normalized, type));
            }
            List<T> items = new ArrayList<>(normalized.size());
            for (JsonNode element : normalized) {
                items.add(objectMapper.treeToValue(element, type));
            }
            return items;
        } catch (Exception ex) {
            throw new ScmException(ScmErrorCode.SCM_RESPONSE_MAPPING_INVALID,
                    "operation=%s target=%s".formatted(operation, type.getSimpleName()), ex);
        }
    }

    public ScmOperationCode getOperation() {
        return operation;
    }

    public String getProviderCode() {
        return providerCode;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public JsonNode getRawBody() {
        return rawBody;
    }

    /** Raw response text; the payload for {@code GET_PULL_REQUEST_DIFF}. */
    public String getRawText() {
        return rawText;
    }

    public JsonNode getNormalized() {
        return normalized;
    }

    public ScmPagination getPagination() {
        return pagination;
    }

    public static final class Builder {
        private ScmOperationCode operation;
        private String providerCode;
        private int httpStatus;
        private JsonNode rawBody;
        private String rawText;
        private JsonNode normalized;
        private ScmPagination pagination;
        private ObjectMapper objectMapper;

        public Builder operation(ScmOperationCode operation) {
            this.operation = operation;
            return this;
        }

        public Builder providerCode(String providerCode) {
            this.providerCode = providerCode;
            return this;
        }

        public Builder httpStatus(int httpStatus) {
            this.httpStatus = httpStatus;
            return this;
        }

        public Builder rawBody(JsonNode rawBody) {
            this.rawBody = rawBody;
            return this;
        }

        public Builder rawText(String rawText) {
            this.rawText = rawText;
            return this;
        }

        public Builder normalized(JsonNode normalized) {
            this.normalized = normalized;
            return this;
        }

        public Builder pagination(ScmPagination pagination) {
            this.pagination = pagination;
            return this;
        }

        public Builder objectMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
            return this;
        }

        public ScmOperationResponse build() {
            return new ScmOperationResponse(this);
        }
    }
}
