# Secure GKD Backend

Secure GKD distributes pre-provisioned game keys through an authenticated HTTP API.
The repository contains two independently deployable Java 17 Spring Boot services:
the authoritative synchronous allocation application and a Kafka-driven allocation-
audit background service. Each owns a separate PostgreSQL database.

The allocation response is authoritative and synchronous. For each new Allocation,
the allocation transaction creates the application-owned version-1
`AllocationCreated` event intent and persists it in the `allocation_outbox` table.
When enabled, the background publisher delivers committed pending intents to the
existing `secure-gkd.allocation-created` Kafka topic. Docker Compose enables that
publisher against its single local broker; standalone allocation runtime remains
publisher-disabled by default. The audit service consumes version-1 events
independently and persists downstream audit records without joining the synchronous
allocation path.

## Current capabilities

- Database-backed username/password authentication for pre-provisioned identities.
- Stateless, signed JWT Bearer authentication for protected requests.
- `USER` and `ADMIN` authorization with unclassified routes denied by default.
- ADMIN-only Game creation and USER-or-ADMIN Game retrieval.
- USER-or-ADMIN allocation of an already-provisioned GameKey.
- Exact-key idempotency: replaying an idempotency key returns its original allocation
  instead of consuming another GameKey.
- Atomic persistence of one application-owned, version-1 `AllocationCreated` outbox
  intent for each new Allocation, excluding secret GameKey and idempotency-key data.
- Optional asynchronous, acknowledged, at-least-once Kafka publication of pending
  outbox intents using their persisted event UUID and exact stored JSON payload.
- Independent background consumption with explicit version/key validation, record
  acknowledgement, and idempotent audit-owned PostgreSQL/Flyway persistence keyed by
  the stable source event UUID.
- Separate allocation and audit service images, database identities, schemas, and
  persistent volumes; neither service accesses the other's tables.
- Flyway-owned PostgreSQL migrations with Hibernate schema validation.
- Bean Validation-backed request validation and structured public API error handling.
- Per-request `X-Request-Id` correlation and bounded application-owned
  operational/security logging.
- Generated OpenAPI JSON, Swagger UI, and a public application health endpoint.

## Architecture and correctness boundary

The allocation application follows a conventional synchronous controller-service-repository
flow. Spring Security authenticates and authorizes the request, Spring MVC binds DTOs,
services own application behavior and transactions, and Spring Data JPA/Hibernate
access PostgreSQL. Controllers do not expose JPA entities as API responses.

`AllocationService.allocate` owns the allocation transaction. It checks for an
existing idempotency record, finds the requested Game and first available GameKey,
persists the Allocation, IdempotencyRecord, and serialized `AllocationCreated` outbox
intent, and constructs the response within the same transaction. Persisted replay
handling prevents an exact replay from consuming another key or creating another
event intent. Candidate selection does not lock or reserve a GameKey, so PostgreSQL's
unique constraint allowing only one Allocation per GameKey is the final
duplicate-allocation protection.

The audit application has no public HTTP API. It consumes string-keyed JSON from
`secure-gkd.allocation-created` with group `secure-gkd-allocation-audit`, starts a new
group at `earliest`, disables auto-commit, validates schema version 1 and matching
event UUIDs, and persists each accepted record in one audit database transaction.
Contract failures go directly to `secure-gkd.allocation-created.dlt`; retryable
failures receive at most two retries with a fixed one-second delay before the same
recovery path. PostgreSQL uniqueness on the retained source event UUID makes a
repeated logical event a successful no-op while preserving the original audit
record. Audit state is eventually consistent and does not affect allocation success.

Flyway creates and evolves the normal schema at startup. Hibernate is configured with
`ddl-auto: validate`, so it validates the migrated schema rather than creating or
updating it. See [Architecture](docs/ARCHITECTURE.md) for the full system and request
flows and [Architecture decisions](docs/DECISIONS.md) for their rationale and limits.

## Technology

- Java 17 and Spring Boot 3.5
- Spring MVC, Spring Security, and OAuth2 Resource Server
- Spring Data JPA, Hibernate, and HikariCP
- Spring Kafka producer and independent consumer support
- PostgreSQL (version 16 is the Compose, CI, and controlled deployment-verification
  baseline)
