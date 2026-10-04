# Feature: Repository management (Module 3)

A REST layer over the SCM operation engine, exposing repositories, pull requests, changed files and diffs to the application.

This is the first module to **use** the SCM integration rather than manage it. It adds no provider code, no provider-specific HTTP, and no `if (providerCode.equals(...))` anywhere — everything provider-shaped stays behind `ScmClient`, exactly where Module 2 put it.

Read [`../BACKEND.md`](../BACKEND.md) for the conventions this relies on: the `ApiResponse` envelope, `GlobalExceptionHandler`, and the `ScmErrorCode` vocabulary.

---

## State

| Capability                         | State     | Notes                                           |
| ---------------------------------- | --------- | ----------------------------------------------- |
| List repositories                  | Built     | Paged, optional search                          |
| Get repository                     | Built     | Addressed by `owner`/`repo`                     |
| List pull requests                 | Built     | State filtered **at the provider**              |
| Get pull request                   | Built     | Canonical state, merged resolved                |
| Changed files                      | Built     | Paged                                           |
| Diff                               | Built     | Parsed into files, hunks and lines              |
| Ownership and usability enforcement| Built     | One gate, every route                           |
| Bitbucket parity                   | Built     | Same code, configuration differences only       |
| Persistence of repositories or PRs | Not built | **Deliberate** — see [No persistence](#no-persistence) |
| Caching                            | Not built | **Deliberate** — measure before adding          |
| Write operations                   | Not built | Comments, reviews and webhooks are later modules |
| Cross-repository pull-request query| Not built | A PR has no meaning outside its repository      |

---

## Package layout

```
com.kksg.applicationServices.repository
├── RepositoryManagementProperties.java   search and diff bounds
├── controller/
│   ├── RepositoryController.java
│   └── PullRequestController.java
├── dto/
│   ├── RepositoryResponse, RepositoryOwner, RepositoryVisibility
│   ├── RepositoryRefResponse, ScmResourceProvider
│   ├── PullRequestResponse, PullRequestAuthor, PullRequestStateFilter
│   ├── PullRequestFileResponse
│   └── PullRequestDiffResponse, DiffFile, DiffHunk, DiffLine, DiffLineType
├── mapper/
│   ├── RepositoryMapper.java
│   └── PullRequestMapper.java
├── service/
│   ├── ScmResourceAccessService.java     the authorization gate
│   ├── ScmResourceContext.java           proof of authorization, passed down
│   ├── ScmOperationRunner.java           logging, error naming, usage recording
│   ├── ScmPageScanner.java               paging and search, resource-independent
│   ├── RepositoryService.java
│   ├── PullRequestService.java
│   ├── RepositoryRef.java                validated owner/repo
│   └── PageQuery.java                    validated page/size/search
└── diff/
    └── UnifiedDiffParser.java
```

Plus two shared additions outside the module:

- `common/response/PageResponse.java` — the page envelope. In `common` because the shape is a property of this API rather than of any one resource.
- `scm/common/util/ScmInstants.java` — tolerant timestamp parsing for the normalized models, which carry timestamps as text.

---

## Layering

```
controller              resolve principal, validate path, delegate
   ↓
RepositoryService / PullRequestService      application behaviour, response shape
   ↓
ScmPageScanner                              paging and search, resource-independent
   ↓
ScmOperationRunner                          logging, error naming, usage recording
   ↓
ScmClient                                   the SCM operation engine (Module 2)
   ↓
provider configuration                      endpoint, auth, paging, response mapping — DB rows
```

Two of those layers exist to avoid repetition that would otherwise be repetition **of a mistake**:

`ScmOperationRunner` is the single place a provider call is wrapped. A service calling `ScmClient` directly would work; it would also be the fifth place to forget the 404 translation, and then a user asking for a repository they cannot see would get a 502 that reads like an outage.

`ScmPageScanner` holds the two parts of paging that are easy to get subtly wrong — translating between zero-based API pages and one-based provider pages, and deciding `hasNext` when no total exists — so they are written and tested once rather than three times.

**Nothing in the module is transactional across a provider call**, deliberately, for the reason `ConfigDrivenScmClient` documents: a transaction held open across outbound HTTP ties database pool capacity to provider latency. The only work that needs a transaction — resolving and authorizing the connection — takes its own short one inside `ScmResourceAccessService`.

---

## Endpoints

All require a Bearer token and sit under the `/coderev` context path.

| Method | Path | Success |
| --- | --- | --- |
| GET | `/api/v1/scm/connections/{connectionId}/repositories` | 200 — `PageResponse<RepositoryResponse>` |
| GET | `…/repositories/{owner}/{repo}` | 200 — `RepositoryResponse` |
| GET | `…/repositories/{owner}/{repo}/pull-requests` | 200 — `PageResponse<PullRequestResponse>` |
| GET | `…/pull-requests/{pullRequestNumber}` | 200 — `PullRequestResponse` |
| GET | `…/pull-requests/{pullRequestNumber}/files` | 200 — `PageResponse<PullRequestFileResponse>` |
| GET | `…/pull-requests/{pullRequestNumber}/diff` | 200 — `PullRequestDiffResponse` |

Query parameters:

| Name | Where | Notes |
| --- | --- | --- |
| `page` | listings | Zero-based. Negative is a 400. |
| `size` | listings | 1–100. Outside that range is a **400, not a clamp**. |
| `search` | repositories, pull requests | Free text. See [Search](#search). |
| `state` | pull requests | `OPEN` \| `CLOSED` \| `MERGED` \| `ALL`. Defaults to `OPEN`. Unknown is a 400. |

`connectionId` and `pullRequestNumber` are `Integer`, so a non-numeric segment is a 400 rather than a 404.

### Why nested under the connection

A repository has no identity in this product independent of the authorization it is read through: the same name can exist on two providers, and whether it is visible at all depends on whose credential is asking. A top-level `/repositories` route would have needed the connection as a query parameter anyway, while inviting the mistake of treating a repository id as globally unique. The URL states the resolution order the backend actually uses.

### Why `{owner}/{repo}` and not `{repositoryId}`

Every configured provider operation that touches a repository substitutes `{{owner}}` and `{{repo}}` into its endpoint template — that is what the provider APIs accept. Neither provider exposes a "get repository by id" route in its configuration, so an id-keyed route would have had to resolve the id back to a name by walking the repository list: a paged provider call per request, and still wrong the moment a repository is renamed. The owner-qualified name is the provider's real primary key for these calls.

Two path segments rather than one URL-encoded segment because an encoded slash is not reliably passed through by servlet containers and reverse proxies — Tomcat rejects `%2F` by default — and a route that depends on that breaks on deployment rather than in development.

Note also that a provider repository id is **not globally unique across providers**: one provider's numeric ids and another's UUIDs share no namespace. Every reference is resolved in the context of a specific connection, never on the identifier alone.

### Why `pullRequestNumber` and not an id

On at least one provider the user-visible `number` and the global `id` are different integers, and only `number` is accepted in a pull-request URL. `NormalizedPullRequest` already warns that conflating them produces 404s. The number is also the value a user sees and quotes.

---

## Authorization

`ScmResourceAccessService` is the single gate. Everything below it takes a `ScmResourceContext` as a parameter, so there is **no code path that reaches a provider without having been through it** — an unauthorized call cannot be written, because there is no way to obtain a context except from the gate.

```
authenticated user  ──(owns; this service)──────▶  connection
connection          ──(credential scope; provider)──▶  repository
repository          ──(URL path; provider)──────▶  pull request
```

Three checks, in order, and the order matters:

1. **Ownership** — delegated to `ScmConnectionService.requireOwned`, which filters by `userId` *inside the query*. Another user's id is reported as **not found, not forbidden**: a 403 would confirm the id exists and turn the endpoint into an oracle for enumerating other users' connections.
2. **Usability** — a disconnected connection has had its credentials destroyed, so using it cannot work. Reported as `409 SCM_CONNECTION_NOT_ACTIVE` so a client can offer "reconnect" rather than showing an unexplained provider failure. `EXPIRED` is deliberately allowed through, because the token service may still be able to refresh it.
3. **Provider activity** — last, because it is the operator's switch rather than anything about this user.

**Repository and pull-request access are deliberately not checked here.** The platform holds no record of which repositories a connection can see, and inventing one would mean maintaining a stale mirror of the provider's permissions. Instead every resource call is made *with that connection's credential*, so the provider decides: a repository the token cannot see answers 404, which the engine reports as `SCM_PROVIDER_RESOURCE_NOT_FOUND` and the calling service translates to `SCM_REPOSITORY_NOT_FOUND`.

Providers answer 404 rather than 403 for an invisible private resource precisely so its existence is not disclosed. That property is preserved rather than unpicked.

The last link is structural: a pull request is always addressed *within* a repository path, so a number belonging to a different repository resolves to nothing rather than to someone else's pull request.

---

## Pagination

`PageResponse<T>` in `common/response`:

```json
{
  "content": [],
  "page": 0,
  "size": 20,
  "first": true,
  "last": false,
  "hasNext": true
}
```

**`totalElements` and `totalPages` are nullable, and that is the contract.** This envelope is filled from an upstream provider that usually publishes no total — GitHub's repository listing and Bitbucket's diffstat both say only "there is another page". The options were to omit the total or fabricate one, and a fabricated total is worse because a client cannot tell it is wrong: it would render "1–20 of 20" over a list with three more pages. `@JsonInclude(NON_NULL)` drops the keys entirely when unknown.

`last` is always the negation of `hasNext`, so it stays accurate with no total to derive it from. **Clients must drive page controls from `hasNext`.**

`page` is zero-based, matching Spring Data and the query parameter. Providers that page from one are translated at the boundary that talks to them.

### Validated, not clamped

`PageQuery` rejects a size outside 1–100 rather than clamping. A caller asking for 5000 has a mistaken model of what it will receive, and quietly serving 100 leaves it believing it has seen everything — the failure then surfaces as missing data somewhere downstream instead of as a 400 at the boundary.

(Page size is separately clamped *deeper* in the stack, against each provider's own ceiling. That is a different concern: it protects the engine's "did I get a full page?" reasoning and must not reject, since the ceiling is provider configuration the caller cannot know.)

---

## Search

Neither configured provider offers a server-side search on these listings. So `ScmPageScanner` applies the filter over provider pages it fetches in sequence, bounded by configuration:

```yaml
repository:
  search:
    max-pages: 5 # up to 5 provider calls
    page-size: 100 # 100 items each, so up to 500 scanned
```

This is a real compromise, and its edges are worth stating rather than discovering:

- **It can only find what it has looked at.** The listings are ordered most-recently-updated first, so what the bound cuts off is the least recently touched.
- **The absence of `totalElements` is the signal that the result set may be incomplete.** A scan that reached the end of the provider's data knows the exact total and reports it; a scan stopped by the bound reports none. That is the only honest thing a client can be told.
- **`hasNext` in scan mode is computed strictly from matches already collected**, never from "the provider might have more". The scan is deterministic from page one, so claiming a further page on the strength of unscanned data would offer a next page that returns the same empty result forever.

Which fields a search covers is a property of the resource, so the predicate lives in the mapper rather than the scanner:

- **Repositories** — name, full name, description.
- **Pull requests** — title, author, both branch names, and an **exact** `#number` match. The description is excluded on purpose: it is long free text, so including it would make nearly any common word match nearly every pull request. The number match is exact rather than substring because searching "12" should not return #120, #124 and #1234.

The alternatives were both worse. Fetching everything and filtering in memory is what the bound exists to prevent. Filtering only the single page the client asked for would mean typing into a search box on page one of twenty found nothing while a match sat on page three — a filter that appears broken is worse than one with a documented reach.

**Pull-request state is different: it is filtered at the provider.** See below.

---

## Provider differences, resolved in configuration

Two differences could have become a provider branch in Java. Both were pushed into the declarative configuration instead, which is what keeps this module provider-agnostic.

### Request-side value translation

GitHub wants `state=open`/`closed`/`all`. Bitbucket wants `OPEN`/`MERGED`/`DECLINED` and expresses "any state" as **repeated** parameters rather than a single token. Without translation, a caller filtering pull requests would have to know which provider it was talking to — the exact branch Module 2 exists to remove.

`RequestConfiguration` therefore gained two declarative fields, the request-side mirror of the existing `response_mapping.valueMappings`:

```json
{
  "parameterValueMappings": {
    "state": {
      "OPEN": "OPEN",
      "CLOSED": "DECLINED|SUPERSEDED",
      "MERGED": "MERGED",
      "ALL": "OPEN|MERGED|DECLINED|SUPERSEDED"
    }
  },
  "multiValueQueryParams": ["state"]
}
```

`ScmRequestBuilder` applies the mapping before anything reads the parameters, so a normalized `state=OPEN` is already the provider's spelling by the time it reaches a query parameter, a path segment or a body template. A value with no entry passes through unchanged, so a provider needing no translation declares nothing; a value mapped to the empty string is **removed**, which is how a provider declares "this filter does not apply to me".

`multiValueQueryParams` is **opt-in per parameter**, split on `|`. Applying the split to every value would mean a search term containing a vertical bar silently became several unrelated filters.

### Resolving "merged"

Providers disagree on whether merged is a *state* or an *event*. Some report `state=MERGED`; others report `state=closed` and record the merge separately — so without resolution a merged pull request is indistinguishable from an abandoned one, and a user filtering by "merged" would be shown results labelled "closed".

The declarative mapping cannot express "closed plus a merge timestamp means merged" — it has no conditionals, and that is a property worth keeping. So `NormalizedPullRequest` gained a factual `mergedAt` field, and `PullRequestMapper` holds the single derivation:

```java
if (reported == CLOSED && mergedAt != null) return MERGED;
```

Expressed over **normalized facts**, not over a provider code, which is what makes it safe for a provider nobody has added yet: one that already says `MERGED` has no merge timestamp mapped and falls through unchanged, and one that says `OPEN` is never reinterpreted.

**One asymmetry remains.** On GitHub, filtering by `MERGED` maps to `closed` at the provider, so the list may include closed-but-unmerged pull requests. The individual states returned are still correct, because they are resolved from `mergedAt`.

---

## Diff parsing

`UnifiedDiffParser` turns the provider's `text/plain` response into files, hunks and lines with both sets of line numbers resolved.

**Why parse server-side at all.** Both providers return the same format — git's unified diff — because both are git. So the parse is provider-independent, which makes it exactly the kind of work that belongs on this side of the boundary: done once here, or reimplemented in every client. It also puts the line-numbering arithmetic, where off-by-one bugs live, in one tested place, and puts the size limit where the memory is.

`GET_PULL_REQUEST_DIFF` is the one operation whose response mapping is `TEXT`, so the payload arrives on `ScmOperationResponse.getRawText()`. Reading `rawText` is correct here and is not the leak of provider shape that reading `rawBody` would be — unified diff is a format both providers emit identically, not a provider-specific document.

What it reads, and what it ignores:

```
diff --git a/path b/path            file boundary (fallback for paths; see below)
new file mode / deleted file mode   change type
rename from / rename to             change type and previous path
--- a/path  /  +++ b/path           authoritative paths, /dev/null for add or delete
Binary files ... differ             no text diff to show
@@ -a,b +c,d @@ section             hunk bounds
```

Mode changes, blob indexes and similarity scores say nothing about what changed in the file, so they are skipped rather than modelled.

**Paths come from the `---`/`+++` lines in preference to `diff --git`.** That line concatenates both paths separated by a space, so a filename containing a space makes it genuinely ambiguous; the two single-path lines never are. `diff --git` is still read first, as a fallback for the rare entry with no `---`/`+++` pair.

**Tolerant by design.** An unrecognised line is skipped, not rejected. A diff is decoration around a pull request rather than a transaction: half a rendered diff is useful, and a parse exception that blanked the page because a provider emitted an unexpected header would not be. The one thing it will not do is drop content silently — see `truncated`.

Combined diffs (`@@@`, produced for merge-commit comparisons) are **not** parsed. They have a different column structure, neither provider returns one for a pull-request diff, and guessing would produce plausible-looking wrong line numbers.

### Bounds

```yaml
repository:
  diff:
    max-lines: 20000
    max-files: 300
```

These bound the **parsed structure**, not the download — `scm.http.max-response-bytes` already caps that. The parsed form is several times the size of the text it came from and is what gets serialised to a client.

Exceeding either marks `truncated` on the response, and on the individual file where the lines ran out. Announced rather than hidden because a viewer showing 40 of 900 changed files without saying so would read as a small pull request.

`binary` and `truncated` both mean "no hunks here", for different reasons, and are reported separately: a binary file has no textual diff and never will, while a truncated one has one that was too large to include.

---

## Error handling

The existing `ScmErrorCode` vocabulary is reused — no second error mechanism. Five constants were **added** to it:

| Code | Status | Meaning |
| --- | --- | --- |
| `SCM_CONNECTION_NOT_ACTIVE` | 409 | Owned by the caller but unusable. Distinct from `SCM_CONNECTION_EXPIRED` (401), which is an authentication problem the client fixes by reauthorizing; this is a state conflict. |
| `SCM_REQUEST_INVALID` | 400 | A caller value failed validation. Distinct from `SCM_OPERATION_PARAMETER_MISSING`, which means a *provider's* operation needs a parameter the caller did not supply. |
| `SCM_PROVIDER_RESOURCE_NOT_FOUND` | 404 | The provider answered 404/410. Engine-level and generic. |
| `SCM_REPOSITORY_NOT_FOUND` | 404 | Translated from the above by the calling service. |
| `SCM_PULL_REQUEST_NOT_FOUND` | 404 | Likewise. |

### Why 404 is translated by the caller

`ConfigDrivenScmClient.classifyFailure` now maps provider 404/410 to `SCM_PROVIDER_RESOURCE_NOT_FOUND` instead of folding it into `SCM_PROVIDER_API_ERROR`. The two deserve opposite treatment: one is a 404 the user caused by asking for a repository they cannot see, the other is a 502 worth paging someone about.

But the engine cannot know *which* resource is missing — only the caller knows whether the request addressed a repository or a pull request. So `ScmOperationRunner` takes the code to report:

- `GET_REPOSITORY` → `SCM_REPOSITORY_NOT_FOUND`
- `LIST_PULL_REQUESTS` → `SCM_REPOSITORY_NOT_FOUND` (there is no pull request in the request yet)
- `GET_PULL_REQUEST`, `/files`, `/diff` → `SCM_PULL_REQUEST_NOT_FOUND`
- `LIST_REPOSITORIES` → untranslated; there is no repository in the request

Everything else propagates untouched. Rate limiting (429), authentication failure (401) and unsupported operations (400) are already named precisely and each calls for a different client reaction, so flattening them would destroy information.

A 2xx that normalizes to nothing is reported as `SCM_RESPONSE_MAPPING_INVALID`, **not** as not-found: reporting a defective `response_mapping` as "not found" would send an operator looking at permissions for a configuration fault.

### Rate limits

No rate-limit management system, deliberately. But `SCM_PROVIDER_RATE_LIMITED` keeps its own 429 status all the way to the client, so a UI can say "try again in a few minutes" rather than "500 Internal Server Error".

---

## No persistence

**No `repositories`, `pull_requests` or `pull_request_files` tables, and no new columns anywhere.**

Repositories and pull requests are provider resources, not application records. A mirror would be stale the moment it was written, and would have to answer questions this module does not need to ask — what happens on rename, on transfer, on access being revoked. Indexing is a later feature with its own requirements.

The one piece of state that *is* written is operational: `ScmConnectionService.markUsed` records `lastUsedAt` after a successful provider call, which finally gives that existing column a value. It is best-effort and guarded twice — once inside the method and once at the call site in `ScmOperationRunner`, because the method is `REQUIRES_NEW` and its commit happens in the proxy *after* the body returns, so a commit failure escapes the inner guard. The field is operational curiosity; it must not be able to turn a successful read into a 500.

The search scan records usage on its first call only. "Last used" is a per-request fact, so writing it per provider page would be five identical updates in five transactions for one keystroke in a filter box.

---

## Observability

`ScmOperationRunner` logs one line per call at INFO, with the identifiers needed to reconstruct what happened: `userId`, `connectionId`, `providerCode`, `operation`, the resource addressed, `durationMs`, and the outcome. The engine logs the provider-facing half of the same call (method, URI, status), so together they cover both sides. `ScmPageScanner` logs a `REPO_SEARCH_SCAN` line with pages scanned, items scanned, matches found and whether it reached the end.

**Request parameters are logged through a deliberate allow-list** — `owner`, `repo`, `pullRequestNumber` — rather than wholesale. The parameter map is the same channel that carries `webhookSecret` for write operations, so logging it generically would put a webhook secret in the application log the first time this runner was reused for one. An allow-list protects against that; a deny-list protects only the secrets someone remembered.

Never logged: access tokens, refresh tokens, client secrets, OAuth secrets, encrypted credential values. No response carries a token, a token reference or any provider configuration either.

---

## Configuration

```yaml
repository:
  search:
    max-pages: ${REPOSITORY_SEARCH_MAX_PAGES:5}
    page-size: ${REPOSITORY_SEARCH_PAGE_SIZE:100}
  diff:
    max-lines: ${REPOSITORY_DIFF_MAX_LINES:20000}
    max-files: ${REPOSITORY_DIFF_MAX_FILES:300}
```

Every value bounds work that is otherwise driven by how large a user's account happens to be. They are starting points rather than tuned figures. **No caching sits in front of any of this**, deliberately — measuring real usage and rate-limit pressure comes before adding one.

Also changed: `springdoc.packages-to-scan` gained `com.kksg.applicationServices.repository.controller`, and `logging.level` gained the module at INFO.

---

## Seed data changes

Both `resources/scm/seed/{github,bitbucket}.json` were extended. The seeder reconciles `seed_managed` rows on every startup, so these apply without a migration.

| Change | Why |
| --- | --- |
| `ownerExternalId`, `ownerAvatarUrl` on repository mappings | The UI shows an owner avatar; the fields are normalized rather than invented in the DTO |
| `updatedAt` on repository mappings | "Last updated" is how a repository list is made scannable |
| `mergedAt` on pull-request mappings (GitHub only) | Resolving merged state; Bitbucket reports `MERGED` directly and maps nothing here |
| `parameterValueMappings` for `state` | Canonical → provider state translation |
| `multiValueQueryParams: ["state"]` (Bitbucket) | "Any state" is repeated parameters there |
| `sort`/`direction` on pull-request listings | Most-recently-updated first, which is what makes the bounded search lose the right end |

---

## Tests

**181 tests across 14 classes** — 174 in this module's own 13 classes, plus 7 in `ScmRequestBuilderValueMappingTest` for the two new declarative config features. The whole suite, this module plus the existing SCM module, is **245 passing**:

```bash
./mvnw test -Dtest='com.kksg.applicationServices.repository.**,com.kksg.applicationServices.scm.**' \
  -DfailIfNoSpecifiedTests=false
```

| Class | Covers |
| --- | --- |
| `ScmResourceAccessServiceTest` | **The security test.** User A cannot reach User B's connection and is told it does not exist; disconnected is 409 not 404; inactive provider stops the request |
| `RepositoryServiceTest` | List, search, detail, and the whole error matrix — unauthorized, inactive, unsupported, provider error, rate limited, expired — asserting that no provider call is attempted when the gate refuses |
| `PullRequestServiceTest` | List with the state filter **reaching the provider as a parameter**, detail, files, diff, invalid number, and which resource a 404 names |
| `ScmPageScannerTest` | Page translation, `hasNext` from provider state, bounded scan semantics, what `totalElements` means |
| `ScmOperationRunnerTest` | 404 renaming per caller context, every other failure propagating intact, usage recording that cannot fail a read |
| `UnifiedDiffParserTest` | Line numbering across a mixed hunk, add/delete/rename/binary detection, truncation announced, CRLF, no-newline marker, empty input |
| `RepositoryControllerTest`, `PullRequestControllerTest` | Routing, parameter binding, response envelope, and every `ScmErrorCode` → HTTP status mapping |
| `RepositoryMapperTest`, `PullRequestMapperTest` | Field mapping, visibility, **merged-state derivation**, tolerant timestamp parsing, search predicates |
| `RepositoryRefTest`, `PageQueryTest`, `PullRequestStateFilterTest` | Input validation and its rejections |
| `ScmRequestBuilderValueMappingTest` | The two new declarative config features |

Controller tests use `MockMvcBuilders.standaloneSetup` with `GlobalExceptionHandler` registered explicitly, rather than `@WebMvcTest`. These assertions are about request mapping and serialisation, neither of which needs a Spring context or a datasource — and a full context would pull in Testcontainers, turning a millisecond test into a minute.

### Two pre-existing failures, fixed

Both were failing on a clean checkout before this work (verified by stashing). Both were in the path this module depends on, so both were fixed:

1. **`ScmPaginationResolver` ignored a declared `nextPath`.** The full-page heuristic ran even when a provider's configuration declared an authoritative next-page field, so Bitbucket's *last* page reported `hasNext: true` — a client would offer a Next button leading to an empty list. The heuristic is now skipped when `nextPath` is declared. A provider using `Link` headers gets no such declaration, so for those the absence of a header is genuinely ambiguous and the heuristic still applies.

2. **`ScmRequestBuilder` double-encoded path values.** `encodeForPath` pre-encodes each segment, then `.encode()` re-encoded the escapes: a value containing a slash arrived as `%2F` and left as `%252F`, asking the provider for a repository whose name literally contains "%2F". Now `build(true)` is used, with query values encoded explicitly through the same component rules `encode()` would have applied. The security property — a value cannot introduce a path segment — held either way and still holds.

---

## Known gaps

- **No caching.** Every request calls the provider, so a provider's rate limit is the practical ceiling. Deliberate for a first implementation.
- **Search reach is bounded** at 500 items by default; a match in an older repository is not found. Signalled by the absent total rather than hidden.
- **`MERGED` on GitHub is approximate as a filter** — it maps to `closed`, so the list may include closed-but-unmerged pull requests. Individual states are correct.
- **No write operations.** `CREATE_PR_COMMENT`, `CREATE_PR_REVIEW`, `CREATE_WEBHOOK` and `DELETE_WEBHOOK` remain reachable only internally.
- **No cross-repository pull-request query.** A pull request has no meaning outside its repository, and no provider operation lists them across repositories.
- **Diff is capped** at 20,000 lines and 300 files; beyond that the response is `truncated`.
- **Combined diffs are not parsed.** Merge-commit comparisons would need a different column model.
- **No integration test against a real provider.** The module is covered by unit tests with the engine mocked; the live path is exercised by the frontend's Playwright suite, which runs against a real connection when one exists.
- **`RequestConfiguration` gained two record components**, which is a source-breaking change for anyone calling its canonical constructor. Only a test did; it was updated.
