package com.kksg.applicationServices.scm.common.util;

import com.kksg.applicationServices.scm.common.exception.ScmErrorCode;
import com.kksg.applicationServices.scm.common.exception.ScmException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The substitution rules that every endpoint template, query parameter and body template depends on.
 *
 * <p>The required/optional split is the behaviour worth pinning down: an unresolved token in a URL path
 * must fail loudly, while an unresolved query parameter must cause the parameter to be dropped. Getting
 * that backwards would either send requests to malformed URLs or reject every call that omits an
 * optional filter.
 */
class PlaceholderResolverTest {

    @Nested
    @DisplayName("resolveRequired")
    class ResolveRequired {

        @Test
        void substitutesFlatAndNestedParameters() {
            String result = PlaceholderResolver.resolveRequired(
                    "/repos/{{owner}}/{{repository.slug}}/pulls/{{number}}",
                    Map.of("owner", "acme",
                            "repository", Map.of("slug", "api"),
                            "number", 42),
                    "GET_PULL_REQUEST");

            assertThat(result).isEqualTo("/repos/acme/api/pulls/42");
        }

        @Test
        void toleratesWhitespaceInsideBraces() {
            String result = PlaceholderResolver.resolveRequired(
                    "/repos/{{ owner }}", Map.of("owner", "acme"), "GET_REPOSITORY");

            assertThat(result).isEqualTo("/repos/acme");
        }

        @Test
        @DisplayName("fails with SCM_OPERATION_PARAMETER_MISSING naming every missing token")
        void failsWhenTokenUnresolved() {
            assertThatThrownBy(() -> PlaceholderResolver.resolveRequired(
                    "/repos/{{owner}}/{{repo}}", Map.of("owner", "acme"), "GET_REPOSITORY"))
                    .isInstanceOf(ScmException.class)
                    .satisfies(thrown -> assertThat(((ScmException) thrown).getErrorCode())
                            .isEqualTo(ScmErrorCode.SCM_OPERATION_PARAMETER_MISSING))
                    // The operation label and parameter name must appear so the error is actionable.
                    .hasMessageContaining("GET_REPOSITORY")
                    .hasMessageContaining("repo");
        }

        @Test
        void leavesTemplatesWithoutPlaceholdersUntouched() {
            assertThat(PlaceholderResolver.resolveRequired("/user/repos", Map.of(), "LIST_REPOSITORIES"))
                    .isEqualTo("/user/repos");
        }
    }

    @Nested
    @DisplayName("resolveOptional")
    class ResolveOptional {

        @Test
        void returnsValueWhenAllTokensResolve() {
            assertThat(PlaceholderResolver.resolveOptional("{{page}}", Map.of("page", 3)))
                    .contains("3");
        }

        @Test
        @DisplayName("returns empty so the caller can omit the whole value")
        void returnsEmptyWhenAnyTokenUnresolved() {
            // This is what makes an unsupplied `state` filter disappear from the query string rather
            // than being sent as a literal "{{state}}".
            assertThat(PlaceholderResolver.resolveOptional("{{state}}", Map.of())).isEmpty();
        }

        @Test
        void returnsEmptyWhenOnlySomeTokensResolve() {
            Optional<String> result =
                    PlaceholderResolver.resolveOptional("{{a}}-{{b}}", Map.of("a", "1"));

            assertThat(result).isEmpty();
        }
    }

    @Test
    void detectsPresenceOfPlaceholders() {
        assertThat(PlaceholderResolver.containsPlaceholder("{{x}}")).isTrue();
        assertThat(PlaceholderResolver.containsPlaceholder("plain")).isFalse();
        assertThat(PlaceholderResolver.containsPlaceholder(null)).isFalse();
    }

    @Test
    @DisplayName("resolves indexed list parameters")
    void resolvesListIndexes() {
        String result = PlaceholderResolver.resolveRequired(
                "{{branches[1]}}", Map.of("branches", List.of("main", "develop")), "TEST");

        assertThat(result).isEqualTo("develop");
    }
}
