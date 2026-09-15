package com.kksg.applicationServices.scm.operation.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.kksg.applicationServices.scm.common.model.ScmOperationRequest;
import com.kksg.applicationServices.scm.common.model.ScmPagination;
import com.kksg.applicationServices.scm.common.util.JsonNodePaths;
import com.kksg.applicationServices.scm.provider.config.ProviderConfiguration;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Derives normalized paging state from a provider response.
 *
 * <p>Three detection strategies are tried, in decreasing order of reliability. The order matters:
 * each earlier strategy is authoritative when available, and the last is a heuristic that must only
 * apply when the provider tells us nothing.
 * <ol>
 *   <li><b>Configured {@code nextPath}</b> - the provider states the next page in its body
 *       (Bitbucket's {@code next}). Authoritative.</li>
 *   <li><b>{@code Link} header with {@code rel="next"}</b> - the RFC 5988 convention GitHub follows.
 *       Authoritative, and implemented against the standard rather than against a specific
 *       provider, so any provider using it benefits with no configuration.</li>
 *   <li><b>Full-page heuristic</b> - a response holding exactly the requested page size probably has
 *       more. Used only when neither signal above exists. It can produce one extra empty request when
 *       the total is an exact multiple of the page size, which is why it ranks last; an extra empty
 *       page is a much cheaper error than silently truncating a repository list.</li>
 * </ol>
 */
@Component
public class ScmPaginationResolver {

    /** Matches an RFC 5988 {@code Link} entry whose relation is {@code next}. */
    private static final Pattern LINK_NEXT = Pattern.compile("<([^>]+)>\\s*;\\s*rel=\"?next\"?", Pattern.CASE_INSENSITIVE);

    public ScmPagination resolve(ScmHttpResponse response,
                                 JsonNode normalized,
                                 ProviderConfiguration configuration,
                                 RequestConfiguration requestConfiguration,
                                 Map<String, Object> parameters) {

        int itemCount = normalized != null && normalized.isArray() ? normalized.size() : 0;

        if (!requestConfiguration.isPaginated()) {
            return ScmPagination.builder().hasNext(false).itemCount(itemCount).build();
        }

        ProviderConfiguration.Pagination pagination = configuration.paginationOrEmpty();
        if (pagination.typeOrDefault() == ProviderConfiguration.PaginationType.NONE) {
            return ScmPagination.builder().hasNext(false).itemCount(itemCount).build();
        }

        Integer currentPage = readInt(parameters.get(ScmOperationRequest.PARAM_PAGE));
        Integer pageSize = readInt(parameters.get(ScmOperationRequest.PARAM_PAGE_SIZE));

        String nextIndicator = readNextIndicator(response, pagination);
        boolean hasNext = nextIndicator != null;

        if (!hasNext && pagination.typeOrDefault() == ProviderConfiguration.PaginationType.PAGE) {
            hasNext = pageSize != null && itemCount >= pageSize;
        }

        ScmPagination.ScmPaginationBuilder builder = ScmPagination.builder()
                .page(currentPage)
                .pageSize(pageSize)
                .itemCount(itemCount)
                .hasNext(hasNext)
                .nextCursor(nextIndicator);

        if (hasNext && pagination.typeOrDefault() == ProviderConfiguration.PaginationType.PAGE) {
            builder.nextPage(currentPage != null ? currentPage + 1 : 2);
        }
        return builder.build();
    }

    /**
     * @return the provider's own "there is more" token, or {@code null} when it supplied none.
     */
    private String readNextIndicator(ScmHttpResponse response, ProviderConfiguration.Pagination pagination) {
        if (pagination.nextPath() != null && !pagination.nextPath().isBlank() && response.bodyJson() != null) {
            String fromBody = JsonNodePaths.textAt(response.bodyJson(), pagination.nextPath());
            if (fromBody != null && !fromBody.isBlank()) {
                return fromBody;
            }
        }
        String linkHeader = response.header("link");
        if (linkHeader != null) {
            var matcher = LINK_NEXT.matcher(linkHeader);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return null;
    }

    private Integer readInt(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
