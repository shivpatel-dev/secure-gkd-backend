# Database migrations

Each service owns its PostgreSQL schema through its own Flyway history. Allocation
service migrations live in `src/main/resources/db/migration`; audit-service migrations
live in `audit-service/src/main/resources/db/migration`. Both use Flyway's
`V<version>__<description>.sql` naming convention. Each Spring Boot application runs
only its own pending migrations before Hibernate validates its own entity mappings.
Hibernate does not create, update, or drop either normal schema.

## Allocation-service migration history

`V1__create_initial_schema.sql` is the baseline for the `games`, `game_keys`,
`allocations`, and `idempotency_records` model.
`V2__create_authentication_identities.sql` adds the separate persisted identity used
for credential authentication. `V3__add_authentication_identity_roles.sql` adds the
required, constrained `USER` or `ADMIN` role and assigns the conservative `USER`
baseline to identities that already exist when the migration runs.
`V4__create_allocation_outbox.sql` adds the allocation service's transactional outbox
with a UUID event primary key, one unique foreign-key reference per Allocation,
versioned JSON payload metadata, and nullable publication timestamp.
`V5__index_pending_allocation_outbox.sql` adds a focused partial index on
`occurred_at, event_id` for rows whose `published_at` is null. It supports bounded,
deterministic oldest-first publisher polling without indexing published history. A
clean database is initialized by starting the application with a database user that can create the
required objects; Flyway creates its schema-history table, applies pending versions
in order, and records each successful migration. Starting the application again
validates the recorded migrations and does not reapply them.

## Audit-service migration history

The audit service has an independent version sequence and an independent
`flyway_schema_history` table in the `secure_gkd_audit` database.
`V1__create_allocation_audit_schema.sql` creates `allocation_audit_record` with a
service-owned UUID primary key and the source event identity, version, timestamps,
Allocation and Game identifiers, non-secret Game code, request correlation, and audit
persistence timestamp. Source Allocation and Game IDs are plain values: the migration
creates no foreign keys to the allocation database. `source_event_id` is deliberately
not unique because consumer-side duplicate suppression is later work.

Do not edit a migration after it has been applied. Every future schema change must be
represented by a new migration with the next version. Review migrations together
with the corresponding JPA mapping change, and keep manual table-creation SQL out of
application startup and CI workflows.

## Existing development schemas

Automatic baselining is deliberately disabled. An existing non-empty schema without
Flyway history must not be accepted merely to make startup succeed.

Choose one of these explicit adoption paths:

1. For a schema whose data must be retained, back it up and compare its tables,
   columns, identity definitions, nullability, lengths, primary keys, unique
   constraints, and foreign keys with `V1__create_initial_schema.sql`. Only after the
   schema is verified as equivalent should an operator deliberately run Flyway's
   `baseline` command at version `1`, using credentials supplied through an untracked
   local configuration or environment. Then run Flyway validation or start the
   application and confirm that validation succeeds without applying `V1`.
2. If the schema contains only disposable local development data, recreate that
   database through the normal local administration process and let Flyway initialize
   the resulting clean schema. Treat recreation as an explicit destructive choice;
   the application does not perform it automatically.

Never enable `baseline-on-migrate` globally or baseline a schema that has not first
been verified. A schema that differs from `V1` requires a separately reviewed data or
schema adoption plan rather than an automatic baseline.

PostgreSQL-backed tests retain each service's configured datasource and use that
service's versioned migrations. CI runs the allocation tests against
`secure_gkd` on its PostgreSQL 16 service and the audit tests against the separate
`secure_gkd_audit` PostgreSQL 16 service. Neither test path maintains a competing
Hibernate-generated schema or reuses the other application's tables.
