# Database migrations

Flyway owns the PostgreSQL schema used by the application. Versioned SQL migrations
live in `src/main/resources/db/migration` and use Flyway's `V<version>__<description>.sql`
naming convention. Spring Boot runs pending migrations during startup before
Hibernate validates the entity mappings. Hibernate does not create, update, or drop
the normal application schema.

`V1__create_initial_schema.sql` is the baseline for the `games`, `game_keys`,
`allocations`, and `idempotency_records` model.
`V2__create_authentication_identities.sql` adds the separate persisted identity used
for credential authentication. `V3__add_authentication_identity_roles.sql` adds the
required, constrained `USER` or `ADMIN` role and assigns the conservative `USER`
baseline to identities that already exist when the migration runs.
`V4__create_allocation_outbox.sql` adds the allocation service's transactional outbox
with a UUID event primary key, one unique foreign-key reference per Allocation,
versioned JSON payload metadata, and nullable publication timestamp. A clean database
is initialized by starting the application with a database user that can create the
required objects; Flyway creates its schema-history table, applies pending versions
in order, and records each successful migration. Starting the application again
validates the recorded migrations and does not reapply them.

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

PostgreSQL-backed tests retain the configured datasource and use these same versioned
migrations. They do not maintain a competing Hibernate-generated test schema.
