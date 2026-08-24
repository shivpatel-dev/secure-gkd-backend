# Render deployment verification

This document records bounded, non-secret evidence from deploying the existing
Secure GKD application to Render on August 24, 2026. It is an evidence record,
not a deployment or rollback runbook. The provider-neutral application contract
remains documented in [Deployment runtime](DEPLOYMENT_RUNTIME.md).

## Evidence model

The statements below distinguish three evidence categories:

- **Repository-defined behavior** comes from the deployed revision's Dockerfile,
  Spring configuration, migrations, security rules, and API implementation.
- **Directly observed Render behavior** comes from the managed resources, bounded
  platform logs, and HTTPS API responses exercised during this verification.
- **Not exercised** identifies behavior for which this deployment provides no
  evidence and therefore makes no claim.

The exact deployed revision was
`94e62c9bd4779568db32f48fe8d54b3216c36d5a`. Render deployment history showed
that revision as `94e62c9` with the reviewed deployment-runtime preparation
change. Auto-deploy was disabled for this verification.

## Deployment shape

| Property | Verified value |
| --- | --- |
| Platform | Render |
| Application resource | One Render Web Service, `secure-gkd-backend` |
| Database resource | One Render Postgres database, `secure-gkd-postgres` |
| Region | Singapore (Southeast Asia) for both resources |
| PostgreSQL | Major version 16; runtime logs reported 16.14 |
| Application build | Existing repository `Dockerfile` |
| Java runtime | Repository-defined Java 17 image; restart logs reported Java 17.0.20 |
| Deployed branch and revision | `main` at `94e62c9bd4779568db32f48fe8d54b3216c36d5a` |
| Effective application port | `10000` |
| Render health-check path | `/api/health` |
| Verification URL | `https://secure-gkd-backend.onrender.com` |

The existing multi-stage Docker build and exec-form container entry point were
used. No alternate Render build, custom startup command, Blueprint, deployment
pipeline, or second application deployment model was introduced.

## External configuration

The Render Web Service externally supplied these configuration names:

- `SPRING_PROFILES_ACTIVE=prod`
- `SPRING_DATASOURCE_URL`
- `SPRING_DATASOURCE_USERNAME`
- `SPRING_DATASOURCE_PASSWORD`
- `JWT_SIGNING_KEY_BASE64`
- `SERVER_PORT=10000`

`JWT_ACCESS_TOKEN_LIFETIME` was not overridden, so the repository-defined
`PT15M` default remained effective. No datasource credential, private database
endpoint, JWT signing material, authentication credential, token, or other raw
private environment value is recorded here. The configuration responsibilities
are defined in [Configuration profiles](CONFIGURATION_PROFILES.md).

## Initial startup and managed database

The following behavior was directly observed during initial startup against a
clean Render Postgres database:

- the application activated the `prod` profile;
- Tomcat initialized on port `10000`;
- HikariCP established a PostgreSQL connection;
- Flyway connected to PostgreSQL 16.14;
- the Flyway schema-history table was initially absent and the schema was empty;
- Flyway validated the three tracked migrations, created its history state, and
  applied V1, V2, and V3 in order;
- the schema reached version 3;
- Hibernate ORM and the JPA entity manager initialized successfully; and
- Tomcat started and Render ultimately marked the service live.

The deployed revision defines Flyway as schema owner, disables automatic
baselining, and configures Hibernate with `ddl-auto: validate`. Successful startup
after the clean migration provides combined repository and runtime evidence that
the migrated schema satisfied Hibernate validation; Hibernate did not replace
Flyway by creating or updating the normal schema. See
[Database migrations](DATABASE_MIGRATIONS.md) for the repository-defined ownership
boundary.

Render temporarily reported that no open ports were detected while the free-tier
instance was still starting. The final state was a detected port `10000` and a live
service. Observed cold starts took roughly two minutes, so this verification does
not imply an always-on service or a fixed startup duration.

## HTTPS health verification

The public HTTPS endpoint returned HTTP `200` with `status` equal to `UP` before
the controlled restart. After the controlled restart, it again returned HTTP `200`
with `status` equal to `UP`. The observed response timestamps were
`2026-08-24T13:25:38.473081293Z` and
`2026-08-24T15:51:59.430855062Z`, respectively.

`GET /api/health` is an application HTTP reachability check. Its controller does
not independently query PostgreSQL, so these responses do not by themselves prove
current database availability. Database connectivity evidence instead came from
startup logs and successful PostgreSQL-backed operations.

## Authentication and authorization

One temporary `ADMIN` identity and one temporary `USER` identity were inserted
directly into the managed database because the application intentionally has no
registration or user-management API. Their passwords remained transient and
untracked. Passwords were encoded compatibly with the application's existing
Spring Security delegating password encoder and bcrypt identifier. Neither
plaintext credentials nor password hashes are recorded here.

Direct HTTPS verification produced these results:

