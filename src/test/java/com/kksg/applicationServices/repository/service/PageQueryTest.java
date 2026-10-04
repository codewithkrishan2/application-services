package com.kksg.applicationServices.repository.service;

import com.kksg.applicationServices.scm.common.exception.ScmErrorCode;
import com.kksg.applicationServices.scm.common.exception.ScmException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Paging request validation.
 *
 * <p>The decision under test is <b>reject rather than clamp</b>. A caller asking for 5000 items has a
 * mistaken model of what it will receive, and quietly returning 100 leaves it believing it has seen
 * everything - so the mistake resurfaces later as missing data rather than immediately as a 400.
 */
class PageQueryTest {

    @Test
    @DisplayName("defaults an absent page and size")
    void appliesDefaults() {
        PageQuery query = PageQuery.of(null, null, null);

        assertThat(query.page()).isZero();
        assertThat(query.size()).isEqualTo(PageQuery.DEFAULT_SIZE);
        assertThat(query.hasSearch()).isFalse();
        assertThat(query.search()).isNull();
    }

    @Test
    @DisplayName("computes the offset of the requested page")
    void computesOffset() {
        assertThat(PageQuery.of(0, 20, null).offset()).isZero();
        assertThat(PageQuery.of(3, 20, null).offset()).isEqualTo(60);
    }

    @Test
    @DisplayName("case-folds the search term once, at the boundary")
    void foldsSearchTerm() {
        // Folded here so the scan does not repeat the work per candidate; the mappers rely on the term
        // already being lower case.
        PageQuery query = PageQuery.of(0, 20, "  CodeAnt  ");

        assertThat(query.search()).isEqualTo("codeant");
        assertThat(query.hasSearch()).isTrue();
    }

    @Test
    @DisplayName("an empty search is no search, not a match-everything filter")
    void blankSearchIsNoFilter() {
        // "?search=" must not become "contains the empty string", which every row satisfies.
        assertThat(PageQuery.of(0, 20, "").hasSearch()).isFalse();
        assertThat(PageQuery.of(0, 20, "   ").hasSearch()).isFalse();
    }

    @Test
    @DisplayName("rejects a negative page")
    void rejectsNegativePage() {
        assertThatThrownBy(() -> PageQuery.of(-1, 20, null))
                .isInstanceOf(ScmException.class)
                .satisfies(thrown -> assertThat(((ScmException) thrown).getErrorCode())
                        .isEqualTo(ScmErrorCode.SCM_REQUEST_INVALID));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -5, 101, 5000})
    @DisplayName("rejects a size outside the accepted range")
    void rejectsOutOfRangeSize(int size) {
        assertThatThrownBy(() -> PageQuery.of(0, size, null))
                .isInstanceOf(ScmException.class)
                .hasMessageContaining("size must be between");
    }

    @Test
    @DisplayName("accepts the range boundaries")
    void acceptsBoundarySizes() {
        assertThat(PageQuery.of(0, 1, null).size()).isEqualTo(1);
        assertThat(PageQuery.of(0, PageQuery.MAX_SIZE, null).size()).isEqualTo(PageQuery.MAX_SIZE);
    }

    @Test
    @DisplayName("rejects an overlong search term")
    void rejectsOverlongSearch() {
        assertThatThrownBy(() -> PageQuery.of(0, 20, "x".repeat(201)))
                .isInstanceOf(ScmException.class)
                .hasMessageContaining("search must be");
    }
}
