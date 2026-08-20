# API notes

## Authentication

`POST /api/auth/token` accepts a JSON body containing `username` and `password`.
Correct credentials return `accessToken`, `tokenType` (`Bearer`), and
`expiresInSeconds`. Send the token to protected routes with the standard
`Authorization: Bearer <accessToken>` header. `GET /api/health` and the token
endpoint are public; other unclassified routes remain denied.

Authentication identities are stored separately from game and allocation state.
Passwords must be encoded with the application's Spring Security `PasswordEncoder`;
neither plaintext nor encoded passwords belong in API responses or JWT claims. No
default production identity is committed, and this API does not provide registration,
account-management, user-management, or role-management endpoints.

Each authentication identity persists exactly one role. `USER` may retrieve Game
information and request game-key allocations. `ADMIN` may perform those operations
and may also create Games; Game creation is the current privileged operation. The
current endpoint policy is:

| Operation | `USER` | `ADMIN` |
| --- | --- | --- |
| `POST /api/games` | Forbidden | Allowed |
| `GET /api/games/{code}` | Allowed | Allowed |
| `POST /api/games/{gameCode}/allocations` | Allowed | Allowed |

JWTs contain the stable username subject, issued-at time, expiration, and a signed
`roles` authorization claim derived from the persisted identity. The resource server
converts that claim to the corresponding Spring Security authority only after normal
token validation. Because authorization state is carried in the token, a role in an
already-issued JWT remains effective until that token expires.

The access-token lifetime is configured by `JWT_ACCESS_TOKEN_LIFETIME` as a
whole-second duration from one second through 24 hours and defaults to `PT15M`. The
HMAC signing key is supplied through `JWT_SIGNING_KEY_BASE64` as Base64 representing
at least 32 bytes. No production key or usable fallback is committed; missing,
malformed, or inadequate signing configuration prevents startup. Tests generate
ephemeral signing material from test-only configuration.

Authorization continues to use the stateless Bearer-token model. The application
does not enable HTTP Basic, form login, cookies, or sessions.

## Request and input boundaries

The JSON request body for each current body-bearing operation is limited to 16 KiB
(16,384 bytes):

- `POST /api/auth/token`;
- `POST /api/games`;
- `POST /api/games/{gameCode}/allocations`.

The application measures the actual body bytes it consumes rather than trusting only
a client-supplied `Content-Length`. A body over the limit is rejected with
`413 Payload Too Large` and the normal safe `ApiError` JSON structure before its
controller or service operation runs. Authentication and authorization still happen
first on protected operations, so the existing `401 Unauthorized` and `403 Forbidden`
precedence and endpoint access rules are unchanged. Bodies at or below the limit
continue through normal JSON deserialization, Bean Validation, and controller/service
handling; malformed JSON within the limit retains Spring MVC's existing request-error
semantics.

Allocation `idempotencyKey` values must be non-blank and at most 255 characters. The
maximum matches the Flyway-owned `VARCHAR(255)` persistence boundary. Idempotency
keys retain their exact submitted value: the application does not trim, change case,
Unicode-normalize, hash, or otherwise canonicalize them.

More generally, this API preserves exact submitted usernames, passwords, Game codes,
and idempotency keys rather than applying generic automatic trimming or
canonicalization. Field validation may reject values outside the documented
boundaries, but it does not silently change identity, credential, lookup, or
idempotency semantics.

## Current abuse-control limitations

`POST /api/auth/token` is intentionally public. Incorrect passwords and unknown
usernames both produce the same `401 Unauthorized` response, so credential failures
do not reveal whether a username exists. Repeated rejected attempts can still consume
application resources and produce the existing bounded operational security event.
The application currently implements neither account lockout nor an application-owned
request rate limiter.

This limitation must be revisited when the deployment topology and public exposure
are established; it is not a claim that rate limiting is unnecessary. This issue does
not add a naive IP-address or in-memory limiter because trusted proxy and client-address
behavior has not been defined, and an in-process counter would not enforce a reliable
global limit across multiple application instances. Arbitrary account lockout could
also be abused to deny service to legitimate identities. A meaningful external or
application-level authentication policy should be selected once the deployment
topology and exposure are known.

Allocation abuse is a separate concern. Allocation requires an authenticated `USER`
or `ADMIN`, but an authorized caller can currently submit repeated allocation
requests. Reusing the same idempotency key returns the original allocation result;
idempotency is replay protection, not a general rate limit or entitlement control.
The one-key allocation transaction and database uniqueness invariants remain
authoritative for allocation correctness.

The application has no customer-entitlement, per-user quota, reservation, billing,
or allocation-abuse policy from which to derive a defensible limit. It therefore does
not invent a per-user quota or throttling threshold here. Any future allocation-abuse
control requires an intentional product or deployment rule. Account lockout,
proxy-aware or distributed rate limiting, gateway/WAF controls, and allocation quotas
remain deferred until concrete deployment or product requirements exist.

## Request correlation and operational logging

Every HTTP response includes `X-Request-Id`. The application generates a new value
for each request; a client-supplied `X-Request-Id` is neither trusted nor propagated.
The value is available to application-owned logging through request-scoped MDC while
the request is processed, and that context is removed when processing completes so a
reused servlet thread does not inherit the previous request's identifier.

Application-owned operational logging is intentionally limited to unexpected
application failures, rejected username/password authentication at
`POST /api/auth/token`, rejected Bearer authentication when a Bearer credential was
actually supplied, and authorization denials that produce `403 Forbidden`. Missing
credentials do not create a warning-level security event, and successful requests do
not create routine application access logs.

Those events use a bounded set of fields: a stable event category, request identifier,
HTTP method, request path, status, and, for an unexpected failure, the exception type.
They exclude submitted usernames and passwords, password hashes, Authorization header
values, Bearer tokens and copied JWT contents, JWT signing material, datasource
passwords and private environment values, request and response bodies, raw query
strings, complete headers, game-key codes, raw exception messages and cause messages,
Throwable dumps, SQL/database exception details, and machine-specific paths. This is
the contract for application-owned logging; it is not a universal guarantee about
third-party components under every possible logging configuration. Datasource and JWT
secrets remain externally supplied as described in
[Configuration profiles](CONFIGURATION_PROFILES.md).

## Public error responses

Covered API failures use one JSON structure:

| Field | Meaning |
| --- | --- |
| `timestamp` | Time when the response was created. |
| `status` | Numeric HTTP status. |
| `error` | Standard HTTP status phrase. |
| `message` | Safe public description of the failure category. |
| `path` | Request path. |
| `fieldErrors` | Field-specific Bean Validation messages, or `{}` for non-validation failures. |

The covered statuses are:

- `400 Bad Request` for an authorized request that fails Bean Validation;
- `401 Unauthorized` for missing or unacceptable Bearer authentication and for
  incorrect credentials at the token endpoint;
- `403 Forbidden` for valid authentication without sufficient authority, including
  unclassified routes denied by the secure default;
- `404 Not Found` when Game retrieval cannot find the requested Game;
- `413 Payload Too Large` when a JSON request body for a current body-bearing API
  operation exceeds 16,384 bytes;
- `500 Internal Server Error` for unexpected application failures without an
  established public error mapping.

Authentication failures intentionally do not distinguish an unknown username from an
incorrect password. Internal exception types, messages, causes, stack traces, SQL and
database details, credentials, tokens, signing material, secret game-key values,
private environment values, and machine-specific details are not part of the public
error contract. Unexpected failures produce a correlatable bounded server-side event
without logging their raw message, cause chain, or Throwable; the client still
receives only the generic public `500` response. The request identifier links these
two safe views without changing the JSON structure.
