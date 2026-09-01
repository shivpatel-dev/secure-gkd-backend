# Configuration profiles

The allocation service separates shared persistence behavior from environment-specific
database connection values with standard Spring Boot profiles.

| Profile | Responsibility | Datasource configuration |
| --- | --- | --- |
| `local` | Local development; this is the default when no profile is active. | `SPRING_DATASOURCE_URL` defaults to `jdbc:postgresql://localhost:5432/secure_gkd`, and `SPRING_DATASOURCE_USERNAME` defaults to `secure_gkd_user`. `SPRING_DATASOURCE_PASSWORD` is required. |
| `test` | Maven tests and CI using PostgreSQL. Its configuration exists only under `src/test/resources`. | `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, and `SPRING_DATASOURCE_PASSWORD` are all required; there are no local fallbacks. |
| `prod` | Production-oriented or deployment execution. | `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, and `SPRING_DATASOURCE_PASSWORD` are all required; there are no local fallbacks. |

Activate a runtime profile with Spring Boot's `SPRING_PROFILES_ACTIVE` environment
variable. For example, set it to `prod` for production-oriented execution. Tests
activate `test` from test-only configuration; CI also sets
`SPRING_PROFILES_ACTIVE=test` explicitly.

Datasource passwords are secrets. Keep them, and any other credentials, in untracked
environment configuration. The repository contains no password fallback. The local
URL and username defaults are non-secret conveniences and apply only to the default
`local` profile. Production startup fails during configuration when any required
datasource environment variable is missing instead of falling back to a local
database identity.

Every runtime profile also requires `JWT_SIGNING_KEY_BASE64`, containing Base64 for
at least 32 bytes of external signing material. There is no production or local
fallback. `JWT_ACCESS_TOKEN_LIFETIME` configures the bounded access-token duration
and defaults to `PT15M`; accepted values are whole seconds from one second through
24 hours. Tests override only the signing key with runtime-generated, test-only
material. See [API notes](API_NOTES.md) for the authentication flow and secret
boundary.

CI supplies the three datasource variables to the test process and uses its
reproducible PostgreSQL service. Local PostgreSQL-backed test runs must supply those
same variables through an untracked environment. Tests do not replace PostgreSQL
with an embedded database.

Common configuration keeps PostgreSQL as the datasource, disables open-in-view,
sets Hibernate schema handling to `validate`, and enables Flyway validation without
automatic baselining. Flyway remains the schema owner in every PostgreSQL-backed
profile; see [Database migrations](DATABASE_MIGRATIONS.md) for migration and schema
adoption rules. The application-level 16 KiB JSON request-body boundary applies
uniformly to every profile and is intentionally a single fixed API boundary rather
than a profile-specific deployment setting; see [API notes](API_NOTES.md) for the
covered operations, `413` response, and deferred abuse-control decisions.

## Optional Kafka outbox publisher

The Kafka publisher is disabled by default in every profile, so standalone and
production-oriented startup do not require a reachable broker. Docker Compose opts in
with `SECURE_GKD_KAFKA_PUBLISHER_ENABLED=true` and supplies
`SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka:9092`. A runtime that deliberately enables the
publisher may also configure:

| Variable | Default and boundary |
| --- | --- |
| `SECURE_GKD_KAFKA_PUBLISHER_TOPIC` | `secure-gkd.allocation-created`; the established transport boundary. |
| `SECURE_GKD_KAFKA_PUBLISHER_POLL_INTERVAL` | `PT1S`; a positive duration between completed polling cycles. |
| `SECURE_GKD_KAFKA_PUBLISHER_BATCH_SIZE` | `100`; accepted values are 1 through 1,000. |
| `SECURE_GKD_KAFKA_PUBLISHER_ACKNOWLEDGEMENT_TIMEOUT` | `PT10S`; must be positive and no greater than one minute. |

The shared producer configuration uses string keys and values, `acks=all`, a five
second request timeout, a five second metadata/send blocking bound, and a ten second
delivery timeout. The publisher waits for producer acknowledgement before recording
`published_at`. Failures remain pending for scheduled retry, and acknowledged events
can still be duplicated if the separate database status update does not commit. No
exactly-once or downstream-processing success guarantee is implied.

## Allocation-audit service configuration

The standalone `audit-service/` application has no HTTP profile or allocation-service
configuration dependency. It always uses its own datasource, Flyway history, and
Hibernate validation. Its external boundary is:

| Variable | Default and boundary |
| --- | --- |
| `AUDIT_DATASOURCE_URL` | Defaults to `jdbc:postgresql://localhost:${AUDIT_DATABASE_HOST_PORT}/secure_gkd_audit`; Compose overrides it with the audit database hostname and container port. |
| `AUDIT_DATASOURCE_USERNAME` | `secure_gkd_audit_user`; non-secret local identity. |
| `AUDIT_DATASOURCE_PASSWORD` | Required with no fallback. Keep it in untracked environment configuration. |
| `AUDIT_DATABASE_HOST_PORT` | Loopback host port used by Compose and the default direct-host JDBC URL; defaults to `55432`. It does not change PostgreSQL's container port `5432`. |
| `AUDIT_DATABASE_SCHEMA` | Optional schema name; defaults to `public`. CI and Compose use a separate audit database, while a non-public value can isolate a local test schema. |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | `localhost:29092`; Compose uses `kafka:9092`. |
| `AUDIT_KAFKA_TOPIC` | `secure-gkd.allocation-created`. |
| `AUDIT_KAFKA_CONSUMER_GROUP` | `secure-gkd-allocation-audit`. |
| `AUDIT_KAFKA_DEAD_LETTER_TOPIC` | `secure-gkd.allocation-created.dlt`. |
| `AUDIT_KAFKA_RETRY_ATTEMPTS` | `2`; retries after the initial retryable attempt. |
| `AUDIT_KAFKA_RETRY_BACKOFF` | `1s`; fixed delay between retryable attempts. |

The consumer uses string keys/values, disabled auto-commit, `earliest` initial offset
behavior, record acknowledgement, and string serialization for original-record
dead-letter publication. Tests disable listener startup. Compose and direct-host
execution default to port `55432`; CI explicitly maps its separate audit PostgreSQL 16
service to port `5433`. Kafka is not a CI service for these focused tests. Compose and
CI give each service only its service-owned datasource values.

For a direct host launch, Spring's normal external-configuration precedence still
applies: generic `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, or
`SPRING_DATASOURCE_PASSWORD` variables in that same process can override the
`spring.datasource` values mapped from the audit variables above. Keep those generic
allocation variables out of a directly launched audit runtime. The audit module's
Maven test/package configuration handles the verification case separately by
excluding those three generic variables from the forked test JVM while retaining the
audit-owned variables. It also pins only the audit test JVM to UTC; it does not change
the packaged application's runtime timezone.