- Apache Kafka 4.3.1 in single-node KRaft mode for local infrastructure only
- Flyway database migrations
- springdoc OpenAPI and Swagger UI
- Maven Wrapper, Docker, and Docker Compose

## Quick start with Docker Compose

Docker with Compose support is the recommended self-contained local path; it builds
both applications and starts them with separate PostgreSQL 16 databases and one local Kafka broker.
Host Java, Maven, PostgreSQL, and Kafka installations are not required.

1. Copy the environment template to the untracked `.env` file:

   ```sh
   cp .env.example .env
   ```

   In PowerShell, use `Copy-Item .env.example .env` instead.

2. Set all three required values in `.env`:

   ```dotenv
   SPRING_DATASOURCE_PASSWORD=<local-only-password>
   AUDIT_DATASOURCE_PASSWORD=<different-local-only-password>
   AUDIT_DATABASE_HOST_PORT=55432
   JWT_SIGNING_KEY_BASE64=<Base64-for-at-least-32-random-bytes>
   ```

   Do not commit `.env` or reuse example values as real secrets. Secret-generation
   guidance is in [Containerized local environment](docs/CONTAINERIZED_LOCAL_ENVIRONMENT.md).

3. Validate the Compose model without printing interpolated secrets, then build and
   start the environment:

   ```sh
   docker compose config --quiet
   docker compose build application audit-service
   docker compose up --detach
   ```

4. Check service state and the public application health endpoint:

   ```sh
   docker compose ps --all
   curl http://localhost:8080/api/health
   ```

   A ready application returns HTTP `200` with a JSON body containing
   `"status":"UP"`. This endpoint reports application HTTP health; it does not
   independently query PostgreSQL, Kafka, or audit state. Both databases and Kafka
   should report healthy, both applications should be running, and the one-shot
   `kafka-topic-init` service should exit successfully after ensuring that
   `secure-gkd.allocation-created` and `secure-gkd.allocation-created.dlt` exist.

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
| `AUDIT_DATASOURCE_URL` | Optional audit-service override; defaults to the loopback audit database using `AUDIT_DATABASE_HOST_PORT`. |
| `AUDIT_DATASOURCE_USERNAME` | Optional audit-service override; defaults to `secure_gkd_audit_user`. |
| `AUDIT_DATASOURCE_PASSWORD` | Required audit-owned database password with no fallback. |
| `AUDIT_DATABASE_HOST_PORT` | Optional loopback host port for Compose and direct-host audit execution; defaults to `55432`. Container-side PostgreSQL remains on `5432`. |
| `AUDIT_DATABASE_SCHEMA` | Optional audit schema override; defaults to `public` in the separately owned audit database. |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | Kafka address; Compose supplies `kafka:9092` to both asynchronous components. |
| `AUDIT_KAFKA_TOPIC` | Audit topic; defaults to `secure-gkd.allocation-created`. |
| `AUDIT_KAFKA_CONSUMER_GROUP` | Stable audit group; defaults to `secure-gkd-allocation-audit`. |
| `AUDIT_KAFKA_DEAD_LETTER_TOPIC` | Audit dead-letter topic; defaults to `secure-gkd.allocation-created.dlt`. |
| `AUDIT_KAFKA_RETRY_ATTEMPTS` | Retry attempts after the initial retryable failure; defaults to `2`. |
| `AUDIT_KAFKA_RETRY_BACKOFF` | Fixed delay between retryable attempts; defaults to `1s`. |
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
.\mvnw.cmd -f audit-service\pom.xml test
.\mvnw.cmd package
.\mvnw.cmd -f audit-service\pom.xml package
.\mvnw.cmd -f integration-tests\pom.xml verify
```

```sh
./mvnw test
./mvnw -f audit-service/pom.xml test
./mvnw package
./mvnw -f audit-service/pom.xml package
./mvnw -f integration-tests/pom.xml verify
```

Local allocation test/package runs require the three `SPRING_DATASOURCE_*` values,
and local audit test/package runs require `AUDIT_DATASOURCE_PASSWORD` plus any
intended `AUDIT_DATASOURCE_URL`, `AUDIT_DATASOURCE_USERNAME`,
`AUDIT_DATABASE_HOST_PORT`, or `AUDIT_DATABASE_SCHEMA` overrides. Supply them through
an untracked environment. The audit module excludes ambient allocation
`SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, and
`SPRING_DATASOURCE_PASSWORD` variables from its forked test JVM and runs that JVM in
UTC, so the commands above remain reproducible when both services' variables coexist
in the calling shell. Database and integration tests retain their configured
service-owned PostgreSQL datasources and do not substitute an embedded database.

