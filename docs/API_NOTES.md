# API notes

## Authentication

`POST /api/auth/token` accepts a JSON body containing `username` and `password`.
Correct credentials return `accessToken`, `tokenType` (`Bearer`), and
`expiresInSeconds`. Send the token to protected `/api/games/**` routes with the
standard `Authorization: Bearer <accessToken>` header. `GET /api/health` and the
token endpoint are public; other unclassified routes remain denied.

Authentication identities are stored separately from game and allocation state.
Passwords must be encoded with the application's Spring Security `PasswordEncoder`;
neither plaintext nor encoded passwords belong in API responses or JWT claims. No
default production identity is committed, and this API does not provide registration
or user-management endpoints.

JWTs contain only a stable username subject, issued-at time, and expiration. The
access-token lifetime is configured by `JWT_ACCESS_TOKEN_LIFETIME` as a whole-second
duration from one second through 24 hours and defaults to `PT15M`. The HMAC signing
key is supplied through `JWT_SIGNING_KEY_BASE64` as Base64 representing at least 32
bytes. No production key or usable fallback is committed; missing, malformed, or
inadequate signing configuration prevents startup. Tests generate ephemeral signing
material from test-only configuration.

The application remains stateless and does not enable HTTP Basic, form login,
cookies, or sessions. This authentication change does not introduce roles,
authorities, or role-based authorization policy.
