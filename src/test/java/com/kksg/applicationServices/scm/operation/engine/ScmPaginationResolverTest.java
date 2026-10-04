package com.kksg.applicationServices.scm.operation.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kksg.applicationServices.scm.common.model.ScmPagination;
import com.kksg.applicationServices.scm.provider.config.ProviderConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Paging normalization across the two conventions the MVP providers use.
 *
 * <p>A caller writes one {@code while (hasNext)} loop; these tests establish that the loop terminates
 * correctly whether the provider signals continuation in the body, in a {@code Link} header, or not at all.
 */
class ScmPaginationResolverTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ScmPaginationResolver resolver = new ScmPaginationResolver();

    private ProviderConfiguration configuration(String json) throws Exception {
        return objectMapper.readValue(json, ProviderConfiguration.class);
    }

    private RequestConfiguration paginated() {
        return new RequestConfiguration(null, null, null, null, true, null, null, null);
    }

    private JsonNode arrayOfSize(int size) {
        var array = objectMapper.createArrayNode();
        for (int i = 0; i < size; i++) {
            array.add(objectMapper.createObjectNode().put("i", i));
        }
        return array;
    }

    @Test
    @DisplayName("a body `next` field is authoritative for continuation")
    void usesConfiguredNextPath() throws Exception {
        ProviderConfiguration bitbucketStyle = configuration("""
                {
                  "api": { "baseUrl": "https://api.bitbucket.org/2.0" },
                  "pagination": {
                    "type": "PAGE", "pageParameter": "page", "sizeParameter": "pagelen", "nextPath": "next"
                  }
                }
                """);

        ScmHttpResponse response = new ScmHttpResponse(200, Map.of(),
                objectMapper.readTree("{\"values\":[],\"next\":\"https://api.bitbucket.org/2.0/repositories?page=2\"}"),
                "");

        ScmPagination pagination = resolver.resolve(response, arrayOfSize(3), bitbucketStyle, paginated(),
                Map.of("page", 1, "pageSize", 50));

        assertThat(pagination.isHasNext()).isTrue();
        assertThat(pagination.getNextPage()).isEqualTo(2);
        assertThat(pagination.getNextCursor()).contains("page=2");
        assertThat(pagination.getItemCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("an absent `next` field means the final page, even on a full page")
    void absentNextPathEndsPagination() throws Exception {
        ProviderConfiguration bitbucketStyle = configuration("""
                {
                  "api": { "baseUrl": "https://api.bitbucket.org/2.0" },
                  "pagination": {
                    "type": "PAGE", "pageParameter": "page", "sizeParameter": "pagelen", "nextPath": "next"
                  }
                }
                """);

        // Full page of 2 items with pageSize 2, but the provider says there is nothing more. The explicit
        // signal must beat the full-page heuristic, otherwise we would issue a pointless extra request.
        ScmHttpResponse response = new ScmHttpResponse(200, Map.of(),
                objectMapper.readTree("{\"values\":[{},{}]}"), "");

        ScmPagination pagination = resolver.resolve(response, arrayOfSize(2), bitbucketStyle, paginated(),
                Map.of("page", 1, "pageSize", 2));

        assertThat(pagination.isHasNext()).isFalse();
        assertThat(pagination.getNextPage()).isNull();
    }

    @Test
    @DisplayName("an RFC 5988 Link header with rel=next drives continuation")
    void usesLinkHeader() throws Exception {
        ProviderConfiguration githubStyle = configuration("""
                {
                  "api": { "baseUrl": "https://api.github.com" },
                  "pagination": { "type": "PAGE", "pageParameter": "page", "sizeParameter": "per_page" }
                }
                """);

        ScmHttpResponse response = new ScmHttpResponse(200,
                Map.of("link", List.of("<https://api.github.com/user/repos?page=3>; rel=\"next\", "
                        + "<https://api.github.com/user/repos?page=9>; rel=\"last\"")),
                objectMapper.readTree("[]"), "");

        ScmPagination pagination = resolver.resolve(response, arrayOfSize(1), githubStyle, paginated(),
                Map.of("page", 2, "pageSize", 50));

        assertThat(pagination.isHasNext()).isTrue();
        assertThat(pagination.getNextPage()).isEqualTo(3);
        assertThat(pagination.getNextCursor()).isEqualTo("https://api.github.com/user/repos?page=3");
    }

    @Test
    @DisplayName("with no provider signal, a full page is assumed to have more")
    void fullPageHeuristicWhenNoSignal() throws Exception {
        ProviderConfiguration githubStyle = configuration("""
                {
                  "api": { "baseUrl": "https://api.github.com" },
                  "pagination": { "type": "PAGE", "pageParameter": "page", "sizeParameter": "per_page" }
                }
                """);

        ScmHttpResponse fullPage = new ScmHttpResponse(200, Map.of(), objectMapper.readTree("[]"), "");

        assertThat(resolver.resolve(fullPage, arrayOfSize(50), githubStyle, paginated(),
                Map.of("page", 1, "pageSize", 50)).isHasNext()).isTrue();

        // A partial page unambiguously ends pagination.
        assertThat(resolver.resolve(fullPage, arrayOfSize(17), githubStyle, paginated(),
                Map.of("page", 1, "pageSize", 50)).isHasNext()).isFalse();
    }

    @Test
    @DisplayName("a non-paginated operation always reports no continuation")
    void nonPaginatedOperationHasNoNext() throws Exception {
        ProviderConfiguration githubStyle = configuration("""
                {
                  "api": { "baseUrl": "https://api.github.com" },
                  "pagination": { "type": "PAGE", "pageParameter": "page", "sizeParameter": "per_page" }
                }
                """);

        RequestConfiguration notPaginated = RequestConfiguration.empty();

        ScmPagination pagination = resolver.resolve(
                new ScmHttpResponse(200, Map.of(), objectMapper.readTree("{}"), ""),
                arrayOfSize(50), githubStyle, notPaginated, Map.of("pageSize", 50));

        assertThat(pagination.isHasNext()).isFalse();
    }

    @Test
    @DisplayName("pagination type NONE disables continuation entirely")
    void paginationTypeNoneDisablesPaging() throws Exception {
        ProviderConfiguration unpaged = configuration("""
                { "api": { "baseUrl": "https://api.example.com" }, "pagination": { "type": "NONE" } }
                """);

        ScmPagination pagination = resolver.resolve(
                new ScmHttpResponse(200, Map.of(), objectMapper.readTree("[]"), ""),
                arrayOfSize(50), unpaged, paginated(), Map.of("pageSize", 50));

        assertThat(pagination.isHasNext()).isFalse();
    }
}
