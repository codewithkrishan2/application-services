# CodeRev — Backend

Spring Boot 3.3.2 / Java 21 / PostgreSQL. Packaged as a WAR (`ServletInitializer` is present), run in development with `./mvnw spring-boot:run -Dspring-boot.run.profiles=local`.

Three feature modules are built: **Identity** (OAuth sign-in and sessions), **SCM** (source-control provider integration) and **Repository management** (repositories, pull requests, files and diffs over the SCM engine). Everything else — review orchestration, AI analysis, repository indexing, billing — does not exist yet.

```
com.kksg.applicationServices
├── identity/     37 files — sign-in, JWT sessions, user profile
├── scm/          93 files — providers, connections, webhooks, outbound operation engine
├── repository/   28 files — repository and pull-request REST layer, diff parsing
├── common/        7 files — ApiResponse and PageResponse envelopes, exceptions, GlobalExceptionHandler
└── config/                 OpenAPI, ModelMapper, Cloudinary (last two unused)
```

Repository management has its own document — [`features/repository-management.md`](features/repository-management.md) — because it was written after this one and because new features get their own file. See [`README.md`](README.md) for how these documents are organised.

---

## Contents

- [Conventions](#conventions)
- [Feature status](#feature-status)
- [Identity module](#identity-module)
- [SCM module](#scm-module)
- [Repository management module](#repository-management-module)
- [Data model](#data-model)
- [Security](#security)
- [Configuration](#configuration)
- [Testing](#testing)
- [Known gaps](#known-gaps)

---

## Conventions

**Base URL.** Every endpoint sits under the servlet context path: `http://localhost:8080/coderev`. The container strips it before Spring routes, which is why no controller mapping, security matcher or springdoc pattern mentions it. Clients must include it.

**Response envelope.** Every JSON response is wrapped in `ApiResponse` (`common/response/ApiResponse.java`):

```json
{ "status": "SUCCESS", "message": "...", "data": { }, "errors": { } }
```

It is annotated `@JsonInclude(NON_NULL)`, so **absent keys are the norm**: success responses carry no `errors`, failures carry no `data`, and `GET /users/me` carries no `message`. Only `status` is guaranteed, and it is a plain string — `"SUCCESS"` or `"FAILED"`.

**Error handling.** `GlobalExceptionHandler` (`@RestControllerAdvice`) is the single source of error shapes:

| Exception | Status | Body |
|---|---|---|
| `ResourceNotFoundException` | 404 | `error(message)` |
| `MethodArgumentNotValidException` | 400 | `error("Validation failed", {field: message})` |
| `ApiException` | **409** | `error(message)` |
| `ScmException` | per `ScmErrorCode` | `error(message, {"code": "SCM_..."})`, plus `errors.retryAfterSeconds` and a `Retry-After` header when the provider supplied one |
| `MethodArgumentTypeMismatchException` | 400 | `error("Invalid parameter type")` |
| `AccessDeniedException` | 403 | `error("Access denied")` |
| `BadCredentialsException` | 401 | `error("Invalid email or password")` — unreachable, nothing throws it |
| `Exception` | 500 | `error("An unexpected error occurred")` |

Two consequences worth knowing. `AuthController` catches `ApiException` **locally** to return 401 rather than the advice's 409 — so a rejected refresh token is a 401. And because `@ExceptionHandler(Exception.class)` resolves before Spring's `DefaultHandlerExceptionResolver`, malformed JSON and wrong HTTP methods surface as **500**, not the conventional 400/405.

Clients should branch on the HTTP status and, for SCM calls, on `errors.code` — never on `message` text, which is human-facing wording.

---

## Feature status

| Capability | State | Notes |
|---|---|---|
| GitHub OAuth sign-in | Built | `read:user user:email` |
| Bitbucket OAuth sign-in | Built | Scopes come from the consumer registration |
| GitLab / Azure DevOps sign-in | Not built | Enum values exist, no provider bean or route |
| Password login / registration | Not built | No password column anywhere |
| JWT access tokens | Built | HS512, 15 min, no roles |
| Refresh tokens with rotation + reuse detection | Built | Opaque, SHA-256 at rest, 30 days |
| Read / update own profile | Built | `fullName` is the only editable field |
| User administration | Not built | No list, delete, deactivate or admin API |
| Email verification flow | Not built | Flag is copied from the provider, never verified here |
| Roles / permissions | Not built | Authentication carries zero authorities |
| SCM provider catalogue | Built | DB-seeded, GitHub + Bitbucket |
| SCM connect via OAuth (+ reconnect) | Built | Upsert keyed `(user, provider, account)` |
| SCM disconnect | Built | Idempotent, destroys credentials |
| Encrypted credential storage | Built | AES/GCM, key-versioned |
| Webhook ingestion with HMAC verification | Built | Stores deliveries; no consumer yet |
| Repository listing, search and detail | Built | Paged; nested under a connection |
| Pull-request listing, detail, files and diff | Built | State filtered at the provider; diff parsed server-side |
| Repository / pull-request persistence | Not built | **Deliberate** — they are provider resources, not records |
| Caching of repository or PR data | Not built | **Deliberate** — measure before adding |
| SCM write operations (comment, review, webhook create) | Not built | Operations are configured but reachable only internally |
| Token refresh for SCM connections | Not built | Declared per provider, no scheduler calls it |

---

## Identity module

### Sign-in flow

Sign-in is OAuth-only. Orchestration is provider-agnostic in `OAuthLoginService`; providers are injected as a `List<OAuthLoginProvider>` into an `EnumMap`, and a duplicate registration fails startup.

```
browser → GET /coderev/api/v1/oauth/{github|bitbucket}/auth
            302 → provider consent screen
          provider → GET /coderev/api/v1/oauth/{provider}/callback?code=&state=
            302 → {FRONTEND_URL}/oauth-success?access_token=…&refresh_token=…
                  or  {FRONTEND_URL}/oauth-error?message=…
```

All four OAuth routes are `permitAll`, return `void`, and **always answer 302** — success and failure alike. `OAuthLoginService` is total and never throws, so no `GlobalExceptionHandler` status is reachable on them. Callback params (`code`, `state`, `error`) are all `required = false`, so a missing one never produces a 400.

Credentials are validated *before* the browser leaves: a blank client id or secret fails with `"GitHub sign-in is not configured: missing client ID"` and an immediate error redirect.

**Callback order of operations:** reject unknown provider → `error` param present means denied → blank `code` rejected → verify `state` → exchange code → fetch profile → provision user → redirect with tokens.

**State is generated and validated.** `OAuthLoginStateService` issues a signed JWT (`issuer=identity-oauth-login-state`, `subject=provider.name()`, 12 random bytes as `nonce`, 10-minute expiry) signed with the *application's* JWT secret, kept distinct from access tokens only by the issuer claim. Being stateless it cannot be marked consumed, so **it is replayable within its 10-minute window** — a limitation acknowledged in the class javadoc and bounded by the provider's single-use code. There is no PKCE and no OIDC nonce.

**Token exchange** uses a dedicated `RestTemplate` with redirects disabled (connect 5 s, read 20 s). Error bodies are parsed even on 4xx, because GitHub reports a bad code as `200` with an error document while Bitbucket uses `400`. GitHub authenticates with credentials in the form body; Bitbucket uses HTTP Basic.

**Profile and email.** GitHub: `GET /user`, then `GET /user/emails` picking the first `primary && verified`, else the first `verified`. Bitbucket: `GET /2.0/user` (uuid, braced), then `GET /2.0/user/emails` reading `values[]` — **first page only** — preferring `is_primary && is_confirmed`. Either provider failing to yield a confirmed address aborts sign-in. Both then hard-code `emailVerified = true`, justified because only confirmed addresses reach that point.

**Provisioning** (`OAuthUserProvisioningService`, `@Transactional`, deliberately a separate bean so the transaction excludes the outbound HTTP calls):

- The matching key is the **email address**, not the provider id (`userRepository.findByEmail`). Signing in with Bitbucket using an address already registered via GitHub therefore attaches a second `UserLogin` to the same `User`. That is intended account linking.
- For an existing user only `profilePicture` is backfilled, and only when currently null. `fullName`, `email`, `emailVerified` and `status` are left alone.
- `UserLogin` is upserted on `(user, provider)`, setting `providerUserId` and the provider grant's `expiresAt` (null for GitHub, ~2 h for Bitbucket).
- **The provider's own access and refresh tokens are not persisted.** The columns were removed rather than encrypted, so sign-in grants nothing reusable. SCM connections store their own credentials separately — see below.

### Sessions

**Access token.** `JwtTokenProvider` pins HS512 on both the signing and verification side, and additionally asserts the header `alg` after verification so a token signed HS256 with the same key cannot be presented. Claims are `iss=coderev-identity`, `sub=<numeric user id>`, `email`, `iat`, `exp`. No roles, scopes, `jti` or audience. Lifetime 900 s.

Startup fails loudly on a bad key: missing secret, non-Base64, or fewer than 64 decoded bytes each throw `IllegalStateException` with a message naming the fix. `JwtProperties.getSecret()` strips all whitespace, so a wrapped `openssl rand -base64 64` works verbatim.

**Refresh token.** An opaque 32-byte `SecureRandom` value, base64url, **not a JWT**. Only its SHA-256 hex digest is stored, in `user_logins.app_refresh_token` (the column keeps its old name on purpose — renaming under `ddl-auto: update` would leave the plaintext column behind). SHA-256 rather than bcrypt because the value already carries 256 bits of entropy and this keeps lookup a single indexed query.

Rotation on every sign-in and every refresh: the current digest moves to `app_refresh_token_previous_hash`, a new one is issued, and expiry resets to 30 days — so a continuously refreshing session never ages out.

Reuse detection: a digest that matches *previous* rather than current logs `REFRESH_TOKEN_REUSE_DETECTED` and revokes **every** session for that user before returning 401.

Three caveats:

- **Concurrent refresh is unprotected.** No `@Version`, no row lock. Two simultaneous refreshes both validate and both rotate; last write wins. The loser's token matches neither column, so its next refresh is a plain 401 without triggering revoke-all. Clients must serialise refreshes.
- **One refresh token per `(user, provider)`.** The digest lives on `UserLogin`, which is unique per user and provider, so signing in on a second device silently rotates the first device's token away — and when that device refreshes, reuse detection revokes everything. There is no per-device session table.
- **Access tokens are not denylisted.** After logout the current access token stays valid until it expires (up to 15 min). The per-request status check only catches deactivated accounts.

### Endpoints

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/api/v1/health` | public | Liveness. Always 200, `data: "UP"` |
| GET | `/api/v1/oauth/github/auth` | public | 302 to GitHub consent |
| GET | `/api/v1/oauth/github/callback` | public | 302 to frontend |
| GET | `/api/v1/oauth/bitbucket/auth` | public | 302 to Bitbucket consent |
| GET | `/api/v1/oauth/bitbucket/callback` | public | 302 to frontend |
| POST | `/api/v1/auth/refresh` | public | Rotate the token pair |
| POST | `/api/v1/auth/logout` | public | Revoke a refresh token |
| GET | `/api/v1/users/me` | Bearer | Current profile |
| PATCH | `/api/v1/users/me` | Bearer | Update `fullName` |

`POST /api/v1/auth/refresh` — body `{ "refreshToken": "..." }` (`@NotBlank`). Returns `AuthResponse`: `accessToken`, `refreshToken`, `tokenType` (always `"Bearer"`), `expiresIn` (seconds, primitive). **401** for every rejection, with the reason in `message`: `Invalid refresh token`, `Refresh token has been revoked`, `Refresh token has expired`, `User account is not active`. 400 on a blank field, 500 otherwise.

`POST /api/v1/auth/logout` — body `{ "refreshToken": "..." }`. Always 200, including for an unknown or already-revoked token, so it is not an existence oracle. The response has no `data` key.

`GET|PATCH /api/v1/users/me` — returns `UserResponse`: `id`, `email`, `fullName` (nullable), `profilePicture` (nullable), `status` (`"ACTIVE"`/`"INACTIVE"`), `emailVerified`, `createdAt` (ISO-8601). PATCH accepts only `fullName`, constrained `@Size(max = 100)` and **not** `@NotBlank` — so `{}` is a no-op but `{"fullName": ""}` writes an empty name. The principal is already the entity, so neither handler performs a lookup.

---

## SCM module

Source-control integration is **configuration-driven**: a provider's API base URL, OAuth endpoints, scopes, capabilities, operations and webhook settings live in `scm_providers.configuration` as JSON, reconciled at startup from `src/main/resources/scm/seed/*.json`. Adding a provider is a seed file, not a code change — which is why `providerCode` should be treated as an open string by clients. `ScmAdapterRegistry` logs `no provider adapters registered; all providers are served by database configuration`.

Currently seeded: **GITHUB** (12 capabilities, 11 operations) and **BITBUCKET** (12 capabilities, 10 operations).

### Connect flow

```
frontend → GET /api/v1/scm/connections/authorize?providerCode=GITHUB   (Bearer)
             200 JSON { authorizationUrl, providerCode, state }
browser  → authorizationUrl                                (client-owned navigation)
provider → GET /api/v1/scm/connections/callback/{providerCode}?code=&state=
             302 → {FRONTEND_URL}/scm/connection-result?provider=…&status=…
```

The authorize endpoint deliberately answers **JSON rather than a 302**, because `fetch` cannot usefully follow a cross-origin redirect to an interactive consent screen — the client has to own the navigation.

`status` is exactly one of three literals:

| `status` | Meaning |
|---|---|
| `success` | Credentials stored, connection live |
| `denied` | User declined consent (`error` param was present) |
| `failed` | A `ScmException` or unexpected failure |

No token, code or diagnostic message is ever placed in that URL; detail stays in the backend log. **The redirect is the completion signal** — there is no polling endpoint.

After exchanging the code, the service calls the provider's `GET_CURRENT_ACCOUNT` operation to discover the external account id, then upserts on `(userId, providerId, externalAccountId)`. Re-running the flow therefore **renews credentials rather than creating duplicates**, and a `DataIntegrityViolationException` branch handles two callbacks arriving concurrently by re-reading and updating instead of failing the user.

`state` is a signed JWT (`issuer=scm-oauth-state`, `subject=userId`, claims `providerId` and `nonce`, 10-minute expiry) signed with the application's JWT secret. Same replay caveat as sign-in state.

A second path exists — `POST /api/v1/scm/connections` with a client-captured `code` — which performs **no state verification**, because identity comes from the verified bearer token instead. It is unused: the configured provider redirect URIs point at the backend's own callback.

### Endpoints

| Method | Path | Auth | Success |
|---|---|---|---|
| GET | `/api/v1/scm/providers` | Bearer | 200 — active providers, `displayOrder` then name |
| GET | `/api/v1/scm/providers/{providerId}` | Bearer | 200 — full detail, resolves regardless of `active` |
| GET | `/api/v1/scm/connections` | Bearer | 200 — caller's connections, newest first |
| GET | `/api/v1/scm/connections/authorize?providerCode=` | Bearer | 200 — consent URL |
| POST | `/api/v1/scm/connections` | Bearer | **201** |
| GET | `/api/v1/scm/connections/{connectionId}` | Bearer | 200 |
| DELETE | `/api/v1/scm/connections/{connectionId}` | Bearer | 200, idempotent |
| GET | `/api/v1/scm/connections/callback/{providerCode}` | public | 302 |
| POST | `/api/v1/scm/webhooks/{providerCode}` | HMAC | 200 |

**None of _these_ endpoints paginate.** Both collection endpoints return a bare array in `data`, ordered server-side. (`ScmPagination` drives *outbound* paging against provider APIs and is not exposed directly.) The repository-management endpoints nested beneath `/scm/connections/{connectionId}` do paginate, with a `PageResponse` envelope — see [that module](features/repository-management.md#pagination).

`providerId` and `connectionId` are `Integer` — a non-numeric segment is a 400, not a 404.

**Ownership is enforced in the service layer**, so another user's connection id answers `404 SCM_CONNECTION_NOT_FOUND` rather than 403; the backend cannot distinguish "not yours" from "not there" without leaking the difference.

`GET /scm/connections` returns **every** row including `DISCONNECTED` ones — the backing query has no status filter, because disconnected rows are retained for history. Clients that want only live connections must filter.

### Response shapes

`ScmProviderResponse` — `id`, `providerCode`, `providerName`, `providerType` (`"CLOUD"`/`"SELF_HOSTED"`, nullable), `active`, `displayOrder`.

`ScmProviderDetailResponse` — the above plus `apiBaseUrl`, `oauthScopes[]`, `supportedCapabilities[]`, `unsupportedCapabilities[]`, `configuredOperations[]`. Deliberately excludes raw provider config, credential property names and webhook signature settings.

`ScmConnectionResponse` — `id`, `providerId`, `providerCode`, `providerName`, `externalAccountId`, `externalAccountName`, `connectionStatus`, `tokenExpiry` (nullable — null means the token does not expire), `connectedAt`, `lastUsedAt` (nullable), `displayName` (nullable), `avatarUrl` (nullable), plus the three readiness fields described under [credential lifecycle](#credential-lifecycle): `readiness`, `usable`, `reauthorizationRequired`. **Never carries a token or token reference**; the mapper has no access to the credential store.

Clients should branch on `usable` and `reauthorizationRequired` rather than on `connectionStatus`. The raw status cannot answer "can I use this now" on its own — `EXPIRED` is usable when the credential can be refreshed silently and not usable when it cannot, and reading it directly is what previously made the UI ask for consent the backend did not need.

`ScmAuthorizationUrlResponse` — `authorizationUrl`, `providerCode`, `state`.

### Credential storage

`EncryptedDatabaseSecretStore` holds SCM credentials as `AES/GCM/NoPadding` ciphertext in `scm_secrets`, with a `keyVersion` to allow rotation. The key is `scm.secrets.encryption-key` (Base64, 256-bit). The built-in development default logs `SCM_SECRET_STORE_INSECURE_KEY: ... Acceptable only because a development profile is active` and **must** be overridden in any deployed environment.

Provider client ids and secrets are not stored in the database at all. `scm_providers.configuration` stores *pointers* — `"clientSecretProperty": "scm.providers.github.client-secret"` — which `ProviderCredentialResolver` dereferences against the Spring `Environment`. Two consequences, and they are the reason for the indirection: a database backup or an admin API that returns provider configuration cannot leak a client secret, and each environment points at its own OAuth application with no per-environment database difference. Error messages name only the missing *property*, never a value.

### Credential lifecycle

Tokens are renewed in two places, and both go through `ScmTokenRefresher.refresh`, which holds a `SELECT … FOR UPDATE` row lock and re-checks expiry *after* acquiring it:

- **On demand** — `ScmTokenService` finds a credential within 60 s of expiry while serving a request and renews it inline.
- **Ahead of time** — `ScmTokenRefreshScheduler` sweeps every `scm.token-refresh.interval` for credentials expiring inside `scm.token-refresh.lead-time`, in bounded batches.

The sweep exists because on-demand renewal is the last possible moment: the user pays the token-endpoint latency inside their own request, and a failure there has no slack — that request fails and so does every one after it. A failure found by the sweep is simply retried on the next tick. It is not a second implementation; it calls the same locking refresher, which is what keeps the sweep and a concurrent user request from both redeeming the same refresh token. Whether a provider can refresh at all is read from `oauth.supportsRefresh`, never from a provider name, so GitHub's non-expiring tokens need no special case — they have a null expiry and the query never returns them.

**Refresh failures are classified terminal or transient**, and the distinction is the difference between a connection the user is asked to fix and one that quietly hammers the provider:

| Outcome | Trigger | Connection becomes | Error raised |
|---|---|---|---|
| Terminal | RFC 6749 §5.2 rejection code (`invalid_grant`, `invalid_client`, `unauthorized_client`, `unsupported_grant_type`, `invalid_scope`), or HTTP 400/401 from the token endpoint | `REVOKED` | `SCM_CONNECTION_REVOKED` |
| Transient | 5xx, timeout, rate limit, unrecognised error code | *unchanged* | `SCM_CONNECTION_EXPIRED` |

Terminal failures set `REVOKED` specifically because `ScmConnection.isUsable()` rejects it. That is what stops the loop: before this split, every failure set `EXPIRED`, which `isUsable()` *accepts*, so a dead refresh token was re-exchanged on every single API call indefinitely and the user was never told to reconnect. Transient failures leave the status alone so a later attempt can win — revoking a connection because the provider had a bad minute would send the user through consent for nothing. `findDueForRefresh` deliberately excludes `REVOKED`, so a terminal failure is never swept again.

**Readiness** (`ScmConnectionReadiness`) is the single derived answer to "can this be used right now", resolved by `ScmConnectionReadinessResolver` and surfaced on every connection response. It is derived rather than stored because it depends on the current time:

| `readiness` | `usable` | `reauthorizationRequired` | Meaning |
|---|---|---|---|
| `READY` | true | false | Valid, or a provider that issues non-expiring tokens |
| `EXPIRING` | true | false | Within 15 minutes of expiry; the sweep is already about to renew it |
| `REFRESHABLE` | true | false | Expired, but renewable with no user involvement |
| `REAUTHORIZATION_REQUIRED` | false | true | Needs fresh consent |
| `DISCONNECTED` | false | false | Removed locally; do not prompt |
| `ERROR` | false | false | Held aside after repeated failures |

`REFRESHABLE` requires two independent facts: the provider declares `oauth.supportsRefresh`, **and** this particular connection holds a refresh credential. A provider that supports refresh is no help to a grant that never received a refresh token. The 15-minute `EXPIRING` window matches the sweep's default lead time on purpose — a shorter one would report "expiring" for connections already renewed.

### Rate limits and retries

`ScmHttpExecutor` retries a request at most `scm.http.max-retries` times, with a doubling backoff from `scm.http.retry-backoff-ms`, and only when **both** conditions hold:

- the status is 502, 503 or 504, or the call failed at the transport layer (no status at all);
- the method is idempotent — `GET`, `HEAD` or `DELETE`.

`POST`, `PUT` and `PATCH` are never retried: a lost *response* is not a lost *request*, and replaying a token exchange can destroy a working credential. **429 is never retried inline** either — retrying a rate limit is how one becomes an outage. It is surfaced instead as `SCM_PROVIDER_RATE_LIMITED` carrying the provider's own wait hint, which reaches the client as both `errors.retryAfterSeconds` and a `Retry-After` header. Only the delta-seconds form of `Retry-After` is read (an HTTP date is only as good as two clocks agreeing), and the value is capped at one hour before being repeated.

A 403 is read as a rate limit only when the response carries `x-ratelimit-remaining: 0` or a `Retry-After` header; otherwise it is a genuine authorisation refusal. Telling a user to wait for a permission they will never be granted is worse than telling them nothing.

### Webhooks

`POST /api/v1/scm/webhooks/{providerCode}` is unauthenticated at the HTTP layer — authenticity comes from the HMAC signature, verified before anything is parsed or written. The body is read as raw `byte[]` so the HMAC is computed over exactly the bytes the provider sent. A bad signature is `SCM_WEBHOOK_SIGNATURE_INVALID` (401) with no database trace.

Because the body must be buffered whole before the signature can be checked, `WebhookRequestSizeLimitFilter` bounds it at `scm.webhook.max-request-bytes` (default 1 MiB) — it cannot depend on the caller being honest.

Once the signature verifies the endpoint always answers **200**, including for duplicates, unmapped events and internal failures, because a non-2xx would cause providers to disable the webhook. The outcome is in the body: `ScmWebhookAckResponse { outcome, deliveryId, eventType }` where `outcome` is `ACCEPTED | DUPLICATE | IGNORED | FAILED`.

Deliveries are recorded in `scm_webhook_deliveries`. Normalised event types are `PULL_REQUEST_OPENED | PULL_REQUEST_UPDATED | PULL_REQUEST_REOPENED | PULL_REQUEST_CLOSED`.

Deduplication is the insert itself: `ScmWebhookDeliveryService.claim` attempts the row and treats a unique-constraint violation as "already claimed". A pre-check alone cannot work, because two concurrent deliveries of the same event both see no existing row before either commits. The claim happens **before** publishing, so a provider retry arriving mid-processing is recognised as a duplicate rather than starting duplicate downstream work. A duplicate is acknowledged and dropped — except for a delivery already in `FAILED`, which is the one worth reprocessing, since the retry is a free chance to recover from a transient fault. When a provider sends no delivery header, the id falls back to `sha256:<digest of the raw body>`; deduplication must not silently switch off.

#### Consuming normalised events

`ScmEventPublisher` is the module's outbound boundary and delegates to `ScmWebhookEventDispatcher`. There are two ways to subscribe, and **no compile-time dependency runs from this module to any subscriber**:

| Route | How | Use when |
|---|---|---|
| `ScmWebhookEventConsumer` | Implement the interface; Spring injects every bean | Preferred — isolated from peers, named in logs, can declare which event types it wants via `supports(...)` |
| `@EventListener(NormalizedWebhookEvent.class)` | Plain Spring listener | Compatibility; served by `ApplicationEventWebhookEventConsumer`, which is itself just a registered consumer |

Each consumer is invoked in its own try/catch, so one that throws cannot stop its neighbours — which a bare `ApplicationEventPublisher.publishEvent` could not guarantee, since Spring publishes synchronously and the first listener to throw aborts the rest. Failures come back as a value rather than an exception, because the delivery has already been claimed and must be recorded: any consumer failure marks the delivery `FAILED` with a reason of `consumerName/ExceptionType` — the exception *message* is deliberately excluded, as a consumer's message may quote payload content. The request is still acknowledged 2xx.

The consumer contract, in full:

- **Be idempotent on `deliveryId`.** A `FAILED` delivery is replayable, and a replay re-invokes consumers that already succeeded.
- **Throw to request a replay.** A consumer whose failure should not hold up the delivery must catch its own errors.
- **Do not block.** Consumers run on the webhook request thread, inside the window the provider is waiting on. Queue the work.
- **Do not branch on `providerCode`.** It is there for logs and metrics; needing it to decide behaviour means the event mapping is incomplete, and that is where the fix belongs.

Dispatch is synchronous and in-process, so an event in flight is lost if the process dies. That is survivable rather than ignored: a row left in `RECEIVED` or `PROCESSING` is exactly the evidence needed to replay it. A durable outbox is the documented next step, and the dispatcher is the single place it would be introduced.

**Zero consumers is a legitimate state** — it is what a deployment without Review Orchestration looks like — and such a delivery is still recorded `PROCESSED`, with a `SCM_EVENT_NO_CONSUMER` log line for visibility. Recording `FAILED` would alert on an expected absence; `IGNORED` already means "unmapped provider event". **Nothing subscribes today** — ingestion and dispatch are built, review is not.

### Enums

`ScmConnectionStatus` — each value implies a different recovery path, which is why clients branch on it rather than a boolean:

| Value | Meaning |
|---|---|
| `ACTIVE` | Usable |
| `EXPIRED` | Token aged out; refresh or silent re-auth possible |
| `REVOKED` | Consent withdrawn at the provider; needs fresh consent |
| `DISCONNECTED` | Removed locally, row kept for history, must not be used |
| `ERROR` | Repeated provider failures, held aside |

`ScmCapabilityCode` — `LIST_REPOSITORIES`, `GET_REPOSITORY`, `LIST_PULL_REQUESTS`, `GET_PULL_REQUEST`, `GET_PULL_REQUEST_FILES`, `GET_PULL_REQUEST_DIFF`, `CREATE_WEBHOOK`, `DELETE_WEBHOOK`, `CREATE_PR_COMMENT`, `CREATE_PR_REVIEW`, `OAUTH_TOKEN_REFRESH`, `WEBHOOK_SIGNATURE_VERIFICATION`.

`ScmOperationCode` — the same list minus the last two, plus `GET_CURRENT_ACCOUNT`. A capability can be declared supported while its operation row is absent, which is why the two are reported separately. The six read operations are invoked by the [repository-management module](features/repository-management.md); the write operations are configured but not yet reachable from any route.

`ScmErrorCode` and its owned HTTP status — this is the stable `errors.code` vocabulary:

| Code | Status |
|---|---|
| `SCM_PROVIDER_NOT_FOUND` | 404 |
| `SCM_PROVIDER_INACTIVE` | 409 |
| `SCM_PROVIDER_CONFIGURATION_INVALID` | 500 |
| `SCM_CONNECTION_NOT_FOUND` | 404 |
| `SCM_CONNECTION_ALREADY_EXISTS` | 409 |
| `SCM_CONNECTION_EXPIRED` / `SCM_CONNECTION_REVOKED` | 401 |
| `SCM_CONNECTION_NOT_ACTIVE` | 409 |
| `SCM_REQUEST_INVALID` | 400 |
| `SCM_OPERATION_NOT_SUPPORTED` / `SCM_OPERATION_PARAMETER_MISSING` | 400 |
| `SCM_OPERATION_NOT_CONFIGURED` / `SCM_RESPONSE_MAPPING_INVALID` | 500 |
| `SCM_OAUTH_STATE_INVALID` | 400 |
| `SCM_OAUTH_EXCHANGE_FAILED` / `SCM_PROVIDER_API_ERROR` | 502 |
| `SCM_OAUTH_REFRESH_REJECTED` | 401 |
| `SCM_PROVIDER_RATE_LIMITED` | 429 |
| `SCM_PROVIDER_RESOURCE_NOT_FOUND` | 404 |
| `SCM_REPOSITORY_NOT_FOUND` / `SCM_PULL_REQUEST_NOT_FOUND` | 404 |
| `SCM_REPOSITORY_SCOPE_NOT_FOUND` | 404 |
| `SCM_WEBHOOK_SIGNATURE_INVALID` | 401 |
| `SCM_WEBHOOK_ALREADY_PROCESSED` / `SCM_WEBHOOK_EVENT_NOT_MAPPED` | 200 |
| `SCM_SECRET_NOT_FOUND` / `SCM_SECRET_STORAGE_FAILED` | 500 |

They all live here rather than in a second vocabulary because one enum that owns both the code and the HTTP status is what lets `GlobalExceptionHandler` need exactly one handler for all of it — adding a failure mode never requires touching that class.

Two of them exist to preserve a distinction a single code would have lost:

- **`SCM_OAUTH_REFRESH_REJECTED`** (401) versus `SCM_OAUTH_EXCHANGE_FAILED` (502) — the terminal/transient split described under [credential lifecycle](#credential-lifecycle). Without it, a dead credential and a brief provider outage are indistinguishable, and one of the two gets the wrong treatment.
- **`SCM_REPOSITORY_SCOPE_NOT_FOUND`** (404) versus `SCM_REPOSITORY_NOT_FOUND` (404) — same status, different cause and different fix. The second means the repository the user asked for is absent or invisible. The first means the user asked for no repository at all: the *account scope* the listing is derived from could not be resolved, which for Bitbucket means no readable workspace. The wording a client shows has to differ, because "repository not found" is nonsense advice when the user asked for a list.

### Provider-derived operation parameters

Some provider endpoints need a value no caller could reasonably supply. Bitbucket's repository listing is the worked example: `GET /2.0/repositories` — the cross-workspace endpoint — was end-of-lifed by Atlassian on **14 April 2026**, and the only supported listing is now `GET /2.0/repositories/{workspace}`. Callers of `LIST_REPOSITORIES` ask for "my repositories", not "the repositories in workspace X"; the workspace is a property of the *connection*.

Rather than branch on the provider in Java, a provider declares where the value comes from, in its `configuration`:

```json
"connectionParameters": {
  "workspace": ["{{connection.metadata.workspace}}", "{{connection.accountName}}"]
}
```

`ConnectionParameterResolver` resolves these against connection facts — `{{connection.id}}`, `{{connection.accountId}}`, `{{connection.accountName}}`, `{{connection.metadata.KEY}}`. A **list** means "first resolvable wins", the same fallback idea `response_mapping` already uses for field paths: an explicit per-connection override is honoured first, and the account discovered at connect time is the default. These are *defaults* — a caller that passes the parameter explicitly always wins, so `GET_REPOSITORY` addressing another workspace still works. A provider that needs nothing declares nothing, and GitHub declares nothing.

> **Bitbucket requires a readable workspace.** Atlassian removed every endpoint that could discover one (`/2.0/workspaces`, `/2.0/user/permissions/workspaces`, `/2.0/user/permissions/repositories` all answer 404 now) and has stated there will be no cross-workspace replacement — so this cannot be auto-discovered, by us or by anyone. A Bitbucket connection whose account has no workspace, or whose workspace slug differs from the account name, returns `SCM_REPOSITORY_SCOPE_NOT_FOUND` until `metadata.workspace` is set on the connection row.

---

## Repository management module

Documented separately: [`features/repository-management.md`](features/repository-management.md).

In brief — six paged read endpoints nested under `/api/v1/scm/connections/{connectionId}/repositories`, built entirely on `ScmClient` with no provider name anywhere in the module. A repository is addressed as `{owner}/{repo}` because that is what provider APIs accept; a pull request by its user-visible `number`, not its id.

Three things about it are worth knowing even if you read nothing else:

**One authorization gate.** `ScmResourceAccessService` is the only way to obtain a `ScmResourceContext`, and every service below it takes that context as a parameter — so an unauthorized call cannot be written. Another user's connection id answers **404, not 403**, so the endpoint cannot be used to enumerate connections. Repository and pull-request access are enforced by the provider refusing the credential, not by a local permissions mirror.

**Nothing is persisted.** No `repositories` or `pull_requests` tables, no new columns. They are provider resources; a mirror would be stale when written. The one write is `lastUsedAt` on a connection after a successful call.

**`PageResponse.totalElements` is usually absent**, because providers do not publish totals. Clients must drive paging from `hasNext`. An invented total would be worse than none, because a client cannot tell it is wrong.

It also made three small additive changes inside the SCM module, all of which stayed in the declarative vocabulary rather than becoming Java branches: `parameterValueMappings` and `multiValueQueryParams` on `request_configuration` (canonical → provider request values), a `mergedAt` field on `NormalizedPullRequest`, and 404 classification in `ConfigDrivenScmClient`. Two pre-existing test failures in the engine were also fixed — a pagination heuristic that overrode a declared `nextPath`, and double-encoded path values. Both are described in the feature document.

---

## Data model

Schema is managed by `spring.jpa.hibernate.ddl-auto: update`. **There are no migrations** — no Flyway, no Liquibase, no SQL files. Column types are whatever Hibernate infers.

`BaseEntity` (`@MappedSuperclass`) gives every entity `id` (identity-generated `Integer`), `created_at` (non-null, non-updatable) and `updated_at`.

**`users`** — `email` (non-null, unique, 255), `full_name` (100, nullable), `profile_picture` (500, nullable), `status` (enum string, non-null, defaults `ACTIVE`), `email_verified` (non-null), `last_login_at` (nullable). No password, username, roles or tenant column.

**`user_logins`** — unique on `(user_id, provider)`. Holds `provider` (enum string), `provider_user_id` (nullable, **not** unique), `expires_at` (the provider grant's expiry, metadata only), plus the refresh-token state: `app_refresh_token` (unique — the SHA-256 hex digest), `app_refresh_token_previous_hash`, `app_refresh_token_expires_at` (null is **treated as expired**, failing closed), `app_refresh_token_revoked`, `app_refresh_token_revoked_at`.

`LoginProvider` = `GITHUB, GITLAB, BITBUCKET, AZURE_DEVOPS` — only the first and third have implementations; nothing can produce the other two. `UserStatus` = `ACTIVE, INACTIVE`.

**SCM tables** — `scm_providers`, `scm_provider_capabilities`, `scm_provider_operations`, `scm_provider_events`, `scm_connections`, `scm_secrets`, `scm_webhook_deliveries`. `scm_connections` is unique on `(user_id, provider_id, external_account_id)`, which is what makes reconnect an upsert.

---

## Security

`SecurityConfig` defines one filter chain. There is no `@EnableMethodSecurity`, no `AuthenticationManager`, no `PasswordEncoder` and no `UserDetailsService`.

- **Sessions** `STATELESS`. **CSRF disabled** — safe because authentication is bearer-only and the service never reads a cookie.
- **`permitAll`:** `/api/v1/health`, `/api/v1/auth/**`, `/api/v1/oauth/**`, `/api/v1/scm/webhooks/**`, `/api/v1/scm/connections/callback/**`. Swagger paths are added **only** when `app.security.expose-api-docs=true`; otherwise they 401. Everything else is `authenticated()`.
- **Headers:** `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, HSTS one year with subdomains, a restrictive `Permissions-Policy`, and a CSP chosen at startup — locked to `default-src 'none'` unless API docs are exposed, which relaxes it enough for Swagger UI.

**`JwtAuthenticationFilter` never rejects.** It populates the `SecurityContext` or leaves the request anonymous and lets `authorizeHttpRequests` decide; any internal `RuntimeException` is caught, the context cleared, and the chain continues. The `bearer ` prefix is matched case-insensitively.

It performs **one database read per authenticated request** (no caching) and rejects an unknown user or a non-`ACTIVE` status — so deactivating an account takes effect on the next request rather than at token expiry. The principal is the `User` **entity**, with `Collections.emptyList()` authorities, which is what makes `@AuthenticationPrincipal User` work.

401s come from `JwtAuthenticationEntryPoint` with `WWW-Authenticate: Bearer` and a uniform body — missing, malformed, expired, bad-signature, deleted and inactive are indistinguishable by design:

```json
{ "status": "FAILED", "message": "Unauthorized. Please provide a valid access token." }
```

**CORS is off by default.** With `app.security.allowed-origins` empty, the bean logs `CORS_DISABLED` and returns no configuration at all, so a browser preflight gets 401. Wildcards are **rejected at startup** with `IllegalStateException`; `allowCredentials` is hardcoded `false`. Scope is `/api/**`.

That default is deliberate: the Next.js frontend calls this API **server-to-server** and keeps the access token in an httpOnly cookie the browser's scripts cannot read. Direct browser→API calls would require setting `CORS_ALLOWED_ORIGINS` *and* moving the token somewhere JavaScript can reach — which is a downgrade, not a shortcut.

---

## Configuration

| Property | Env var | Default |
|---|---|---|
| `server.servlet.context-path` | `SERVER_CONTEXT_PATH` | `/coderev` |
| `security.jwt.secret` | `JWT_SECRET` | **none — startup fails** |
| `security.jwt.access-token-expiration` | `JWT_ACCESS_TOKEN_EXPIRATION` | `900` s |
| `security.jwt.refresh-token-expiration` | `JWT_REFRESH_TOKEN_EXPIRATION` | `2592000` s |
| `app.frontend-url` | `FRONTEND_URL` | `http://localhost:3000` |
| `app.security.allowed-origins` | `CORS_ALLOWED_ORIGINS` | empty → CORS off |
| `app.security.expose-api-docs` | `EXPOSE_API_DOCS` | `false` |
| `github.oauth.client-id` / `-secret` | `GITHUB_CLIENT_ID` / `_SECRET` | empty |
| `bitbucket.oauth.client-id` / `-secret` | `BITBUCKET_CLIENT_ID` / `_SECRET` | empty |
| `scm.providers.github.client-id` / `-secret` | `SCM_GITHUB_CLIENT_ID` / `_SECRET` | falls back to `GITHUB_CLIENT_ID` / `_SECRET` |
| `scm.providers.bitbucket.client-id` / `-secret` | `SCM_BITBUCKET_CLIENT_ID` / `_SECRET` | falls back to `BITBUCKET_*` |
| `scm.secrets.encryption-key` | `SCM_SECRETS_ENCRYPTION_KEY` | dev key — **override in deployment** |
| `scm.webhook.max-request-bytes` | `SCM_WEBHOOK_MAX_REQUEST_BYTES` | `1048576` |
| `scm.seed.enabled` | `SCM_SEED_ENABLED` | `true` |
| `scm.http.max-retries` | — | `2` |
| `scm.http.retry-backoff-ms` | — | `250` (doubling) |
| `scm.token-refresh.enabled` | — | `true` |
| `scm.token-refresh.interval` | — | `PT5M` |
| `scm.token-refresh.lead-time` | — | `PT15M` |
| `scm.token-refresh.batch-size` | — | `50` |

`scm.token-refresh.enabled=false` disables only the proactive sweep; the on-demand path in `ScmTokenService` still renews a credential it finds expiring. `interval` must stay comfortably shorter than `lead-time`, or a token can expire inside one interval and be found only after the fact. `batch-size` is a bound rather than a target — each renewal is an outbound call, and a backlog is simply drained by the next tick in expiry order.

`@EnableScheduling` lives in `config/SchedulingConfig.java`. The sweep is safe to run on more than one replica: concurrent sweeps serialise on the same row lock the on-demand path uses, so the second one re-reads, finds a valid token and does nothing.

No `server.port` is set, so the Boot default **8080** applies — corroborated by the OAuth redirect URIs, which hardcode `localhost:8080`.

The identity and SCM OAuth applications are **separate**. Sign-in needs only `read:user user:email`; the SCM flow requests repository contents, pull-request writes and webhook management. Sharing one application would quietly widen what signing in can grant.

### Local development

`src/main/resources/application-local.yml` is tracked in git and carries the dev JWT key, both sign-in OAuth applications, `allowed-origins: http://localhost:3000`, `expose-api-docs: true`, and the SCM client **ids** and redirect URIs.

SCM client **secrets** live in `config/application-local.yml`, which is **untracked** (`/config/` is gitignored). Spring Boot's default config locations include `optional:file:./config/`, searched after the classpath, so it overrides per-property with nothing imported explicitly.

One trap, and the reason `spring.devtools.restart.additional-paths: config` is set: `./config/` is deliberately *not* on the classpath, and devtools only restarts on classpath changes. Without that setting, editing a credential there changes nothing in the running application, and the next connect attempt fails using the credential loaded at the last restart — while the file on disk is already correct.

> **Security debt.** `application-local.yml` still contains three live committed secrets: the GitHub and Bitbucket sign-in client secrets and the JWT signing key. They are in git history and should be treated as compromised, rotated, and moved into `config/`. The repository's own `application.yml` states the principle: "a default is how a credential ends up in a repository."

### Swagger

`http://localhost:8080/coderev/swagger-ui.html`, OpenAPI JSON at `/coderev/v3/api-docs` — **only** when `EXPOSE_API_DOCS=true`. Security scheme `bearerAuth` is registered globally. A controller package absent from `springdoc.packages-to-scan` is silently undocumented, so adding a controller means adding its package there; note the list still includes `com.kksg.applicationServices.controllers`, which does not exist.

---

## Testing

```bash
./mvnw test                                                    # everything
./mvnw test -Dtest='com.kksg.applicationServices.scm.**'       # SCM module only
./mvnw test -Dtest='com.kksg.applicationServices.repository.**' # repository management only
```

508 tests, all offline except the opt-in group below. Notable suites, and what each exists to catch:

| Suite | Catches |
|---|---|
| `scm/seed/SeededOperationRequestTest` | A seeded operation targeting a wrong or removed endpoint. Reads the real `scm/seed/*.json` and asserts the full generated URI — every other engine test builds configuration inline, which is why none of them caught the Bitbucket outage |
| `scm/common/http/ScmHttpExecutorRedirectTest` | A cross-origin redirect being followed with the user's token attached, and the retry policy widening |
| `scm/operation/engine/ConfigDrivenScmClientStatusTest` | The provider HTTP status matrix collapsing — 401 vs 403 vs rate-limit-403 vs 404 vs 5xx, and `Retry-After` sanitisation |
| `scm/connection/service/ScmOAuthTokenExchangerTest` | A refresh failure being misclassified as terminal or transient; a secret reaching an exception message |
| `scm/connection/service/ScmTokenRefresherTest` | The unbounded refresh loop returning — asserts `isUsable() == false` after a rejected grant |
| `scm/webhook/service/ScmWebhookServiceTest` | The pipeline's step order (an unverified caller causing a write) and its outcome taxonomy |
| `scm/event/ScmWebhookEventDispatcherTest` | One consumer's failure starving the others |

### Live provider tests (opt-in)

`scm/live/LiveProviderIntegrationTest` talks to the real GitHub and Bitbucket APIs. **Disabled by default and skipped silently** — it needs live credentials, makes outbound calls, and can fail for reasons unrelated to this codebase. None of that belongs in a build gate.

```bash
# Bitbucket
CODEREV_LIVE_SCM=1 \
CODEREV_LIVE_BITBUCKET_TOKEN=<access token> \
CODEREV_LIVE_BITBUCKET_WORKSPACE=<workspace slug> \
  ./mvnw test -Dtest='LiveProviderIntegrationTest'

# GitHub
CODEREV_LIVE_SCM=1 CODEREV_LIVE_GITHUB_TOKEN=<access token> \
  ./mvnw test -Dtest='LiveProviderIntegrationTest'
```

Each provider's group is additionally gated on its own token variable, so supplying one set of credentials runs that provider and skips the other rather than failing it. A read-only token suffices; nothing writes to a provider. **Credentials come from the environment only** — none appear in the file or in any fixture, and nothing is written to disk. Assertions are about HTTP status and response *shape*, never a particular account's repositories, so they pass for any account.

These exist for one reason offline tests cannot cover: unit tests prove the engine resolves whatever template it is given, and only a live call proves the template still points at an endpoint that exists. `removedCrossWorkspaceEndpointIsStillGone` is the sentinel for that class of failure — if Atlassian ever restores `/2.0/repositories`, that failing test is how we find out.

---

## Known gaps

Beyond the "Not built" rows above, these are behaviours worth knowing before relying on the modules that *are* built.

**Identity**

- Concurrent refresh is last-write-wins; clients must serialise refreshes.
- One refresh token per `(user, provider)` — a second device silently invalidates the first, and the resulting reuse detection revokes all sessions.
- Access tokens have no `jti` or denylist; logout does not invalidate the current one.
- `UserStatus.INACTIVE` is never written by any code path — deactivation requires a manual database update.
- OAuth sign-in state is replayable within 10 minutes. No PKCE, no OIDC.
- Bitbucket's email lookup reads only the first page of `/2.0/user/emails`.
- No rate limiting, lockout, audit table or login-attempt tracking.
- Malformed JSON and wrong HTTP methods return 500 rather than 400/405.

**SCM**

- Webhook deliveries are normalised and dispatched, but **nothing subscribes yet** — Review Orchestration is the intended consumer. Such deliveries are recorded `PROCESSED` with a `SCM_EVENT_NO_CONSUMER` log line.
- Event dispatch is **synchronous and in-process**. An event in flight is lost if the process dies; the delivery row is the recovery evidence, and there is no automated replay — a stuck `RECEIVED`/`PROCESSING`/`FAILED` row must be replayed by hand. A durable outbox is the next step.
- The write operations — `CREATE_PR_COMMENT`, `CREATE_PR_REVIEW`, `CREATE_WEBHOOK`, `DELETE_WEBHOOK` — are configured but reachable only internally. The six read operations are exposed by the repository-management module.
- **Bitbucket needs a workspace that cannot be discovered.** See [provider-derived operation parameters](#provider-derived-operation-parameters). An account with no workspace lists nothing, and a workspace whose slug differs from the account name needs `metadata.workspace` set on the connection row — there is no UI for that today.
- The provider list exposes no signal for whether credentials are configured, so a client cannot disable a Connect action ahead of time — a misconfigured provider fails on click with `SCM_PROVIDER_CONFIGURATION_INVALID`.
- Bitbucket declares `CREATE_PR_REVIEW` unsupported.
- No webhook *registration* flow: `CREATE_WEBHOOK` is configured but no route or lifecycle hook calls it, so deliveries only arrive for webhooks created manually at the provider.
- The token sweep logs its outcome but exports no metrics, so a slow accumulation of transient refresh failures is visible only in logs.
- OAuth state remains replayable within its 10-minute window; no PKCE.

**Repository management**

Full list in [the feature document](features/repository-management.md#known-gaps). The ones with the widest reach:

- **No caching.** Every request calls the provider, so a provider rate limit is the practical ceiling on throughput.
- **Search reach is bounded** at 500 items by default, because neither provider offers a server-side search on these listings. An absent `totalElements` is the signal that a result set may be incomplete.
- **`MERGED` is approximate as a filter on GitHub**, where it maps to `closed` at the provider. Individual states are still correct, being resolved from `mergedAt`.

**Cross-cutting**

- No schema migrations; `ddl-auto: update` only.
- No tests for the identity module. `src/test` holds the context-load test, the SCM suites and the repository-management suite — **508 tests**, of which 2 are the opt-in live groups, skipped by default.
- `ModelMapperConfig` and `CloudnaryConfig` define unused beans; `spring.mail.*` is configured with no mail code.