| Operation | Observed result |
| --- | --- |
| Correct `ADMIN` credentials at `POST /api/auth/token` | HTTP `200`; a token was present, `tokenType` was `Bearer`, and `expiresInSeconds` was `900` |
| Incorrect credentials at `POST /api/auth/token` | HTTP `401`; structured `Unauthorized` response with `Authentication required` |
| Anonymous `GET` of a protected Game route | HTTP `401`; structured `Unauthorized` response with `Authentication required` |
| Correct `USER` credentials at `POST /api/auth/token` | HTTP `200`; a Bearer token was present and `expiresInSeconds` was `900` |
| Authenticated `USER` attempting `POST /api/games` | HTTP `403`; structured `Forbidden` response with `Access denied` |

No issued Bearer token was retained in this evidence. The observed role behavior
matches the repository-defined policy in [API notes](API_NOTES.md).

## Protected Game and allocation behavior

Using the temporary `ADMIN` identity, `POST /api/games` created one verification
Game with code `RENDER-VERIFY-89` and title `Render Verification Game 89`. The
response contained ID `1` and creation time
`2026-08-24T14:42:25.163557Z`. Using the temporary `USER` identity,
`GET /api/games/RENDER-VERIFY-89` returned the same persisted Game fields.

Because the application has no GameKey-provisioning API, exactly one temporary
GameKey was inserted directly into PostgreSQL for that Game. It was not added to
a migration or repository seed. Its value was generated in PostgreSQL and is not
recorded here.

The first `POST /api/games/RENDER-VERIFY-89/allocations` request returned HTTP
`201`, the expected Game code, a present key value, and allocation time
`2026-08-24T14:45:44.909684Z`. Repeating the request with the same temporary
idempotency key also returned HTTP `201` with the same Game code, secret key, and
allocation timestamp. This directly demonstrated that the replay returned the
original allocation rather than consuming another GameKey. The secret key value
is intentionally omitted.

## Controlled failure responses

The following safe failures were exercised through the public HTTPS service:

- a blank allocation `idempotencyKey` returned HTTP `400`, `Bad Request`,
  `Validation failed`, and the field error `idempotencyKey is required`;
- incorrect credentials returned the structured HTTP `401` response described
  above;
- anonymous protected access returned the structured HTTP `401` response described
  above;
- a `USER` calling the `ADMIN`-only Game creation endpoint returned the structured
  HTTP `403` response described above; and
- an authenticated request for a missing Game returned HTTP `404`, `Not Found`,
  with `Game not found`.

No destructive database failure or manufactured HTTP `500` response was triggered.

## Controlled restart and persistence

One Render **Restart service** action was exercised without intentionally changing
the deployed revision or configuration. Shutdown logs showed Spring Boot graceful
shutdown completing, the JPA entity manager factory closing, and the Hikari pool
shutting down.

On restart, bounded logs showed:

- Java 17.0.20 and the `prod` profile;
- Tomcat initialized on port `10000`;
- HikariCP re-established a PostgreSQL connection;
- Flyway connected to PostgreSQL 16.14 and validated all three migrations;
- the current schema was version 3 and up to date, so no migration was necessary;
- Hibernate ORM 6.6.49.Final and the JPA entity manager initialized successfully;
- the authentication manager initialized; and
- Tomcat and the application started successfully.

The observed restart startup time was about 110 seconds. After restart, the health
endpoint returned `UP`, `USER` authentication again issued a Bearer token, the
previously created Game retained its original fields, and replaying the original
idempotency key returned the same original allocation timestamp and key. This is
direct evidence that the application restart did not erase PostgreSQL-backed state
and that the persisted idempotency record remained effective. It is evidence from
one controlled restart, not a general availability or recovery guarantee.

## Log and privacy boundary

Render events and bounded console logs were inspected for initial deployment,
migration, application startup, HTTP availability, graceful shutdown, and restart.
This document records only the non-secret observations needed to support the
deployment claims. It excludes private database endpoints, datasource credentials,
temporary inbound addresses, authentication passwords and hashes, JWT signing
material, Bearer tokens, secret GameKey values, raw environment values, and private
Render identifiers. No screenshots are part of the tracked evidence.

The repository-defined application logging boundary is documented in
[Security](SECURITY.md#request-correlation-and-application-owned-logging).

## Limitations and unexercised behavior

This verification exercised one Render Web Service, one same-region managed
PostgreSQL database, one clean migration, bounded functional API checks, and one
controlled application restart. It did not exercise or establish:

- high availability, autoscaling, zero-downtime behavior, or production traffic;
- production reliability or a continuously warm instance;
- load, stress, or concurrency behavior on Render;
- database backup, restore, or disaster recovery;
- deployment rollback;
- production monitoring, alerting, tracing, or log aggregation; or
- broader cloud or multi-provider operation.

Free-tier cold-start behavior was observed, including startup times around two
minutes. The health endpoint is not an independent PostgreSQL health probe.

## Resource state

The temporary external PostgreSQL inbound `/32` rule used solely for verification
was removed after database fixture work completed. After successful deployment
verification and documentation review, the temporary Render Web Service
`secure-gkd-backend` and Render Postgres resource `secure-gkd-postgres` were
intentionally removed because continued hosting had no intended demonstration
purpose. The temporary database fixtures were not retained because the database
resource containing them was removed.
