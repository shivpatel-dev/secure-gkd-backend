# Secure GKD Backend

Secure GKD is a backend for distributing pre-provisioned game keys through an
authenticated HTTP API. It currently runs as one synchronous Java 17 Spring Boot
service backed by PostgreSQL. The service can authenticate pre-existing identities,
create and retrieve Games, and allocate an available GameKey with persisted
idempotency.

The allocation response is authoritative and synchronous. Docker Compose provides a
single local Kafka broker and a provisioned topic for future allocation events, but
the application has no Kafka client, event serialization/schema/compatibility
contract, transactional outbox, producer, consumer, audit service, or asynchronous
allocation path.

## Current capabilities

- Database-backed username/password authentication for pre-provisioned identities.
- Stateless, signed JWT Bearer authentication for protected requests.
- `USER` and `ADMIN` authorization with unclassified routes denied by default.
- ADMIN-only Game creation and USER-or-ADMIN Game retrieval.
- USER-or-ADMIN allocation of an already-provisioned GameKey.
- Exact-key idempotency: replaying an idempotency key returns its original allocation
  instead of consuming another GameKey.
- Flyway-owned PostgreSQL migrations with Hibernate schema validation.
- Bean Validation-backed request validation and structured public API error handling.
- Per-request `X-Request-Id` correlation and bounded application-owned
  operational/security logging.
- Generated OpenAPI JSON, Swagger UI, and a public application health endpoint.

## Architecture and correctness boundary

The application follows a conventional synchronous controller-service-repository
flow. Spring Security authenticates and authorizes the request, Spring MVC binds DTOs,
services own application behavior and transactions, and Spring Data JPA/Hibernate
access PostgreSQL. Controllers do not expose JPA entities as API responses.

`AllocationService.allocate` owns the allocation transaction. It checks for an
existing idempotency record, finds the requested Game and first available GameKey,
persists the Allocation and IdempotencyRecord, and constructs the response within the
same transaction. Persisted replay handling prevents an exact replay from consuming
another key. Candidate selection does not lock or reserve a GameKey, so PostgreSQL's
unique constraint allowing only one Allocation per GameKey is the final
duplicate-allocation protection.

Flyway creates and evolves the normal schema at startup. Hibernate is configured with
`ddl-auto: validate`, so it validates the migrated schema rather than creating or
updating it. See [Architecture](docs/ARCHITECTURE.md) for the full system and request
flows and [Architecture decisions](docs/DECISIONS.md) for their rationale and limits.

## Technology

- Java 17 and Spring Boot 3.5
- Spring MVC, Spring Security, and OAuth2 Resource Server
- Spring Data JPA, Hibernate, and HikariCP
- PostgreSQL (version 16 is the Compose, CI, and controlled deployment-verification
  baseline)
- Apache Kafka 4.3.1 in single-node KRaft mode for local infrastructure only
- Flyway database migrations
- springdoc OpenAPI and Swagger UI
- Maven Wrapper, Docker, and Docker Compose

## Quick start with Docker Compose

Docker with Compose support is the recommended self-contained local path; it builds
the application and starts it with PostgreSQL 16 and a single local Kafka broker.
Host Java, Maven, PostgreSQL, and Kafka installations are not required.

1. Copy the environment template to the untracked `.env` file:

   ```sh
   cp .env.example .env
   ```

   In PowerShell, use `Copy-Item .env.example .env` instead.

2. Set both required values in `.env`:

   ```dotenv
   SPRING_DATASOURCE_PASSWORD=<local-only-password>
   JWT_SIGNING_KEY_BASE64=<Base64-for-at-least-32-random-bytes>
   ```

   Do not commit `.env` or reuse example values as real secrets. Secret-generation
   guidance is in [Containerized local environment](docs/CONTAINERIZED_LOCAL_ENVIRONMENT.md).

3. Validate the Compose model without printing interpolated secrets, then build and
   start the environment:

   ```sh
   docker compose config --quiet
   docker compose build application
   docker compose up --detach
   ```

