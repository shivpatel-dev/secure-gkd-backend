# Deployment runtime

This document defines the provider-neutral runtime contracts for deploying the
allocation-service and allocation-audit-service containers. It does not select a
hosting provider or define provider-specific infrastructure.

The repository's single-node Kafka service and topic initialization belong only to
the Docker Compose local-development environment. A deployment of the complete
asynchronous path must provide a reachable Kafka cluster and the source and
dead-letter topics externally; the repository does not provision that external
infrastructure. The allocation image's outbox publisher is disabled by default, so
Kafka is not an allocation-service startup or synchronous-correctness prerequisite.
The audit service, by contrast, is a Kafka consumer and needs its own database and
broker connectivity to perform audit processing.

## Deployment artifact and Java runtime

The root `Dockerfile` builds the allocation service, and
`audit-service/Dockerfile` builds the audit service. Each Java 17 build stage uses the
repository Maven Wrapper to package only its Spring Boot application. Each final Java
17 JRE image receives only its packaged application, so Maven, the source tree, and
other build tooling remain outside the runtime images.

The allocation image runs as the dedicated numeric identity `10001:10001`; the audit
image runs as `10002:10002`. Each packaged JAR is owned by its service identity. The
processes do not require root, writable application directories, additional Linux
capabilities, or another operating-system privilege. Environment-specific
configuration and secrets must remain outside the images.

## Required runtime configuration

### Allocation service

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

