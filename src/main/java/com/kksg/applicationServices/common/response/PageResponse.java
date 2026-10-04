package com.kksg.applicationServices.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;

import java.util.List;

/**
 * One page of a collection, for endpoints whose data is paged.
 *
 * <p>Lives in {@code common} rather than in a feature package because the shape is a property of this
 * API rather than of any one resource: a client that can read a page of repositories should be able to
 * read a page of pull requests with the same code.
 *
 * <p><b>{@code totalElements} and {@code totalPages} are nullable, and that is the point.</b> This
 * envelope is frequently filled from an upstream provider that does not publish a total - GitHub's
 * repository listing and Bitbucket's diffstat both say only "there is another page". The honest options
 * were to omit the totals or to fabricate them; a fabricated total is worse than an absent one, because
 * a client cannot tell it is wrong and will render "1-20 of 20" over a list that has three more pages.
 * So they are absent when unknown, {@code @JsonInclude(NON_NULL)} drops the keys entirely, and
 * {@link #isHasNext()} carries the fact a pager actually needs.
 *
 * <p>{@code page} is <b>zero-based</b>, matching Spring Data and the query parameter clients send.
 * Providers that page from one are translated at the boundary that talks to them, not here.
 */
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PageResponse<T> {

    private final List<T> content;

    /** Zero-based index of this page. */
    private final int page;

    /** Requested page size. The page may hold fewer items; it never holds more. */
    private final int size;

    /** Total matching items, or {@code null} when the source does not publish one. */
    private final Integer totalElements;

    /** Derived from {@link #totalElements}; {@code null} when that is unknown. */
    private final Integer totalPages;

    private final boolean first;

    /**
     * Whether this is the final page.
     *
     * <p>Always the negation of {@link #hasNext}, so it is accurate even when totals are unknown -
     * unlike a {@code page == totalPages - 1} derivation, which would need a total to mean anything.
     */
    private final boolean last;

    private final boolean hasNext;

    private PageResponse(List<T> content, int page, int size, Integer totalElements, boolean hasNext) {
        this.content = content != null ? List.copyOf(content) : List.of();
        this.page = page;
        this.size = size;
        this.totalElements = totalElements;
        this.totalPages = totalElements == null || size <= 0
                ? null
                : Math.max(1, (int) Math.ceil(totalElements / (double) size));
        this.first = page <= 0;
        this.hasNext = hasNext;
        this.last = !hasNext;
    }

    /**
     * A page whose total is unknown. The common case for provider-backed collections.
     */
    public static <T> PageResponse<T> of(List<T> content, int page, int size, boolean hasNext) {
        return new PageResponse<>(content, page, size, null, hasNext);
    }

    /**
     * A page whose total is known, either because the source published it or because the whole
     * collection was enumerated.
     *
     * <p>{@code hasNext} is still passed explicitly rather than derived from the total: the two must
     * agree, and the caller is the only party that knows whether it actually reached the end.
     */
    public static <T> PageResponse<T> of(List<T> content, int page, int size, boolean hasNext,
                                         Integer totalElements) {
        return new PageResponse<>(content, page, size, totalElements, hasNext);
    }

    /** An empty page, used when a request is valid but provably has no results to fetch. */
    public static <T> PageResponse<T> empty(int page, int size) {
        return new PageResponse<>(List.of(), page, size, 0, false);
    }
}
