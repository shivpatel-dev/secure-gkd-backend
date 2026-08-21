# Containerized local environment

Docker Compose can build and run the backend with PostgreSQL 16 without a host Java,
Maven, or PostgreSQL installation. This workflow is for local development; it is not
a production deployment configuration.

## Prerequisites and local secrets

Install Docker with Compose support. From the repository root, copy `.env.example`
to an untracked `.env` file and fill both required blank values:

- `SPRING_DATASOURCE_PASSWORD` is the local password shared by PostgreSQL and the
  application.
- `JWT_SIGNING_KEY_BASE64` is Base64 for at least 32 bytes of signing material.

If OpenSSL is already installed, generate suitable local JWT material with:

```sh
openssl rand -base64 32
```

On Windows, either Windows PowerShell or modern PowerShell can generate the same
32-byte value without OpenSSL:

```powershell
$bytes = New-Object byte[] 32
$generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try {
    $generator.GetBytes($bytes)
    [Convert]::ToBase64String($bytes)
}
finally {
    $generator.Dispose()
}
```

Paste the generated output after `JWT_SIGNING_KEY_BASE64=` in `.env`. Never commit
`.env` or the generated value. `JWT_ACCESS_TOKEN_LIFETIME` is optional and retains
the existing `PT15M` default when omitted; accepted overrides remain whole seconds
from one second through 24 hours.

## Build and start

Validate the Compose model without rendering interpolated secret values, then build
and start both services:

```sh
docker compose config --quiet
docker compose build application
docker compose up --detach
```

The `database` service owns PostgreSQL 16 and the named `postgres-data` volume. Its
port is internal to the Compose network and is not published to the host. The
`application` service uses the existing `local` Spring profile and connects to
`jdbc:postgresql://database:5432/secure_gkd` as `secure_gkd_user`. Compose waits for
the PostgreSQL readiness check before starting the application.

The application image uses Java 17. Its build stage invokes the repository Maven
Wrapper, and the runtime stage contains the packaged Spring Boot application without
the Maven build toolchain.

On a new volume, application startup runs the existing Flyway migrations before
Hibernate validates the resulting schema. No manual schema SQL or seeded identity is
part of this workflow.

## Verify and troubleshoot

Check service state and the public health endpoint on the published application port:

```sh
docker compose ps
curl http://localhost:8080/api/health
```

The health response should report `"status":"UP"`. If startup does not complete,
inspect bounded recent logs rather than exposing the rendered Compose configuration:

```sh
docker compose logs --tail 100 database application
```

## Stop, retain, or reset data

Normal shutdown removes the containers and network but retains the named PostgreSQL
volume, so the next startup reuses local database state:

```sh
docker compose down
docker compose up --detach
```

To deliberately discard local database data and reproduce a clean Flyway-backed
startup, remove the Compose volume and then start again:

```sh
docker compose down --volumes
docker compose up --detach
```

Volume deletion is destructive. Use `docker compose down --volumes` only when the
local database data is disposable. After either startup, use `docker compose ps` and
the health request above to verify readiness.