4. Check service state and the public application health endpoint:

   ```sh
   docker compose ps --all
   curl http://localhost:8080/api/health
   ```

   A ready application returns HTTP `200` with a JSON body containing
   `"status":"UP"`. This endpoint reports application HTTP health; it does not
   independently query PostgreSQL or Kafka. The `kafka` service should report
   healthy, and the one-shot `kafka-topic-init` service should exit successfully
   after ensuring that `secure-gkd.allocation-created` exists.

Compose-network Kafka clients use `kafka:9092`; host-side development tools use
`localhost:29092`, which is published only on the IPv4 loopback interface. Both
listeners are plaintext and intentionally local-only. The container guide covers
readiness and topic checks, connectivity diagnostics, logs, data retention, and
destructive local reset options. For host execution with Java 17, the Maven Wrapper,
and an independently running PostgreSQL database, use
[Application startup](docs/APPLICATION_STARTUP.md).

## Configuration and secrets

Runtime configuration is supplied externally. The repository does not provide usable
datasource-password or JWT-signing-key fallbacks.

| Variable | Responsibility |
| --- | --- |
| `SPRING_DATASOURCE_PASSWORD` | Required database password for local, test, and production-oriented execution. |
| `JWT_SIGNING_KEY_BASE64` | Required Base64 encoding of at least 32 bytes of signing material for runtime profiles. |
| `SPRING_DATASOURCE_URL` | Optional local override; required by the `test` and `prod` profiles. |
| `SPRING_DATASOURCE_USERNAME` | Optional local override; required by the `test` and `prod` profiles. |
| `JWT_ACCESS_TOKEN_LIFETIME` | Optional whole-second duration from one second through 24 hours; defaults to `PT15M`. |
| `SPRING_PROFILES_ACTIVE` | Selects the environment profile; `local` is the application default and deployments should select `prod`. |

Keep datasource credentials, signing material, user credentials, Bearer tokens, and
real game-key values out of source control, documentation, and public logs. See
[Configuration profiles](docs/CONFIGURATION_PROFILES.md),
[Security](docs/SECURITY.md), and
[Database migrations](docs/DATABASE_MIGRATIONS.md) for the authoritative boundaries.

## API, authentication, and authorization

The API authenticates credentials against persisted identities and returns a
short-lived HS256-signed JWT from `POST /api/auth/token`. Send that token as
`Authorization: Bearer <accessToken>` on protected requests. The application creates
no HTTP authentication session.

| Operation | Access |
| --- | --- |
| `GET /api/health` | Public |
| `POST /api/auth/token` | Public, for a pre-existing identity |
| `POST /api/games` | `ADMIN` |
| `GET /api/games/{code}` | `USER` or `ADMIN` |
| `POST /api/games/{gameCode}/allocations` | `USER` or `ADMIN` |

All unclassified routes are denied by default. There is no registration,
account-management, user-management, or role-management API, so identities and their
roles must be provisioned outside the HTTP API. There is also no public
GameKey-provisioning API. A successful allocation requires the target Game and an
available GameKey that was provisioned separately; creating a Game does not create a
GameKey.

With the application running, request a token for a pre-existing identity:

```sh
curl --request POST \
  --header "Content-Type: application/json" \
  --data '{"username":"REPLACE_WITH_USERNAME","password":"REPLACE_WITH_PASSWORD"}' \
  http://localhost:8080/api/auth/token
```

Then use the returned token and an existing Game that already has an available key:

```sh
curl --request POST \
  --header "Authorization: Bearer REPLACE_WITH_ACCESS_TOKEN" \
  --header "Content-Type: application/json" \
  --data '{"idempotencyKey":"REPLACE_WITH_UNIQUE_REQUEST_ID"}' \
  http://localhost:8080/api/games/REPLACE_WITH_GAME_CODE/allocations
```

Reusing the exact idempotency key returns the original allocation result. The complete
copyable exercise, including Game creation, retrieval, representative responses, and
failure cases, is in [Practical API examples](docs/API_EXAMPLES.md). Request details
and error semantics are in [API notes](docs/API_NOTES.md).

