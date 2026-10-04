package com.kksg.applicationServices.repository.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kksg.applicationServices.scm.common.model.ScmOperationCode;
import com.kksg.applicationServices.scm.common.model.ScmOperationResponse;
import com.kksg.applicationServices.scm.common.model.ScmPagination;

import java.util.List;

/**
 * Builds the normalized responses the operation engine would return, for tests that mock the engine.
 *
 * <p>Shared rather than repeated per test class because the shape has three parts that must agree - the
 * normalized tree, the paging state and the mapper - and a test that got one of them wrong would pass
 * for the wrong reason.
 */
final class ScmResponses {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private ScmResponses() {
    }

    /** A list response carrying {@code items}, with paging state saying whether more exist. */
    static ScmOperationResponse list(ScmOperationCode operation, List<?> items, boolean hasNext) {
        return list(operation, items, hasNext, null);
    }

    static ScmOperationResponse list(ScmOperationCode operation, List<?> items, boolean hasNext,
                                     Integer totalCount) {
        JsonNode normalized = MAPPER.valueToTree(items);
        return ScmOperationResponse.builder()
                .operation(operation)
                .providerCode("GITHUB")
                .httpStatus(200)
                .normalized(normalized)
                .pagination(ScmPagination.builder()
                        .itemCount(items.size())
                        .hasNext(hasNext)
                        .totalCount(totalCount)
                        .build())
                .objectMapper(MAPPER)
                .build();
    }

    /** A single-object response. */
    static ScmOperationResponse object(ScmOperationCode operation, Object value) {
        return ScmOperationResponse.builder()
                .operation(operation)
                .providerCode("GITHUB")
                .httpStatus(200)
                .normalized(value == null ? MAPPER.nullNode() : MAPPER.valueToTree(value))
                .pagination(ScmPagination.empty())
                .objectMapper(MAPPER)
                .build();
    }

    /** A text response, as {@code GET_PULL_REQUEST_DIFF} produces. */
    static ScmOperationResponse text(ScmOperationCode operation, String body) {
        return ScmOperationResponse.builder()
                .operation(operation)
                .providerCode("GITHUB")
                .httpStatus(200)
                .rawText(body)
                .normalized(MAPPER.nullNode())
                .pagination(ScmPagination.empty())
                .objectMapper(MAPPER)
                .build();
    }
}