The asynchronous integration command requires Docker and the two packaged service
JARs produced by the preceding package commands. It creates one real Kafka broker and
two disposable PostgreSQL 16 containers, starts each application artifact as a
separate JVM, and removes the isolated container state when the suite ends. The suite
uses only synthetic fixtures and bounded eventual assertions; it does not use the
developer Compose volumes or an external broker or database.

GitHub Actions runs both test suites and package builds with Java 17 and separate
PostgreSQL 16 services for pull requests targeting `main`, then runs the asynchronous
integration suite against disposable Kafka and PostgreSQL containers. Focused audit
tests still do not require Kafka. Passing local tests is not a claim that CI has run;
CI results remain separate evidence.

## Deployment contract and evidence

The root [Dockerfile](Dockerfile) and
[audit-service/Dockerfile](audit-service/Dockerfile) are separate provider-neutral
deployment artifacts. Each builds one Java 17 application, places only its packaged
JAR in the runtime image, and runs as a non-root user. An allocation deployment supplies the `prod` profile,
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
- Kafka publication and delivery may repeat. The audit consumer uses the stable
  `eventId` and audit-owned PostgreSQL uniqueness to suppress repeated audit effects,
  but the database transaction and Kafka offset are not one distributed transaction.
  There is no exactly-once end-to-end guarantee.
- The audit consumer retries retryable processing failures twice with a fixed
  one-second delay, then publishes the original record to its dead-letter topic.
  Contract/key failures bypass retry. Automated dead-letter replay is not provided;
  operators inspect and deliberately republish corrected records when appropriate.
- The deployment evidence does not establish high availability, autoscaling, backup
  recovery, disaster recovery, production traffic, or general production reliability.

## Distributed-system documentation

[Architecture](docs/ARCHITECTURE.md) is the primary reviewer path for the implemented
request-to-audit flow, service and database ownership, delivery and failure semantics,
tradeoffs, deployment-evidence boundary, and links to repository evidence. The
[AllocationCreated event contract](docs/ALLOCATION_CREATED_EVENT.md) is authoritative
for version-1 fields, compatibility, identity, ownership, and sensitive-data limits.
[Architecture decision 7](docs/DECISIONS.md#7-add-one-asynchronous-boundary-for-allocation-audit-processing)
preserves why the core allocation domain remains together and why audit is the one
asynchronous service boundary. Automated dead-letter replay is not implemented.
Pending and published outbox rows, dead-letter records, and audit rows have no
application-owned automatic retention or cleanup policy.

## Documentation map

| Area | Documents |
| --- | --- |
| System design | [Architecture](docs/ARCHITECTURE.md) · [Architecture decisions](docs/DECISIONS.md) · [AllocationCreated event contract](docs/ALLOCATION_CREATED_EVENT.md) |
| Local startup | [Application startup](docs/APPLICATION_STARTUP.md) · [Containerized environment](docs/CONTAINERIZED_LOCAL_ENVIRONMENT.md) |
| Configuration and data | [Configuration profiles](docs/CONFIGURATION_PROFILES.md) · [Database migrations](docs/DATABASE_MIGRATIONS.md) |
| API and security | [API notes](docs/API_NOTES.md) · [Practical API examples](docs/API_EXAMPLES.md) · [Security](docs/SECURITY.md) |
| Deployment | [Runtime contract](docs/DEPLOYMENT_RUNTIME.md) · [Verification evidence](docs/DEPLOYMENT_VERIFICATION.md) · [Operator runbook](docs/DEPLOYMENT_RUNBOOK.md) |
