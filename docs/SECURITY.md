# Security and hardening overview

This document is the reviewer-facing entry point for the security and hardening
behavior implemented in this repository. It summarizes current application controls,
evidence boundaries, and known limitations; it does not claim that the application or
an unspecified deployment is production-grade secure or reliable. Detailed API,
configuration, migration, and allocation behavior remains authoritative in the linked
documents.

## Authentication

`POST /api/auth/token` is the public credential endpoint. It authenticates the
submitted username and password against a separate, database-backed authentication
identity. The application-provided Spring Security `PasswordEncoder` creates one-way
password hashes, and authentication compares submitted credentials with the encoded
`password_hash`; plaintext and encoded passwords are not returned in responses or
placed in tokens.

Successful authentication returns a short-lived access token with token type
`Bearer`. Protected requests submit that token in the `Authorization` header. Spring
Security validates it independently for each request, and the application keeps no
HTTP session: HTTP Basic, form login, logout, and cookie-based authentication are not
enabled.

No production identity or credential is committed. Identity provisioning is an
external operational responsibility because the API has no registration,
account-management, user-management, role-management, password-change, or
password-reset endpoint. See [API notes](API_NOTES.md) for request and response
details.

## Authorization

Each persisted identity has exactly one of the two supported application roles:
`USER` or `ADMIN`. The current endpoint policy is:

| Endpoint | Access |
| --- | --- |
| `GET /api/health` | Public |
| `POST /api/auth/token` | Public |
| `POST /api/games` | `ADMIN` |
| `GET /api/games/{code}` | `USER` or `ADMIN` |
| `POST /api/games/{gameCode}/allocations` | `USER` or `ADMIN` |

All unclassified routes are denied by default. A request without acceptable
authentication receives `401 Unauthorized`; this includes a missing, malformed,
incorrectly signed, or expired Bearer token. A request that is authenticated but
lacks authority for the route receives `403 Forbidden`. The JWT authority converter
recognizes only the established `USER` and `ADMIN` role values. A validly signed token
with no role or an unsupported role can authenticate, but it receives no arbitrary
application authority and is forbidden on role-protected routes.

## JWT boundary

Issued JWTs contain only the stable username subject (`sub`), issued-at time (`iat`),
expiration (`exp`), and a signed `roles` claim derived from the persisted identity.
They are signed with HS256 using externally supplied Base64 key material. Configuration
requires the decoded key to contain at least 32 bytes; a missing, malformed, or shorter
key prevents valid application configuration.

`JWT_ACCESS_TOKEN_LIFETIME` must be a whole number of seconds from one second through
24 hours and defaults to `PT15M` (15 minutes). Authorization state is copied into an
issued token, so a later persisted-role change does not alter that token: its role
remains effective until expiration.

A signature provides token integrity and authenticity to a verifier that holds the
shared signing key. It does **not** encrypt the JWT or make its claims confidential.
Claims must therefore never contain passwords, signing material, game keys, or other
secrets.

The current design has no refresh-token flow, token-revocation mechanism,
signing-key-rotation mechanism, external identity provider, or MFA. Expiration is the
only per-token end to authorization established by the application.

## Secrets, profiles, and configuration

Datasource passwords and JWT signing material are supplied from outside source
control. There is no usable committed production fallback for either secret:

- the default `local` profile supplies only non-secret local datasource URL and
  username defaults and still requires `SPRING_DATASOURCE_PASSWORD`;
- the `prod` profile requires datasource URL, username, and password externally;
- the `test` profile requires an external PostgreSQL datasource and keeps its
  runtime-generated signing material in test-only configuration; and
- every runtime profile requires `JWT_SIGNING_KEY_BASE64`.

Environment owners are responsible for supplying, storing, and controlling these
values. Production credentials, datasource passwords, signing material, access
tokens, and secret game-key values do not belong in public documentation, public
errors, application-owned logs, or source control. [Configuration profiles](CONFIGURATION_PROFILES.md)
describes the complete profile responsibilities. A successful allocation response
still returns the allocated game key as part of the existing API contract.

## Schema and migration ownership

Flyway owns the PostgreSQL schema. It applies versioned migrations at startup, while
Hibernate uses `ddl-auto: validate` rather than creating or updating the normal
schema. `V2__create_authentication_identities.sql` introduces persisted authentication
identities. `V3__add_authentication_identity_roles.sql` adds the constrained `USER` or
`ADMIN` role, backfills identities that already exist at that point with the
conservative `USER` baseline, and then makes the role required.

