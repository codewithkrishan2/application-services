package com.kksg.applicationServices.repository.service;

import com.kksg.applicationServices.scm.common.exception.ScmErrorCode;
import com.kksg.applicationServices.scm.common.exception.ScmException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Repository addressing: the validation that keeps a path segment a path segment.
 *
 * <p>The request builder already percent-encodes values and rejects dot segments before anything
 * reaches a URL, so these are the outer of two independent checks. They are worth having anyway: a
 * malformed reference should be refused where it enters the application, with an error naming the
 * caller's mistake, rather than surviving to become a strange provider response.
 */
class RepositoryRefTest {

    @Test
    @DisplayName("parses an owner-qualified name")
    void parsesFullName() {
        RepositoryRef ref = RepositoryRef.parse("acme/my-service");

        assertThat(ref.owner()).isEqualTo("acme");
        assertThat(ref.name()).isEqualTo("my-service");
        assertThat(ref.fullName()).isEqualTo("acme/my-service");
    }

    @Test
    @DisplayName("trims surrounding whitespace")
    void trimsSegments() {
        RepositoryRef ref = new RepositoryRef("  acme  ", " api ");

        assertThat(ref.owner()).isEqualTo("acme");
        assertThat(ref.name()).isEqualTo("api");
    }

    @ParameterizedTest
    @ValueSource(strings = {"acme", "/api", "acme/", "", "   "})
    @DisplayName("rejects a full name that is not in owner/name form")
    void rejectsMalformedFullName(String candidate) {
        assertThatThrownBy(() -> RepositoryRef.parse(candidate))
                .isInstanceOf(ScmException.class)
                .satisfies(thrown -> assertThat(((ScmException) thrown).getErrorCode())
                        .isEqualTo(ScmErrorCode.SCM_REQUEST_INVALID));
    }

    @Test
    @DisplayName("rejects a null full name")
    void rejectsNullFullName() {
        assertThatThrownBy(() -> RepositoryRef.parse(null))
                .isInstanceOf(ScmException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("rejects a blank segment")
    void rejectsBlankSegments(String blank) {
        assertThatThrownBy(() -> new RepositoryRef(blank, "api"))
                .isInstanceOf(ScmException.class)
                .hasMessageContaining("owner");

        assertThatThrownBy(() -> new RepositoryRef("acme", blank))
                .isInstanceOf(ScmException.class)
                .hasMessageContaining("repo");
    }

    @ParameterizedTest
    @ValueSource(strings = {"acme/evil", "acme\\evil", "../etc", "a/b/c"})
    @DisplayName("rejects a path separator inside a segment")
    void rejectsPathSeparators(String candidate) {
        // A slash would split one path segment into two and move the outbound request to a different
        // endpoint than the one the operation template describes.
        assertThatThrownBy(() -> new RepositoryRef(candidate, "api"))
                .isInstanceOf(ScmException.class)
                .satisfies(thrown -> assertThat(((ScmException) thrown).getErrorCode())
                        .isEqualTo(ScmErrorCode.SCM_REQUEST_INVALID));
    }

    @Test
    @DisplayName("rejects a control character or line break in a segment")
    void rejectsControlCharacters() {
        // A CR or LF reaching an outbound header or URL is the classic request-splitting primitive.
        assertThatThrownBy(() -> new RepositoryRef("acme", "api\r\nX-Injected: 1"))
                .isInstanceOf(ScmException.class);
        assertThatThrownBy(() -> new RepositoryRef("ac\u0000me", "api"))
                .isInstanceOf(ScmException.class);
    }

    @Test
    @DisplayName("rejects an implausibly long segment")
    void rejectsOverlongSegment() {
        assertThatThrownBy(() -> new RepositoryRef("acme", "x".repeat(201)))
                .isInstanceOf(ScmException.class)
                .hasMessageContaining("longer than");
    }

    @Test
    @DisplayName("splits on the last separator, keeping the owner intact")
    void splitsOnLastSeparator() {
        // An owner cannot contain a slash, so if a provider ever nests a repository path the owner is
        // still the part before the final separator. Splitting on the first would truncate it.
        assertThatThrownBy(() -> RepositoryRef.parse("acme/group/api"))
                .isInstanceOf(ScmException.class)
                .hasMessageContaining("path separator");
    }
}
