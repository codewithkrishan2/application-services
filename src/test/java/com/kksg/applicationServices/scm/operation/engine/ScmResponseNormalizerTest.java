package com.kksg.applicationServices.scm.operation.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kksg.applicationServices.scm.common.exception.ScmErrorCode;
import com.kksg.applicationServices.scm.common.exception.ScmException;
import com.kksg.applicationServices.scm.common.model.FileChangeType;
import com.kksg.applicationServices.scm.common.model.NormalizedPullRequestFile;
import com.kksg.applicationServices.scm.common.model.NormalizedRepository;
import com.kksg.applicationServices.scm.common.model.PullRequestState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Response normalization: the boundary that stops provider-shaped JSON from reaching Modules 3-8.
 *
 * <p>The headline assertion of this class is {@code githubAndBitbucketProduceIdenticalNormalizedShape}:
 * two structurally different provider payloads, two different mappings, one identical normalized result.
 * If that holds, downstream modules genuinely cannot tell the providers apart.
 */
class ScmResponseNormalizerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ScmResponseNormalizer normalizer = new ScmResponseNormalizer(objectMapper);

    private ScmHttpResponse jsonResponse(String body) throws Exception {
        return new ScmHttpResponse(200, Map.of(), objectMapper.readTree(body), body);
    }

    private ResponseMapping mapping(String json) throws Exception {
        return objectMapper.readValue(json, ResponseMapping.class);
    }

    @Test
    @DisplayName("GitHub and Bitbucket repository payloads normalize to the same shape")
    void githubAndBitbucketProduceIdenticalNormalizedShape() throws Exception {
        ScmHttpResponse githubResponse = jsonResponse("""
                [{
                  "id": 123,
                  "name": "api",
                  "full_name": "acme/api",
                  "private": true,
                  "default_branch": "main",
                  "owner": { "login": "acme" },
                  "clone_url": "https://github.com/acme/api.git",
                  "html_url": "https://github.com/acme/api"
                }]
                """);
        ResponseMapping githubMapping = mapping("""
                {
                  "type": "LIST",
                  "fields": {
                    "externalId": "id",
                    "name": "name",
                    "fullName": "full_name",
                    "owner": "owner.login",
                    "isPrivate": "private",
                    "defaultBranch": "default_branch",
                    "cloneUrl": "clone_url",
                    "webUrl": "html_url"
                  },
                  "transforms": { "externalId": "TO_STRING", "isPrivate": "TO_BOOLEAN" }
                }
                """);

        // Bitbucket wraps results in `values`, uses `uuid`/`is_private`, and buries the clone URL in an array.
        ScmHttpResponse bitbucketResponse = jsonResponse("""
                {
                  "values": [{
                    "uuid": "123",
                    "name": "api",
                    "full_name": "acme/api",
                    "is_private": true,
                    "mainbranch": { "name": "main" },
                    "workspace": { "slug": "acme" },
                    "links": {
                      "clone": [{ "name": "https", "href": "https://github.com/acme/api.git" }],
                      "html": { "href": "https://github.com/acme/api" }
                    }
                  }],
                  "next": null
                }
                """);
        ResponseMapping bitbucketMapping = mapping("""
                {
                  "type": "LIST",
                  "itemsPath": "values",
                  "fields": {
                    "externalId": "uuid",
                    "name": "name",
                    "fullName": "full_name",
                    "owner": "workspace.slug",
                    "isPrivate": "is_private",
                    "defaultBranch": "mainbranch.name",
                    "cloneUrl": "links.clone[0].href",
                    "webUrl": "links.html.href"
                  },
                  "transforms": { "externalId": "TO_STRING", "isPrivate": "TO_BOOLEAN" }
                }
                """);

        JsonNode fromGithub = normalizer.normalize(githubResponse, githubMapping, "LIST_REPOSITORIES");
        JsonNode fromBitbucket = normalizer.normalize(bitbucketResponse, bitbucketMapping, "LIST_REPOSITORIES");

        assertThat(fromGithub).isEqualTo(fromBitbucket);

        NormalizedRepository repository = objectMapper.treeToValue(fromGithub.get(0), NormalizedRepository.class);
        assertThat(repository.getExternalId()).isEqualTo("123");
        assertThat(repository.getFullName()).isEqualTo("acme/api");
        assertThat(repository.getOwner()).isEqualTo("acme");
        assertThat(repository.getIsPrivate()).isTrue();
        assertThat(repository.getDefaultBranch()).isEqualTo("main");
    }

    @Test
    @DisplayName("TO_STRING reconciles a numeric id with a UUID string id")
    void toStringTransformNormalizesIdentifierTypes() throws Exception {
        ResponseMapping numericMapping = mapping("""
                { "type": "OBJECT", "fields": { "externalId": "id" }, "transforms": { "externalId": "TO_STRING" } }
                """);

        JsonNode normalized = normalizer.normalize(jsonResponse("""
                { "id": 987654321 }
                """), numericMapping, "GET_REPOSITORY");

        assertThat(normalized.get("externalId").isTextual()).isTrue();
        assertThat(normalized.get("externalId").asText()).isEqualTo("987654321");
    }

    @Test
    @DisplayName("fallback paths pick the first non-null candidate")
    void fallbackFieldPathsHandleAddedAndDeletedFiles() throws Exception {
        // Bitbucket's diffstat nulls `new.path` for a deletion and `old.path` for an addition. One mapping
        // must cover both without a conditional.
        ResponseMapping diffstatMapping = mapping("""
                {
                  "type": "LIST",
                  "itemsPath": "values",
                  "fields": {
                    "path": ["new.path", "old.path"],
                    "changeType": "status",
                    "additions": "lines_added",
                    "deletions": "lines_removed"
                  },
                  "transforms": { "additions": "TO_INTEGER", "deletions": "TO_INTEGER" },
                  "valueMappings": {
                    "changeType": { "added": "ADDED", "removed": "REMOVED", "modified": "MODIFIED" }
                  },
                  "defaults": { "changeType": "UNKNOWN" }
                }
                """);

        JsonNode normalized = normalizer.normalize(jsonResponse("""
                { "values": [
                  { "status": "added",    "new": { "path": "src/New.java" }, "old": null,
                    "lines_added": 10, "lines_removed": 0 },
                  { "status": "removed",  "new": null, "old": { "path": "src/Gone.java" },
                    "lines_added": 0,  "lines_removed": 7 },
                  { "status": "modified", "new": { "path": "src/Same.java" }, "old": { "path": "src/Same.java" },
                    "lines_added": 3,  "lines_removed": 2 }
                ] }
                """), diffstatMapping, "GET_PULL_REQUEST_FILES");

        List<NormalizedPullRequestFile> files = objectMapper.readerForListOf(NormalizedPullRequestFile.class)
                .readValue(normalized);

        assertThat(files).hasSize(3);
        assertThat(files.get(0).getPath()).isEqualTo("src/New.java");
        assertThat(files.get(0).getChangeType()).isEqualTo(FileChangeType.ADDED);
        // Deletion: `new.path` was null, so the second candidate supplied the path.
        assertThat(files.get(1).getPath()).isEqualTo("src/Gone.java");
        assertThat(files.get(1).getChangeType()).isEqualTo(FileChangeType.REMOVED);
        assertThat(files.get(2).getPath()).isEqualTo("src/Same.java");
        assertThat(files.get(2).getDeletions()).isEqualTo(2);
    }

    @Test
    @DisplayName("valueMappings translate provider vocabulary case-insensitively")
    void valueMappingsTranslateStates() throws Exception {
        ResponseMapping bitbucketStates = mapping("""
                {
                  "type": "LIST",
                  "itemsPath": "values",
                  "fields": { "state": "state" },
                  "valueMappings": {
                    "state": { "OPEN": "OPEN", "MERGED": "MERGED", "DECLINED": "CLOSED", "SUPERSEDED": "CLOSED" }
                  }
                }
                """);

        JsonNode normalized = normalizer.normalize(jsonResponse("""
                { "values": [ {"state":"OPEN"}, {"state":"declined"}, {"state":"MERGED"}, {"state":"WEIRD"} ] }
                """), bitbucketStates, "LIST_PULL_REQUESTS");

        assertThat(normalized.get(0).get("state").asText()).isEqualTo("OPEN");
        assertThat(normalized.get(1).get("state").asText()).isEqualTo("CLOSED");
        assertThat(normalized.get(2).get("state").asText()).isEqualTo("MERGED");
        // Unmapped values pass through, then degrade at the enum boundary rather than being lost.
        assertThat(normalized.get(3).get("state").asText()).isEqualTo("WEIRD");
        assertThat(PullRequestState.fromCode(normalized.get(3).get("state").asText()))
                .isEqualTo(PullRequestState.UNKNOWN);
    }

    @Test
    @DisplayName("defaults fill fields the provider does not report")
    void defaultsApplyWhenExtractionYieldsNothing() throws Exception {
        ResponseMapping withDefault = mapping("""
                {
                  "type": "OBJECT",
                  "fields": { "changeType": "status", "path": "filename" },
                  "defaults": { "changeType": "UNKNOWN" }
                }
                """);

        JsonNode normalized = normalizer.normalize(jsonResponse("""
                { "filename": "src/A.java" }
                """), withDefault, "GET_PULL_REQUEST_FILES");

        assertThat(normalized.get("changeType").asText()).isEqualTo("UNKNOWN");
        assertThat(normalized.get("path").asText()).isEqualTo("src/A.java");
    }

    @Test
    @DisplayName("a missing field with no default is omitted rather than failing")
    void missingFieldsAreOmitted() throws Exception {
        ResponseMapping withOptional = mapping("""
                { "type": "OBJECT", "fields": { "name": "name", "description": "description" } }
                """);

        JsonNode normalized = normalizer.normalize(jsonResponse("""
                { "name": "api", "description": null }
                """), withOptional, "GET_REPOSITORY");

        assertThat(normalized.has("name")).isTrue();
        assertThat(normalized.has("description")).isFalse();
    }

    @Test
    @DisplayName("TEXT mapping exposes the raw body, for unified diffs")
    void textMappingReturnsRawBody() {
        String diff = "diff --git a/A.java b/A.java\n@@ -1 +1 @@\n-old\n+new\n";
        ScmHttpResponse textResponse = new ScmHttpResponse(200, Map.of(), null, diff);

        JsonNode normalized = normalizer.normalize(
                textResponse, ResponseMapping.raw(), "GET_PULL_REQUEST_DIFF");

        // raw() has no declared type, so RAW passes the (absent) JSON body through.
        assertThat(normalized).isNull();

        ResponseMapping textMapping = new ResponseMapping(
                ResponseMapping.MappingType.TEXT, null, null, null, null, null);
        JsonNode asText = normalizer.normalize(textResponse, textMapping, "GET_PULL_REQUEST_DIFF");

        assertThat(asText.isTextual()).isTrue();
        assertThat(asText.asText()).isEqualTo(diff);
    }

    @Test
    @DisplayName("an empty or absent collection normalizes to an empty array")
    void absentCollectionYieldsEmptyArray() throws Exception {
        ResponseMapping listMapping = mapping("""
                { "type": "LIST", "itemsPath": "values", "fields": { "name": "name" } }
                """);

        assertThat(normalizer.normalize(jsonResponse("{}"), listMapping, "LIST_REPOSITORIES"))
                .isEmpty();
        assertThat(normalizer.normalize(jsonResponse("{\"values\":[]}"), listMapping, "LIST_REPOSITORIES"))
                .isEmpty();
    }

    @Test
    @DisplayName("a LIST mapping pointed at a non-array fails rather than inventing data")
    void shapeMismatchIsFatal() throws Exception {
        ResponseMapping listMapping = mapping("""
                { "type": "LIST", "itemsPath": "values", "fields": { "name": "name" } }
                """);

        assertThatThrownBy(() -> normalizer.normalize(
                jsonResponse("{\"values\": {\"name\": \"api\"}}"), listMapping, "LIST_REPOSITORIES"))
                .isInstanceOf(ScmException.class)
                .satisfies(thrown -> assertThat(((ScmException) thrown).getErrorCode())
                        .isEqualTo(ScmErrorCode.SCM_RESPONSE_MAPPING_INVALID));
    }

    @Test
    @DisplayName("a mapping with no declared fields passes the object through")
    void emptyFieldsPassesThrough() throws Exception {
        ResponseMapping passthrough = mapping("{ \"type\": \"OBJECT\" }");

        JsonNode normalized = normalizer.normalize(
                jsonResponse("{ \"anything\": 1 }"), passthrough, "GET_REPOSITORY");

        assertThat(normalized.get("anything").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("a malformed numeric field is dropped instead of failing the operation")
    void unparseableIntegerIsDropped() throws Exception {
        ResponseMapping numeric = mapping("""
                { "type": "OBJECT", "fields": { "additions": "lines" }, "transforms": { "additions": "TO_INTEGER" } }
                """);

        JsonNode normalized = normalizer.normalize(
                jsonResponse("{ \"lines\": \"not-a-number\" }"), numeric, "GET_PULL_REQUEST_FILES");

        assertThat(normalized.get("additions").isNull()).isTrue();
    }

    @Test
    @DisplayName("one source path can feed two normalized fields with different transforms")
    void sameSourcePathMappedTwice() throws Exception {
        // Bitbucket uses `id` as both the global identifier and the addressable PR number.
        ResponseMapping mapping = mapping("""
                {
                  "type": "OBJECT",
                  "fields": { "externalId": "id", "number": "id" },
                  "transforms": { "externalId": "TO_STRING", "number": "TO_INTEGER" }
                }
                """);

        JsonNode normalized = normalizer.normalize(jsonResponse("{ \"id\": 42 }"), mapping, "GET_PULL_REQUEST");

        assertThat(normalized.get("externalId").isTextual()).isTrue();
        assertThat(normalized.get("externalId").asText()).isEqualTo("42");
        assertThat(normalized.get("number").isNumber()).isTrue();
        assertThat(normalized.get("number").asInt()).isEqualTo(42);
    }

    @Test
    void stringTransformsAdjustCase() throws Exception {
        ResponseMapping mapping = mapping("""
                {
                  "type": "OBJECT",
                  "fields": { "lower": "value", "upper": "value", "trimmed": "padded" },
                  "transforms": { "lower": "LOWERCASE", "upper": "UPPERCASE", "trimmed": "TRIM" }
                }
                """);

        JsonNode normalized = normalizer.normalize(
                jsonResponse("{ \"value\": \"MiXeD\", \"padded\": \"  spaced  \" }"), mapping, "TEST");

        assertThat(normalized.get("lower").asText()).isEqualTo("mixed");
        assertThat(normalized.get("upper").asText()).isEqualTo("MIXED");
        assertThat(normalized.get("trimmed").asText()).isEqualTo("spaced");
    }
}
