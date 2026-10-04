package com.kksg.applicationServices.repository.service;

import com.kksg.applicationServices.scm.common.exception.ScmErrorCode;
import com.kksg.applicationServices.scm.common.exception.ScmException;

import java.util.Locale;

/**
 * A validated {@code page} / {@code size} / {@code search} request.
 *
 * <p>Validated rather than clamped. A caller asking for {@code size=5000} has a mistaken idea of what
 * it will get back, and quietly serving 100 items leaves it believing it has seen everything - the
 * failure then surfaces as missing data somewhere downstream instead of as a 400 at the boundary.
 * Rejecting is the cheaper error. (Page size is separately clamped deeper in the stack, against each
 * provider's own ceiling, which is a different concern: that one protects the engine's own
 * "did I get a full page?" reasoning and must not reject, since the ceiling is provider configuration
 * the caller cannot know.)
 *
 * @param page   zero-based page index.
 * @param size   items per page.
 * @param search case-folded free-text filter, or {@code null} when absent.
 */
public record PageQuery(int page, int size, String search) {

    public static final int DEFAULT_SIZE = 20;

    /**
     * Ceiling on {@code size}.
     *
     * <p>Chosen to match the page size the configured providers cap at, so one request for a full page
     * is one upstream call. A larger value would silently become several upstream calls or a truncated
     * page depending on the provider.
     */
    public static final int MAX_SIZE = 100;

    /** Longest accepted search term. Bounded so the filter cannot be used to push large payloads. */
    private static final int MAX_SEARCH_LENGTH = 200;

    /**
     * Builds a query from raw request parameters, each of which may be absent.
     *
     * @throws ScmException {@link ScmErrorCode#SCM_REQUEST_INVALID} when a supplied value is out of
     *         range.
     */
    public static PageQuery of(Integer page, Integer size, String search) {
        int resolvedPage = page == null ? 0 : page;
        if (resolvedPage < 0) {
            throw new ScmException(ScmErrorCode.SCM_REQUEST_INVALID, "page must not be negative");
        }

        int resolvedSize = size == null ? DEFAULT_SIZE : size;
        if (resolvedSize < 1 || resolvedSize > MAX_SIZE) {
            throw new ScmException(ScmErrorCode.SCM_REQUEST_INVALID,
                    "size must be between 1 and %d".formatted(MAX_SIZE));
        }

        return new PageQuery(resolvedPage, resolvedSize, normalizeSearch(search));
    }

    public boolean hasSearch() {
        return search != null;
    }

    /** Index of the first item on this page within the full result sequence. */
    public int offset() {
        return page * size;
    }

    /**
     * Case-folds and bounds the search term.
     *
     * <p>Folded once here rather than per comparison: the term is tested against every candidate on
     * every scanned page, and lower-casing it in the loop would be the same work repeated thousands of
     * times. A blank term becomes {@code null}, so {@code ?search=} behaves as "no filter" rather than
     * as "match the empty string", which every row would satisfy.
     */
    private static String normalizeSearch(String search) {
        if (search == null) {
            return null;
        }
        String trimmed = search.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_SEARCH_LENGTH) {
            throw new ScmException(ScmErrorCode.SCM_REQUEST_INVALID,
                    "search must be %d characters or fewer".formatted(MAX_SEARCH_LENGTH));
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }
}
