package com.kksg.applicationServices.scm.common.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Path navigation used by every response mapping.
 *
 * <p>The "missing yields null rather than throwing" rule is the important one: providers omit optional
 * fields routinely, and an exception here would fail an entire repository listing because one repository
 * had no description.
 */
class JsonNodePathsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private JsonNode document;

    @BeforeEach
    void setUp() throws Exception {
        // Shaped after a real Bitbucket repository payload, including the nested clone link array that
        // motivated array-index support.
        document = objectMapper.readTree("""
                {
                  "uuid": "{9f1c-abc}",
                  "name": "api",
                  "is_private": true,
                  "description": null,
                  "workspace": { "slug": "acme" },
                  "links": {
                    "clone": [
                      { "name": "https", "href": "https://bitbucket.org/acme/api.git" },
                      { "name": "ssh", "href": "git@bitbucket.org:acme/api.git" }
                    ],
                    "html": { "href": "https://bitbucket.org/acme/api" }
                  },
                  "values": [ { "id": 1 }, { "id": 2 } ]
                }
                """);
    }

    @Test
    void readsTopLevelField() {
        assertThat(JsonNodePaths.textAt(document, "name")).isEqualTo("api");
    }

    @Test
    void readsNestedField() {
        assertThat(JsonNodePaths.textAt(document, "workspace.slug")).isEqualTo("acme");
    }

    @Test
    @DisplayName("reads an array element by index")
    void readsArrayIndex() {
        assertThat(JsonNodePaths.textAt(document, "links.clone[0].href"))
                .isEqualTo("https://bitbucket.org/acme/api.git");
        assertThat(JsonNodePaths.textAt(document, "links.clone[1].href"))
                .isEqualTo("git@bitbucket.org:acme/api.git");
    }

    @Test
    void ignoresOptionalJsonPathRootPrefix() {
        assertThat(JsonNodePaths.at(document, "$.values").isArray()).isTrue();
        assertThat(JsonNodePaths.at(document, "$")).isSameAs(document);
    }

    @Test
    void returnsArrayNodeItself() {
        assertThat(JsonNodePaths.at(document, "values").size()).isEqualTo(2);
    }

    @Test
    @DisplayName("returns null for absent, explicitly-null, and out-of-range paths")
    void returnsNullRatherThanThrowing() {
        assertThat(JsonNodePaths.at(document, "missing")).isNull();
        assertThat(JsonNodePaths.at(document, "workspace.missing")).isNull();
        assertThat(JsonNodePaths.at(document, "missing.deeper.still")).isNull();
        // An explicit JSON null must behave the same as absence, so mapping falls back to a default.
        assertThat(JsonNodePaths.at(document, "description")).isNull();
        assertThat(JsonNodePaths.at(document, "links.clone[9].href")).isNull();
        assertThat(JsonNodePaths.at(document, "name[0]")).isNull();
    }

    @Test
    void handlesNullAndBlankInputs() {
        assertThat(JsonNodePaths.at(null, "name")).isNull();
        assertThat(JsonNodePaths.at(document, null)).isNull();
        assertThat(JsonNodePaths.at(document, "  ")).isNull();
    }

    @Test
    @DisplayName("renders non-string scalars as text so numeric ids normalize to strings")
    void rendersScalarsAsText() {
        assertThat(JsonNodePaths.textAt(document, "is_private")).isEqualTo("true");
        assertThat(JsonNodePaths.textAt(document, "values[0].id")).isEqualTo("1");
    }
}
