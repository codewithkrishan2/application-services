package com.kksg.applicationServices.repository.dto;

import com.kksg.applicationServices.scm.common.exception.ScmErrorCode;
import com.kksg.applicationServices.scm.common.exception.ScmException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The canonical pull-request state filter and its two deliberate choices. */
class PullRequestStateFilterTest {

    @ParameterizedTest
    @CsvSource({
            "OPEN,OPEN", "open,OPEN", "  Open  ,OPEN",
            "closed,CLOSED", "MERGED,MERGED", "all,ALL"
    })
    @DisplayName("parses case-insensitively")
    void parsesCaseInsensitively(String input, PullRequestStateFilter expected) {
        assertThat(PullRequestStateFilter.parse(input)).isEqualTo(expected);
    }

    @Test
    @DisplayName("defaults to OPEN rather than ALL")
    void defaultsToOpen() {
        // Open pull requests are what a reviewer arrives to look at; defaulting to everything would
        // make the first page of a long-lived repository a list of history.
        assertThat(PullRequestStateFilter.parse(null)).isEqualTo(PullRequestStateFilter.OPEN);
        assertThat(PullRequestStateFilter.parse("")).isEqualTo(PullRequestStateFilter.OPEN);
        assertThat(PullRequestStateFilter.parse("  ")).isEqualTo(PullRequestStateFilter.OPEN);
    }

    @Test
    @DisplayName("rejects an unrecognised state instead of silently defaulting")
    void rejectsUnknownState() {
        // Defaulting would answer a misspelled filter with a plausible list that does not address the
        // question asked, which is harder to notice than a 400.
        assertThatThrownBy(() -> PullRequestStateFilter.parse("DECLINED"))
                .isInstanceOf(ScmException.class)
                .satisfies(thrown -> assertThat(((ScmException) thrown).getErrorCode())
                        .isEqualTo(ScmErrorCode.SCM_REQUEST_INVALID))
                .hasMessageContaining("state must be one of");
    }

    @Test
    @DisplayName("has no UNKNOWN value")
    void hasNoUnknownValue() {
        // "Show me the pull requests whose state we failed to recognise" is not a request anyone makes,
        // and accepting it would mean sending a meaningless filter to a provider.
        assertThat(PullRequestStateFilter.values())
                .extracting(Enum::name)
                .containsExactly("OPEN", "CLOSED", "MERGED", "ALL");
    }
}