### OpenAPI and Swagger UI

Generated documentation is public while the application is running:

- OpenAPI JSON: `http://localhost:8080/v3/api-docs`
- Swagger UI: `http://localhost:8080/swagger-ui.html`

Swagger describes the current API and its Bearer-token requirements; it does not
provision the identities or GameKeys needed by protected examples.

## Testing and CI

Use the repository Maven Wrapper rather than a globally installed Maven executable:

```powershell
.\mvnw.cmd test
```

```sh
./mvnw test
```

Local test runs must receive the `test` profile's PostgreSQL datasource variables
through an untracked environment. Database and integration tests retain the configured
PostgreSQL datasource and do not substitute an embedded database.

GitHub Actions runs the test suite and package build with Java 17 and a PostgreSQL 16
service for pull requests targeting `main`. Passing local tests is not a claim that CI
has run; CI results remain separate evidence.

## Deployment contract and evidence

The multi-stage [Dockerfile](Dockerfile) is the provider-neutral deployment artifact.
It builds and runs the application with Java 17, places only the packaged JAR in the
runtime image, and runs as a non-root user. A deployment supplies the `prod` profile,
PostgreSQL connectivity and credentials, JWT signing material, effective port routing,
and platform operations externally. The full contract is in
[Deployment runtime](docs/DEPLOYMENT_RUNTIME.md).

Render was used for one bounded historical verification with one Web Service and one
PostgreSQL 16 database. That exercise observed clean migrations, authenticated API
behavior, allocation replay, and one controlled restart. It did not establish high
availability, autoscaling, disaster recovery, production traffic, or general
production reliability. The temporary Render application and database resources were
intentionally removed after verification, so this project does not claim a
continuously running public deployment. See
[Deployment verification](docs/DEPLOYMENT_VERIFICATION.md) for the evidence and
[Deployment runbook](docs/DEPLOYMENT_RUNBOOK.md) for the separate operator procedure.

## Known limitations

- No registration, account-management, user-management, role-management, password
  reset, or public identity-provisioning API.
- No public GameKey-provisioning API; allocation depends on separately provisioned
  inventory.
- No application-owned rate limiter, account lockout, distributed abuse control, or
  trusted-proxy/client-address policy.
- No allocation entitlement, billing, reservation, or per-user quota model.
- `GET /api/health` reports application HTTP health, not independent PostgreSQL
  readiness.
- The deployment evidence does not establish high availability, autoscaling, backup
  recovery, disaster recovery, production traffic, or general production reliability.

## Planned future direction

A later distributed-system phase may define and publish a versioned allocation event
using transactional-outbox reasoning and add one independent consumer with
idempotent event processing. The local broker and provisioned topic are implemented
infrastructure; the event contract, outbox, producer, consumer, and audit behavior
remain planned concepts. The present correctness boundary remains the synchronous
`AllocationService.allocate` transaction and its PostgreSQL constraints.
The accepted boundary, transaction model, delivery assumptions, and rejected
alternatives are recorded in
[Architecture decision 7](docs/DECISIONS.md#7-add-one-asynchronous-boundary-for-allocation-audit-processing).

## Documentation map

| Area | Documents |
| --- | --- |
| System design | [Architecture](docs/ARCHITECTURE.md) · [Architecture decisions](docs/DECISIONS.md) |
| Local startup | [Application startup](docs/APPLICATION_STARTUP.md) · [Containerized environment](docs/CONTAINERIZED_LOCAL_ENVIRONMENT.md) |
| Configuration and data | [Configuration profiles](docs/CONFIGURATION_PROFILES.md) · [Database migrations](docs/DATABASE_MIGRATIONS.md) |
| API and security | [API notes](docs/API_NOTES.md) · [Practical API examples](docs/API_EXAMPLES.md) · [Security](docs/SECURITY.md) |
| Deployment | [Runtime contract](docs/DEPLOYMENT_RUNTIME.md) · [Verification evidence](docs/DEPLOYMENT_VERIFICATION.md) · [Operator runbook](docs/DEPLOYMENT_RUNBOOK.md) |
