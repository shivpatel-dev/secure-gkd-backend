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
- `500 Internal Server Error` for unexpected application failures without an
  established public error mapping.

Authentication failures intentionally do not distinguish an unknown username from an
incorrect password. Internal exception types, messages, causes, stack traces, SQL and
database details, credentials, tokens, signing material, secret game-key values,
private environment values, and machine-specific details are not part of the public
error contract. Unexpected failures retain server-side diagnostic handling while the
client receives only the generic public `500` response.