`SECURE_GKD_KAFKA_PUBLISHER_ENABLED` is optional and defaults to `false`. A deployment
that deliberately enables it must separately provide a reachable
`SPRING_KAFKA_BOOTSTRAP_SERVERS` value and the publisher settings described in
[Configuration profiles](CONFIGURATION_PROFILES.md#optional-kafka-outbox-publisher).
Kafka readiness is not an application-startup or synchronous-allocation prerequisite.

### Allocation-audit service

The audit service has no Spring profile or JWT configuration dependency. Its runtime
must supply or deliberately accept these service-owned settings:

| Variable | Runtime responsibility |
| --- | --- |
| `AUDIT_DATASOURCE_URL` | JDBC URL for the audit-owned PostgreSQL database; the direct-host default uses `localhost` and `AUDIT_DATABASE_HOST_PORT`. |
| `AUDIT_DATASOURCE_USERNAME` | Audit-owned PostgreSQL identity; defaults to the non-secret local name `secure_gkd_audit_user`. |
| `AUDIT_DATASOURCE_PASSWORD` | Required password for the audit-owned PostgreSQL identity; there is no fallback. |
| `AUDIT_DATABASE_SCHEMA` | Optional audit-owned schema name; defaults to `public`. |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | Reachable broker address; direct-host execution defaults to `localhost:29092`. |
| `AUDIT_KAFKA_TOPIC` | Source topic; defaults to `secure-gkd.allocation-created`. |
| `AUDIT_KAFKA_CONSUMER_GROUP` | Stable consumer group; defaults to `secure-gkd-allocation-audit`. |
| `AUDIT_KAFKA_DEAD_LETTER_TOPIC` | Recovery topic; defaults to `secure-gkd.allocation-created.dlt`. |
| `AUDIT_KAFKA_RETRY_ATTEMPTS` | Retry attempts after the initial retryable failure; defaults to `2`. |
| `AUDIT_KAFKA_RETRY_BACKOFF` | Fixed retry delay; defaults to `1s`. |

The deployment environment must provision both Kafka topics deliberately. Automatic
topic creation is not part of either service's runtime contract. Full defaults and
validation boundaries are in
[Configuration profiles](CONFIGURATION_PROFILES.md#allocation-audit-service-configuration).

Runtime values must be supplied through the selected environment's external
configuration mechanism. Do not place credentials, signing material, access tokens,
secret game-key values, or reusable example secrets in either image, the repository,
deployment evidence, or command output.

## Application port and traffic

The allocation application and container use port `8080` by default. The deployment
environment must route traffic to the effective application port. Standard Spring
Boot external configuration can set a different application port with `SERVER_PORT`;
the runtime must then route traffic to that value instead. This contract does not
define a provider-specific `PORT` variable or mapping. The audit service sets
`web-application-type: none` and exposes no HTTP port.

## PostgreSQL startup and schema ownership

Each service has a separate PostgreSQL ownership boundary. Connectivity,
provisioning, network reachability, and credentials for both databases belong to the
deployment environment. A controlled deployment-like verification of these
contracts must use PostgreSQL 16, matching the repository's Compose and CI baseline.

At startup, each service connects only to its owned datasource. Its Flyway instance
validates its own schema-history table and applied migration checksums, then applies
pending versioned migrations from its packaged `classpath:db/migration`. Automatic
baselining remains disabled. After Flyway completes, Hibernate validates the
resulting owned schema with `ddl-auto: validate`; Hibernate does not create or update
either normal application schema.

Each configured database identity must have the permissions needed to create and
update its Flyway schema-history table, apply every pending service-owned migration,
and perform that service's normal persistence operations. The required privileges
depend on the tracked migrations and database environment; this contract does not
invent provider-specific privilege, TLS, backup, or network requirements. See
[Database migrations](DATABASE_MIGRATIONS.md) for the separate ownership boundaries
and explicit existing-schema adoption rules.

Allocation-service startup is successful only after required configuration is
accepted, its PostgreSQL database is reachable, Flyway has reached the expected
migrated state, Hibernate validation has succeeded, and the HTTP server is listening
on the effective port. Audit-service startup similarly requires its owned database
and migrations; useful audit processing additionally requires broker connectivity.
Detailed settings remain in [Configuration profiles](CONFIGURATION_PROFILES.md), and
the direct-host allocation path remains in [Application startup](APPLICATION_STARTUP.md).

## Health check

`GET /api/health` is public and is the current HTTP/application health-check
candidate. An HTTP `200` response containing `"status":"UP"` demonstrates that the
allocation application is running and serving HTTP. The controller does not
independently query PostgreSQL, Kafka, the audit service, or audit state, so this
response is not proof of their current availability. The audit service has no HTTP
health endpoint; its process, listener, broker connectivity, lag, failures, and
database state require deployment-owned operational checks.

## Logs and privacy

Spring Boot writes both services' normal application and startup logs to standard
console output; the container runtime exposes that output as the deployment log
stream. Neither application writes container-local log files, and this contract adds
no new logging framework, aggregation, monitoring, or tracing infrastructure.

Recorded logs and deployment evidence must exclude datasource passwords, JWT signing
material, authentication credentials, access tokens, secret game-key values, and raw
private environment values. The guarantees and limits for application-owned events
are defined in [Security](SECURITY.md#request-correlation-and-application-owned-logging).

## Termination

Both images use exec-form entry points, so each Java process receives the container
runtime's normal termination signal directly. A deployment must use that normal
container stop path and allow the Java/Spring process time to close its application
context, database connection pool, and, for the asynchronous components, Kafka
resources. No custom shutdown endpoint is required or provided. A process that needs
a forced kill after the runtime's stop timeout has not met this contract.

## Current limitations

These contracts do not select or configure a hosting provider. They do not provide
cloud resources, database or Kafka provisioning, topic administration,
secret-manager integration, registry publication, TLS or network topology, backup or
restore, rollback, scaling, high availability, monitoring, alerting, tracing, or log
aggregation. They also do not turn the HTTP health endpoint into a PostgreSQL, Kafka,
or audit probe. Those decisions require an actual deployment environment.

The recorded Render exercise covered only one temporary allocation Web Service and
one PostgreSQL database; both were removed afterward. It did not deploy or verify the
audit service, Kafka transport, or complete two-service topology, and it does not
establish a continuously running production deployment. See
[Deployment verification](DEPLOYMENT_VERIFICATION.md) for that bounded evidence.
