# Deployment runtime

This document defines the provider-neutral runtime contract for deploying the
existing application container. It does not select a hosting provider or define
provider-specific infrastructure.

## Deployment artifact and Java runtime

The repository `Dockerfile` is the single deployment build. Its Java 17 build stage
uses the repository Maven Wrapper to package the Spring Boot application. The final
Java 17 JRE image receives only the packaged application, so Maven, the source tree,
and other build tooling remain outside the runtime image.

The final image creates a dedicated `securegkd` user and group with numeric ID
`10001` and runs the exec-form Java entry point as `10001:10001`. The packaged JAR is
owned by that identity. The process does not require root, a writable application
directory, additional Linux capabilities, or another operating-system privilege.
Environment-specific configuration and secrets must remain outside the image.

## Required runtime configuration

The deployment environment must explicitly supply these variables:

| Variable | Runtime responsibility |
| --- | --- |
| `SPRING_PROFILES_ACTIVE=prod` | Selects the production-oriented profile. The shared image does not bake in profile activation. |
| `SPRING_DATASOURCE_URL` | Complete JDBC URL for the externally managed PostgreSQL database. The `prod` profile has no local fallback. |
| `SPRING_DATASOURCE_USERNAME` | PostgreSQL identity used for startup migrations and normal persistence. The `prod` profile has no local fallback. |
| `SPRING_DATASOURCE_PASSWORD` | Password for that PostgreSQL identity. There is no repository fallback. |
| `JWT_SIGNING_KEY_BASE64` | Base64 for at least 32 bytes of externally managed JWT signing material. There is no repository fallback. |

`JWT_ACCESS_TOKEN_LIFETIME` is optional. When omitted, it retains the existing
`PT15M` default. An override must be a whole number of seconds from one second
through 24 hours, as described in [Security](SECURITY.md#jwt-boundary).

These values must be supplied through the selected runtime's external configuration
mechanism. Do not place credentials, signing material, access tokens, secret game-key
values, or reusable example secrets in the image, repository, deployment evidence,
or command output.

## Application port and traffic

The application and container use port `8080` by default. The deployment environment
must route traffic to the effective application port. Standard Spring Boot external
configuration can set a different application port with `SERVER_PORT`; the runtime
must then route traffic to that value instead. This contract does not define a
provider-specific `PORT` variable or mapping.

## PostgreSQL startup and schema ownership

PostgreSQL connectivity, provisioning, network reachability, and credentials belong
to the deployment environment. A controlled deployment-like verification of this
contract must use PostgreSQL 16, matching the repository's Compose and CI baseline.

At startup, the application connects using the external datasource configuration.
Flyway validates its schema-history table and applied migration checksums, then
applies pending versioned migrations from `classpath:db/migration`. Automatic
baselining remains disabled. After Flyway completes, Hibernate validates the
resulting schema with `ddl-auto: validate`; Hibernate does not create or update the
normal application schema.

The configured database identity must have the permissions needed to create and
update Flyway's schema-history table, apply every pending versioned migration, and
perform the application's normal persistence operations. The required privileges
depend on the tracked migrations and the database environment; this contract does
not invent provider-specific privilege, TLS, backup, or network requirements. See
[Database migrations](DATABASE_MIGRATIONS.md) for schema ownership and explicit
existing-schema adoption rules.

Startup is successful only after required configuration is accepted, PostgreSQL is
reachable, Flyway has reached the expected migrated state, Hibernate validation has
succeeded, and the HTTP server is listening on the effective port. Detailed profile
and startup behavior remains in [Configuration profiles](CONFIGURATION_PROFILES.md)
and [Application startup](APPLICATION_STARTUP.md).

## Health check

`GET /api/health` is public and is the current HTTP/application health-check
candidate. An HTTP `200` response containing `"status":"UP"` demonstrates that the
application is running and serving HTTP. The controller does not independently query
PostgreSQL, so this response is not proof of current database availability. A
deployment must not describe it as a database readiness check.

## Logs and privacy

Spring Boot writes normal application and startup logs to standard console output;
the container runtime exposes that output as the deployment log stream. The
application does not write container-local log files and this contract adds no new
logging framework, aggregation, monitoring, or tracing infrastructure.

Recorded logs and deployment evidence must exclude datasource passwords, JWT signing
material, authentication credentials, access tokens, secret game-key values, and raw
private environment values. The guarantees and limits for application-owned events
are defined in [Security](SECURITY.md#request-correlation-and-application-owned-logging).

## Termination

The image uses an exec-form entry point, so the Java process receives the container
runtime's normal termination signal directly. A deployment must use that normal
container stop path and allow the Java/Spring process time to close its application
context and database connection pool. No custom shutdown endpoint is required or
provided. A process that needs a forced kill after the runtime's stop timeout has not
met this contract.

## Current limitations

This contract does not select or configure a hosting provider. It does not provide
cloud resources, database provisioning, secret-manager integration, registry
publication, TLS or network topology, backup or restore, rollback, scaling, high
availability, monitoring, alerting, tracing, or log aggregation. It also does not
turn the HTTP health endpoint into a PostgreSQL probe. Those decisions require an
actual deployment environment and remain outside the application runtime contract.
