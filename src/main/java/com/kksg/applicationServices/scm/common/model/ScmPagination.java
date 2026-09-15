package com.kksg.applicationServices.scm.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Normalized paging state returned alongside a list operation.
 *
 * <p>Providers page in incompatible ways: GitHub uses {@code page}/{@code per_page} plus an
 * RFC 5988 {@code Link} header, Bitbucket uses {@code page}/{@code pagelen} plus a {@code next}
 * URL in the body. Rather than exposing either, the engine reports the two facts a caller
 * actually needs - "is there more?" and "what do I pass to get it?".
 *
 * <p>A caller loops on {@link #isHasNext()} and feeds {@link #getNextPage()} (or
 * {@link #getNextCursor()} for cursor-style providers) back into the next request. That loop is
 * identical for every provider, which is the point.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScmPagination {

    /** Page that produced this response, when the provider is page-based. */
    private Integer page;

    private Integer pageSize;

    /** Value to pass as {@code page} to retrieve the following page, when known. */
    private Integer nextPage;

    /**
     * Opaque continuation token for cursor-based providers. When present the caller should pass it
     * as the {@code cursor} parameter instead of computing a page number.
     */
    private String nextCursor;

    private boolean hasNext;

    /** Total item count when the provider volunteers it; {@code null} otherwise. */
    private Integer totalCount;

    /** Number of items in this response. */
    private int itemCount;

    /** Paging state for a non-paged operation, or for a response with nothing further to fetch. */
    public static ScmPagination empty() {
        return ScmPagination.builder().hasNext(false).build();
    }
}
