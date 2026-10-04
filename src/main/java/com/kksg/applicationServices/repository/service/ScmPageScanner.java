package com.kksg.applicationServices.repository.service;

import com.kksg.applicationServices.common.response.PageResponse;
import com.kksg.applicationServices.repository.RepositoryManagementProperties;
import com.kksg.applicationServices.scm.common.exception.ScmErrorCode;
import com.kksg.applicationServices.scm.common.model.ScmOperationCode;
import com.kksg.applicationServices.scm.common.model.ScmOperationRequest;
import com.kksg.applicationServices.scm.common.model.ScmOperationResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Turns a paged SCM list operation into one {@link PageResponse}, with or without a search term.
 *
 * <p>Shared by repositories, pull requests and changed files, because paging an upstream list is the
 * same problem each time and the two tricky parts - translating between zero-based API pages and
 * one-based provider pages, and deciding {@code hasNext} when no total exists - are worth getting right
 * once.
 *
 * <h2>Two modes</h2>
 *
 * <p><b>No search: one provider call per page.</b> API page <i>n</i> maps to provider page <i>n+1</i>
 * at the requested size, and {@code hasNext} comes from the engine's normalized paging state - which is
 * derived from the provider's own {@code Link} header or {@code next} link rather than guessed. This is
 * the path every ordinary request takes, and it is deliberately the cheap one.
 *
 * <p><b>With search: a bounded scan.</b> Neither configured provider offers a server-side search on
 * these listings - GitHub's repository listing has no query parameter for it, and the pull-request and
 * diffstat listings have none either - so the filter is applied here, over pages fetched in sequence.
 *
 * <p>That is a real compromise and it is worth being precise about its edges rather than discovering
 * them later:
 * <ul>
 *   <li>It costs up to {@code repository.search.max-pages} provider calls, which is why the bound
 *       exists and why the scan stops as soon as it has enough to answer.</li>
 *   <li><b>It can only find what it has looked at.</b> A match beyond the bound is not returned. The
 *       listings are ordered most-recently-updated first, so what the bound cuts off is the least
 *       recently touched, which is the right end to lose.</li>
 *   <li><b>Absence of {@code totalElements} is the signal that the result set may be incomplete.</b> A
 *       scan that reached the end of the provider's data knows the exact total and reports it; a scan
 *       stopped by the bound reports no total. That distinction is the only honest thing a client can
 *       be told, and it is better than a count that looks authoritative and is not.</li>
 * </ul>
 *
 * <p>{@code hasNext} in the scan mode is computed strictly from matches already collected, never from
 * "the provider might have more". The scan is deterministic from page one, so claiming a further page
 * on the strength of unscanned data would offer a Next button that returns the same empty page forever.
 *
 * <p>The alternative designs were both worse. Fetching everything and filtering in memory is what the
 * bound exists to prevent. Filtering only the single page the client asked for would mean typing into a
 * search box on page one of twenty found nothing while a match sat on page three - a filter that
 * appears broken is worse than one with a documented reach.
 */
@Service
public class ScmPageScanner {

    private static final Logger log = LoggerFactory.getLogger(ScmPageScanner.class);

    /** Providers number their first page 1; this API's first page is 0. */
    private static final int PROVIDER_FIRST_PAGE = 1;

    private final ScmOperationRunner runner;
    private final RepositoryManagementProperties properties;

    public ScmPageScanner(ScmOperationRunner runner, RepositoryManagementProperties properties) {
        this.runner = runner;
        this.properties = properties;
    }

