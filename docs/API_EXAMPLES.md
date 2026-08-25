# Practical API examples

These examples exercise the HTTP API that exists today. For the detailed authority
on authentication, authorization, request limits, public errors, request correlation,
and abuse-control boundaries, see [API notes](API_NOTES.md).

With the application running, the generated OpenAPI JSON is available at
`/v3/api-docs` and Swagger UI is available at `/swagger-ui.html`.

## Prerequisites and placeholders

The application has no registration, account-management, user-management, or
role-management API. Protected examples require a pre-existing identity with the
stated `USER` or `ADMIN` role. These prerequisites do not authorize adding default
users, credentials, or production seed data.

The application also has no GameKey-provisioning API. A successful allocation
requires the target Game and at least one available GameKey to already exist. Creating
a Game through the API does not provision a GameKey. Do not substitute a real GameKey
secret into this guide or other tracked files.

The commands use POSIX-style shell variables. Set the base URL for the running
instance you intentionally want to exercise:

```sh
BASE_URL="http://localhost:8080"
```

Replace every `REPLACE_WITH_...` value before running a command. Do not save real
credentials or returned tokens in tracked files.

## Public health check

```sh
curl --request GET \
  --header "Accept: application/json" \
  "${BASE_URL}/api/health"
```

A successful response has this shape:

```json
{
  "status": "UP",
  "service": "secure-gkd-backend",
  "timestamp": "2026-01-02T03:04:05Z"
}
```

## Request and use a Bearer token

Supply credentials for a pre-existing identity:

```sh
curl --request POST \
  --header "Content-Type: application/json" \
  --data '{"username":"REPLACE_WITH_PREEXISTING_USERNAME","password":"REPLACE_WITH_PASSWORD"}' \
  "${BASE_URL}/api/auth/token"
```

The response contains an access token, its Bearer token type, and its lifetime:

```json
{
  "accessToken": "REPLACE_WITH_RETURNED_ACCESS_TOKEN",
  "tokenType": "Bearer",
  "expiresInSeconds": 900
}
```

Copy the returned `accessToken` into a shell variable for the protected examples:

```sh
ACCESS_TOKEN="REPLACE_WITH_RETURNED_ACCESS_TOKEN"
```

The configured lifetime can differ from the representative response above.

## Create a Game as ADMIN

Game creation requires an `ADMIN` token. Substitute a new code appropriate for the
instance being exercised:

```sh
curl --request POST \
  --header "Authorization: Bearer ${ACCESS_TOKEN}" \
  --header "Content-Type: application/json" \
  --data '{"code":"REPLACE_WITH_NEW_GAME_CODE","title":"Synthetic Demonstration Game"}' \
  "${BASE_URL}/api/games"
```

A successful response is `201 Created` and has this shape:

```json
{
  "id": 1,
  "code": "REPLACE_WITH_NEW_GAME_CODE",
  "title": "Synthetic Demonstration Game",
  "createdAt": "2026-01-02T03:04:05Z"
}
```

## Retrieve a Game as USER or ADMIN

Game retrieval accepts either a `USER` or `ADMIN` token:

```sh
GAME_CODE="REPLACE_WITH_EXISTING_GAME_CODE"

curl --request GET \
  --header "Authorization: Bearer ${ACCESS_TOKEN}" \
  --header "Accept: application/json" \
  "${BASE_URL}/api/games/${GAME_CODE}"
```

## Allocate an available GameKey as USER or ADMIN

Use a new idempotency key for a new logical request. The target Game must already
have an available GameKey provisioned outside this HTTP API:

```sh
curl --request POST \
  --header "Authorization: Bearer ${ACCESS_TOKEN}" \
  --header "Content-Type: application/json" \
  --data '{"idempotencyKey":"REPLACE_WITH_NEW_UNIQUE_REQUEST_ID"}' \
  "${BASE_URL}/api/games/${GAME_CODE}/allocations"
```

A successful response is `201 Created` and has this shape:

```json
{
  "gameCode": "REPLACE_WITH_EXISTING_GAME_CODE",
  "keyCode": "SYNTHETIC-KEY-NOT-VALID",
  "allocatedAt": "2026-01-02T03:04:05Z"
}
```

Repeating the request with the exact same `idempotencyKey` returns the original
allocation result instead of allocating another GameKey. Idempotency keys are
preserved exactly; use a distinct value for each new logical allocation request.

## Representative failures

The following commands intentionally fail. Error bodies use the common `ApiError`
structure described in [API notes](API_NOTES.md#public-error-responses).

### Authentication failure: 401

Unknown identities and incorrect passwords intentionally produce indistinguishable
responses:

```sh
curl --request POST \
  --header "Content-Type: application/json" \
  --data '{"username":"REPLACE_WITH_PREEXISTING_USERNAME","password":"REPLACE_WITH_INCORRECT_PASSWORD"}' \
  "${BASE_URL}/api/auth/token"
```

### Authorization failure: 403

Use a token for a pre-existing `USER` identity to demonstrate that only `ADMIN` can
create Games:

```sh
USER_ACCESS_TOKEN="REPLACE_WITH_RETURNED_USER_ACCESS_TOKEN"

curl --request POST \
  --header "Authorization: Bearer ${USER_ACCESS_TOKEN}" \
  --header "Content-Type: application/json" \
  --data '{"code":"REPLACE_WITH_NEW_GAME_CODE","title":"Synthetic Demonstration Game"}' \
  "${BASE_URL}/api/games"
```

### Validation failure: 400

Use an `ADMIN` token with blank required Game fields:

```sh
ADMIN_ACCESS_TOKEN="REPLACE_WITH_RETURNED_ADMIN_ACCESS_TOKEN"

curl --request POST \
  --header "Authorization: Bearer ${ADMIN_ACCESS_TOKEN}" \
  --header "Content-Type: application/json" \
  --data '{"code":"","title":""}' \
  "${BASE_URL}/api/games"
```

### Missing Game: 404

Use a `USER` or `ADMIN` token with a code known not to exist in the target instance:

```sh
curl --request GET \
  --header "Authorization: Bearer ${ACCESS_TOKEN}" \
  --header "Accept: application/json" \
  "${BASE_URL}/api/games/REPLACE_WITH_MISSING_GAME_CODE"
```

The generated OpenAPI reference lists the other covered errors only on operations
where they apply, including request bodies over the 16,384-byte limit and generic
unexpected application failures.