Automatic Flyway baselining is disabled. A non-empty schema without Flyway history
requires an explicit, verified adoption or recreation process; the application does
not accept it through an automatic shortcut. See [Database migrations](DATABASE_MIGRATIONS.md)
for the adoption rules and migration evidence.

## Public errors and information disclosure

Covered application failures use the common `ApiError` JSON representation, with a
timestamp, status, standard error phrase, safe message, request path, and optional
field-error entries. The established mappings cover:

- Bean Validation failures (`400`);
- credential and Bearer authentication failures (`401`);
- authenticated authorization denials (`403`);
- a missing Game on the Game retrieval operation (`404`);
- an oversized covered JSON request body (`413`); and
- unexpected application failures (`500`).

Unknown usernames and incorrect passwords produce indistinguishable public
authentication responses. Unexpected failures return a generic public message.
Exception messages and causes, stack traces, SQL or database details, credentials,
tokens, signing material, secret game-key values, private environment data, and
machine-specific details remain outside this public contract.

Where the application has not defined a separate mapping, Spring MVC request errors
retain their framework semantics. In particular, the generic application exception
handler rethrows framework HTTP errors, message-conversion failures, and type
mismatches rather than replacing them with a speculative application mapping. The
detailed response boundary is in [API notes](API_NOTES.md).

## Request correlation and application-owned logging

The application generates a new `X-Request-Id` for every request, returns it on the
response, places it in request-scoped MDC, and removes it when processing completes.
A caller-supplied value is not trusted or propagated.

Application-owned operational events are limited to these categories:

- rejected username/password authentication;
- rejected Bearer authentication when a Bearer credential was supplied;
- authorization denials; and
- unexpected application failures.

Their bounded context consists of the event category, request ID, HTTP method, path,
status, and—for unexpected failures—the exception type. Application-owned logging
intentionally excludes credentials and password hashes, Authorization values and
Bearer tokens, JWT contents and signing material, datasource secrets, request and
response bodies, secret game-key values, raw exception or cause details, Throwable
dumps, SQL/database details, and machine-specific information. Missing credentials
and successful requests do not produce routine application-owned security/access
events.

These guarantees cover this application's logging statements. They do not establish
how third-party libraries, infrastructure, or arbitrary future logging configuration
will behave.

## Input and abuse boundaries

The three current JSON body-bearing operations—the token, Game creation, and
allocation endpoints—have a 16 KiB (16,384-byte) request-body limit when the request
uses a JSON-compatible media type. The filter reads the actual bytes rather than
trusting `Content-Length`; protected routes still apply authentication and
authorization before body-size handling.

Allocation idempotency keys must be non-blank and no longer than 255 characters,
matching their persisted boundary. Usernames, passwords, Game codes, and idempotency
keys retain their submitted values: the application does not silently trim, change
case, Unicode-normalize, or otherwise canonicalize them.

Idempotency protects a caller from creating another allocation when the same key is
replayed. It is not a rate limit, authentication control, authorization entitlement,
or per-user quota. The public token endpoint remains susceptible to repeated
credential attempts because there is no account lockout or application-owned rate
limiter. An authorized `USER` or `ADMIN` can submit repeated allocation requests
because there is no per-user entitlement, reservation, billing, or quota model.

The repository deliberately does not invent these controls without the information
needed to make them reliable:

- an IP-address or in-process limiter is deferred until deployment topology, trusted
  proxy/client-address handling, and multi-instance enforcement are established;
- arbitrary account lockout could itself let an attacker deny service to a legitimate
  identity; and
- arbitrary allocation limits lack a product entitlement policy from which to derive
  a defensible threshold.

The application also has no distributed rate limiter or trusted-proxy/client-address
policy. The repository establishes no deployment-level WAF, API gateway, reverse
proxy, or equivalent protection. Those limitations must be addressed according to a
real deployment and product model; their absence is not evidence that the controls
are unnecessary.

## Allocation invariants remain unchanged

Security hardening does not redefine allocation behavior. The synchronous
`@Transactional` service operation, exact-key idempotency replay, available-key
selection, duplicate-allocation exception translation, and database-backed uniqueness
constraints remain authoritative and unchanged. In particular, the database still
enforces unique Game codes, unique game-key codes, one allocation per game key,
unique idempotency keys, and one idempotency record per allocation.

See [allocation runtime debugging](ALLOCATION_RUNTIME_DEBUGGING.md) for the complete
transaction and replay flow and [allocation concurrency evidence](ALLOCATION_CONCURRENCY.md)
for the observed duplicate-allocation protection and evidence limits.
