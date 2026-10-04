# CodeRev backend — documentation

Two kinds of document live here, and the distinction is worth knowing before you go looking for something.

[`BACKEND.md`](BACKEND.md) is the **cross-cutting reference**: conventions shared by every module (the `ApiResponse` envelope, `GlobalExceptionHandler`, the error vocabulary), the data model, security configuration, deployment configuration, and overall feature status. Start there.

`features/` holds **one document per feature**, mirroring the frontend's `docs/features/`. Each is self-contained: what it does, the endpoints and files involved, the decisions behind them, and what is not built yet.

| Feature                                                       | Document                                                                | State |
| ------------------------------------------------------------- | ----------------------------------------------------------------------- | ----- |
| Identity — OAuth sign-in, JWT sessions, profile               | [`BACKEND.md#identity-module`](BACKEND.md#identity-module)              | Built |
| SCM integration — providers, connections, webhooks, engine    | [`BACKEND.md#scm-module`](BACKEND.md#scm-module)                        | Built |
| Repository management — repositories, PRs, files, diffs       | [`features/repository-management.md`](features/repository-management.md) | Built |

Identity and SCM are documented as sections of `BACKEND.md` rather than as separate files, because that is where they were written. They are not less documented for it — the SCM section covers the operation engine in detail — and splitting them out would be a rewrite with no reader benefit. New features get their own file.

The three modules build on each other in that order: identity establishes who the user is, SCM integration records which provider accounts they have authorized, and repository management is the first module to _use_ one of those authorizations.

---

## The one idea to understand first

Everything about the SCM and repository modules follows from this: **no business code names a provider.**

A caller asks for a normalized operation — `LIST_REPOSITORIES` — and supplies a connection. It does not know the URL, the HTTP method, the authentication header, the paging convention or the response shape. All of those come from database configuration for that connection's provider, seeded from `resources/scm/seed/*.json`.

```
normalized request  →  ScmClient  →  provider configuration  →  GitHub / Bitbucket
```

The practical test is that `ConfigDrivenScmClient` contains no provider name anywhere, and neither does Repository Management. Adding a provider is rows, not code. When a genuine provider difference appears — one provider spelling a pull-request state `open` and another `OPEN` — the fix goes into the configuration vocabulary, not into an `if`. [Provider differences](features/repository-management.md#provider-differences-resolved-in-configuration) is a worked example of exactly that.

---

## Frontend documentation

Separate, in [`../../frontend/docs/`](../../frontend/docs/README.md), with a matching per-feature structure. The repository-management documents on the two sides are complements rather than duplicates: this one covers the API, the authorization chain and the provider configuration; the frontend one covers the routes, the pagination contract as a client sees it, and the diff viewer.
