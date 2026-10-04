# CodeRev — Backend

Spring Boot 3.3.2 / Java 21 / PostgreSQL. Packaged as a WAR (`ServletInitializer` is present), run in development with `./mvnw spring-boot:run -Dspring-boot.run.profiles=local`.

Two feature modules are built: **Identity** (OAuth sign-in and sessions) and **SCM** (source-control provider integration). Everything else — repository indexing, pull-request review, AI analysis, billing — does not exist yet.

```
com.kksg.applicationServices
├── identity/     37 files — sign-in, JWT sessions, user profile
├── scm/          92 files — providers, connections, webhooks, outbound operation engine
├── common/        6 files — ApiResponse envelope, exceptions, GlobalExceptionHandler
└── config/                 OpenAPI, ModelMapper, Cloudinary (last two unused)
```

---

## Contents

- [Conventions](#conventions)
- [Feature status](#feature-status)
- [Identity module](#identity-module)
- [SCM module](#scm-module)
- [Data model](#data-model)
- [Security](#security)
- [Configuration](#configuration)
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
| `ScmException` | per `ScmErrorCode` | `error(message, {"code": "SCM_..."})` |
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
| Repository / pull-request REST endpoints | Not built | Operations are reachable only internally |
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

**Nothing here paginates.** Both collection endpoints return a bare array in `data`, ordered server-side. (`ScmPagination` exists, but it drives *outbound* paging against provider APIs and is not exposed.)

`providerId` and `connectionId` are `Integer` — a non-numeric segment is a 400, not a 404.

**Ownership is enforced in the service layer**, so another user's connection id answers `404 SCM_CONNECTION_NOT_FOUND` rather than 403; the backend cannot distinguish "not yours" from "not there" without leaking the difference.

`GET /scm/connections` returns **every** row including `DISCONNECTED` ones — the backing query has no status filter, because disconnected rows are retained for history. Clients that want only live connections must filter.

### Response shapes

`ScmProviderResponse` — `id`, `providerCode`, `providerName`, `providerType` (`"CLOUD"`/`"SELF_HOSTED"`, nullable), `active`, `displayOrder`.

`ScmProviderDetailResponse` — the above plus `apiBaseUrl`, `oauthScopes[]`, `supportedCapabilities[]`, `unsupportedCapabilities[]`, `configuredOperations[]`. Deliberately excludes raw provider config, credential property names and webhook signature settings.

`ScmConnectionResponse` — `id`, `providerId`, `providerCode`, `providerName`, `externalAccountId`, `externalAccountName`, `connectionStatus`, `tokenExpiry` (nullable — null means the token does not expire), `connectedAt`, `lastUsedAt` (nullable), `displayName` (nullable), `avatarUrl` (nullable). **Never carries a token or token reference**; the mapper has no access to the credential store.

`ScmAuthorizationUrlResponse` — `authorizationUrl`, `providerCode`, `state`.

### Credential storage

`EncryptedDatabaseSecretStore` holds SCM credentials as `AES/GCM/NoPadding` ciphertext in `scm_secrets`, with a `keyVersion` to allow rotation. The key is `scm.secrets.encryption-key` (Base64, 256-bit). The built-in development default logs `SCM_SECRET_STORE_INSECURE_KEY: ... Acceptable only because a development profile is active` and **must** be overridden in any deployed environment.

Provider client ids and secrets are not stored in the database at all. `scm_providers.configuration` stores *pointers* — `"clientSecretProperty": "scm.providers.github.client-secret"` — which `ProviderCredentialResolver` dereferences against the Spring `Environment`. Two consequences, and they are the reason for the indirection: a database backup or an admin API that returns provider configuration cannot leak a client secret, and each environment points at its own OAuth application with no per-environment database difference. Error messages name only the missing *property*, never a value.

### Webhooks

`POST /api/v1/scm/webhooks/{providerCode}` is unauthenticated at the HTTP layer — authenticity comes from the HMAC signature, verified before anything is parsed or written. The body is read as raw `byte[]` so the HMAC is computed over exactly the bytes the provider sent. A bad signature is `SCM_WEBHOOK_SIGNATURE_INVALID` (401) with no database trace.

Because the body must be buffered whole before the signature can be checked, `WebhookRequestSizeLimitFilter` bounds it at `scm.webhook.max-request-bytes` (default 1 MiB) — it cannot depend on the caller being honest.

Once the signature verifies the endpoint always answers **200**, including for duplicates, unmapped events and internal failures, because a non-2xx would cause providers to disable the webhook. The outcome is in the body: `ScmWebhookAckResponse { outcome, deliveryId, eventType }` where `outcome` is `ACCEPTED | DUPLICATE | IGNORED | FAILED`.

Deliveries are recorded in `scm_webhook_deliveries`. Normalised event types are `PULL_REQUEST_OPENED | PULL_REQUEST_UPDATED | PULL_REQUEST_REOPENED | PULL_REQUEST_CLOSED`. **Nothing consumes them yet** — ingestion is built, review is not.

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

`ScmOperationCode` — the same list minus the last two, plus `GET_CURRENT_ACCOUNT`. A capability can be declared supported while its operation row is absent, which is why the two are reported separately.

`ScmErrorCode` and its owned HTTP status — this is the stable `errors.code` vocabulary:

| Code | Status |
|---|---|
| `SCM_PROVIDER_NOT_FOUND` | 404 |
| `SCM_PROVIDER_INACTIVE` | 409 |
| `SCM_PROVIDER_CONFIGURATION_INVALID` | 500 |
| `SCM_CONNECTION_NOT_FOUND` | 404 |
| `SCM_CONNECTION_ALREADY_EXISTS` | 409 |
| `SCM_CONNECTION_EXPIRED` / `SCM_CONNECTION_REVOKED` | 401 |
| `SCM_OPERATION_NOT_SUPPORTED` / `SCM_OPERATION_PARAMETER_MISSING` | 400 |
| `SCM_OPERATION_NOT_CONFIGURED` / `SCM_RESPONSE_MAPPING_INVALID` | 500 |
| `SCM_OAUTH_STATE_INVALID` | 400 |
| `SCM_OAUTH_EXCHANGE_FAILED` / `SCM_PROVIDER_API_ERROR` | 502 |
| `SCM_PROVIDER_RATE_LIMITED` | 429 |
| `SCM_WEBHOOK_SIGNATURE_INVALID` | 401 |
| `SCM_WEBHOOK_ALREADY_PROCESSED` / `SCM_WEBHOOK_EVENT_NOT_MAPPED` | 200 |
| `SCM_SECRET_NOT_FOUND` / `SCM_SECRET_STORAGE_FAILED` | 500 |

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

No `server.port` is set, so the Boot default **8080** applies — corroborated by the OAuth redirect URIs, which hardcode `localhost:8080`.

The identity and SCM OAuth applications are **separate**. Sign-in needs only `read:user user:email`; the SCM flow requests repository contents, pull-request writes and webhook management. Sharing one application would quietly widen what signing in can grant.

### Local development

`src/main/resources/application-local.yml` is tracked in git and carries the dev JWT key, both sign-in OAuth applications, `allowed-origins: http://localhost:3000`, `expose-api-docs: true`, and the SCM client **ids** and redirect URIs.

SCM client **secrets** live in `config/application-local.yml`, which is **untracked** (`/config/` is gitignored). Spring Boot's default config locations include `optional:file:./config/`, searched after the classpath, so it overrides per-property with nothing imported explicitly.

One trap, and the reason `spring.devtools.restart.additional-paths: config` is set: `./config/` is deliberately *not* on the classpath, and devtools only restarts on classpath changes. Without that setting, editing a credential there changes nothing in the running application, and the next connect attempt fails using the credential loaded at the last restart — while the file on disk is already correct.

> **Security debt.** `application-local.yml` still contains three live committed secrets: the GitHub and Bitbucket sign-in client secrets and the JWT signing key. They are in git history and should be treated as compromised, rotated, and moved into `config/`. The repository's own `application.yml` states the principle: "a default is how a credential ends up in a repository."

### Swagger

`http://localhost:8080/coderev/swagger-ui.html`, OpenAPI JSON at `/coderev/v3/api-docs` — **only** when `EXPOSE_API_DOCS=true`. Security scheme `bearerAuth` is registered globally. Note `springdoc.packages-to-scan` lists `com.kksg.applicationServices.controllers`, which does not exist.

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

- Webhook deliveries are stored but never consumed.
- No REST surface for repositories or pull requests; the operation engine is internal-only.
- `OAUTH_TOKEN_REFRESH` is declared per provider but nothing schedules a refresh, so `EXPIRED` connections require manual reconnect.
- The provider list exposes no signal for whether credentials are configured, so a client cannot disable a Connect action ahead of time — a misconfigured provider fails on click with `SCM_PROVIDER_CONFIGURATION_INVALID`.
- Bitbucket declares `CREATE_PR_REVIEW` unsupported.

**Cross-cutting**

- No schema migrations; `ddl-auto: update` only.
- No tests for the identity module. `src/test` holds the context-load test plus SCM unit tests.
- `ModelMapperConfig` and `CloudnaryConfig` define unused beans; `spring.mail.*` is configured with no mail code.