    /**
     * Fetches one page.
     *
     * @param parameters     operation parameters other than paging - owner, repo, state. Paging is
     *                       added here so a caller cannot accidentally fix the page it asks for.
     * @param normalizedType the normalized model the engine should bind each item to.
     * @param matches        the search predicate, or {@code null} for no search. Supplied by the caller
     *                       because which fields a search covers is a property of the resource, not of
     *                       paging - a repository matches on name and description, a pull request on
     *                       title, author and branch.
     * @param mapper         normalized model to response DTO.
     * @param notFoundAs     error code to report if the provider says the containing resource is gone.
     */
    public <N, R> PageResponse<R> fetchPage(ScmResourceContext context,
                                            ScmOperationCode operation,
                                            Map<String, Object> parameters,
                                            Class<N> normalizedType,
                                            PageQuery query,
                                            Predicate<N> matches,
                                            Function<N, R> mapper,
                                            ScmErrorCode notFoundAs) {

        if (matches == null || !query.hasSearch()) {
            return fetchDirect(context, operation, parameters, normalizedType, query, mapper, notFoundAs);
        }
        return fetchFiltered(context, operation, parameters, normalizedType, query, matches, mapper,
                notFoundAs);
    }

    /** One provider page, one API page. */
    private <N, R> PageResponse<R> fetchDirect(ScmResourceContext context,
                                               ScmOperationCode operation,
                                               Map<String, Object> parameters,
                                               Class<N> normalizedType,
                                               PageQuery query,
                                               Function<N, R> mapper,
                                               ScmErrorCode notFoundAs) {

        ScmOperationResponse response = runner.run(context,
                request(operation, parameters).page(query.page() + PROVIDER_FIRST_PAGE, query.size()),
                notFoundAs);

        List<R> content = response.asList(normalizedType).stream().map(mapper).toList();

        // totalCount is whatever the provider volunteered, which for these listings is normally nothing.
        // Passing it through rather than substituting content.size() keeps "unknown" distinguishable
        // from "this is all of it".
        return PageResponse.of(content, query.page(), query.size(),
                response.getPagination().isHasNext(), response.getPagination().getTotalCount());
    }

    /** Scan provider pages, keeping matches, until the requested page can be answered. */
    private <N, R> PageResponse<R> fetchFiltered(ScmResourceContext context,
                                                 ScmOperationCode operation,
                                                 Map<String, Object> parameters,
                                                 Class<N> normalizedType,
                                                 PageQuery query,
                                                 Predicate<N> matches,
                                                 Function<N, R> mapper,
                                                 ScmErrorCode notFoundAs) {

        int maxPages = Math.max(1, properties.getSearch().getMaxPages());
        int scanSize = Math.max(1, properties.getSearch().getPageSize());

        // One more than the page needs, so hasNext is known without a further scan.
        int required = query.offset() + query.size() + 1;

        List<N> matched = new ArrayList<>();
        boolean reachedEnd = false;
        int pagesScanned = 0;
        int itemsScanned = 0;

        while (pagesScanned < maxPages && matched.size() < required) {
            ScmOperationResponse response = runner.run(context,
                    request(operation, parameters)
                            .page(PROVIDER_FIRST_PAGE + pagesScanned, scanSize),
                    notFoundAs,
                    // Record "last used" on the first call only; the rest are the same request.
                    pagesScanned == 0);

            List<N> items = response.asList(normalizedType);
            itemsScanned += items.size();
            for (N item : items) {
                if (matches.test(item)) {
                    matched.add(item);
                }
            }
            pagesScanned++;

            if (!response.getPagination().isHasNext()) {
                reachedEnd = true;
                break;
            }
        }

        int from = Math.min(query.offset(), matched.size());
        int to = Math.min(from + query.size(), matched.size());
        List<R> content = matched.subList(from, to).stream().map(mapper).toList();

        boolean hasNext = matched.size() > query.offset() + query.size();
        Integer totalElements = reachedEnd ? matched.size() : null;

        log.info("REPO_SEARCH_SCAN: userId={}, connectionId={}, providerCode={}, operation={}, "
                        + "pagesScanned={}, itemsScanned={}, matched={}, reachedEnd={}",
                context.userId(), context.connectionId(), context.providerCode(), operation,
                pagesScanned, itemsScanned, matched.size(), reachedEnd);

        return PageResponse.of(content, query.page(), query.size(), hasNext, totalElements);
    }

    private ScmOperationRequest request(ScmOperationCode operation, Map<String, Object> parameters) {
        return ScmOperationRequest.of(operation).parameters(parameters);
    }
}
