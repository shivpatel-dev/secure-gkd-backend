# Render deployment and rollback runbook

This runbook describes how an operator can recreate the Render deployment shape
previously verified for Secure GKD and how to decide whether an application rollback
is safe. It does not create infrastructure, automate deployment, or define a database
rollback.

## Evidence and authority

Keep these evidence categories separate when following or updating this runbook:

- **Repository-defined application behavior:** the existing `Dockerfile`, production
  Spring configuration, and [deployment runtime](DEPLOYMENT_RUNTIME.md) define the
  image, required configuration, startup order, and health-endpoint boundary.
  [Configuration profiles](CONFIGURATION_PROFILES.md),
  [database migrations](DATABASE_MIGRATIONS.md),
  [application startup](APPLICATION_STARTUP.md), [security](SECURITY.md), and
  [API notes](API_NOTES.md) remain authoritative for their detailed subjects.
- **Directly exercised on Render:** the bounded
  [Render deployment verification](DEPLOYMENT_VERIFICATION.md) records one Web
  Service, one same-region PostgreSQL 16 database, one initial deploy, API checks,
  and one controlled restart.
- **Render-documented platform behavior:** the provider procedures below were
  checked against Render's current official documentation for
  [Docker services](https://render.com/docs/docker),
  [Web Services](https://render.com/docs/web-services),
  [Render Postgres connections](https://render.com/docs/postgresql-creating-connecting),
  [deploys](https://render.com/docs/deploys),
  [health checks](https://render.com/docs/health-checks), and
  [rollbacks](https://render.com/docs/rollbacks). These procedures are not project
  runtime evidence.
- **Not exercised by this project:** deployment rollback, database recovery, and the
  broader reliability capabilities listed under
  [Operational risks and evidence limits](#operational-risks-and-evidence-limits).

Render screens and capabilities can change. Recheck the linked official documentation
before a real deployment or rollback.

## Prerequisites and deployment record

Before creating or changing resources:

1. Obtain authorized Render workspace access and repository access sufficient for
   Render to build the selected revision.
2. Select and record the full commit SHA intended for deployment. Confirm that the
   selected branch currently resolves to that reviewed commit; do not deploy an
   unreviewed branch head by accident.
3. Record the full SHA and successful Render deploy for the last-known-good
   application revision, if one exists. Keep operational Render identifiers in the
   private incident or change record, not in this repository.
4. Prepare externally managed values for every required environment variable. Store
   secrets only in Render's environment configuration or another approved secret
   system; never put them in Git, the image, screenshots, tickets, chat, or shell and
   deployment logs.
5. Plan for exactly one Render Web Service built from the repository `Dockerfile`
   and one Render Postgres resource on PostgreSQL major version 16. Place both in the
   same Render region. Render documents that same-region resources can use the
   private network and internal database connection details.
6. Decide whether auto-deploy should remain off while the selected revision is
   verified. The previous bounded verification used auto-deploy off; that observation
   is not a requirement imposed by the application.

Do not reuse the revision recorded in the historical verification merely because it
was once exercised. Select the revision intentionally for the current change.

## Production configuration

Configure these names on the Web Service without copying their values into an
operator transcript:

| Environment variable | Required value or responsibility |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | Required value `prod`. |
| `SPRING_DATASOURCE_URL` | Required JDBC URL assembled from the Render Postgres internal host, port, and database name. |
| `SPRING_DATASOURCE_USERNAME` | Required Render Postgres user. |
| `SPRING_DATASOURCE_PASSWORD` | Required Render Postgres password; secret. |
| `JWT_SIGNING_KEY_BASE64` | Required externally generated Base64 for at least 32 bytes of signing material; secret. |
| `JWT_ACCESS_TOKEN_LIFETIME` | Optional whole-second duration from one second through 24 hours; omit to retain `PT15M`. |
| `SERVER_PORT` | Set to `10000` to recreate the verified Render shape. |

Render exposes internal PostgreSQL connection components and an assembled
PostgreSQL connection URL. This application does not consume Render's URL format
directly. Translate the internal components without publishing them:

```text
SPRING_DATASOURCE_URL=jdbc:postgresql://<internal-host>:5432/<database>
SPRING_DATASOURCE_USERNAME=<database-user>
SPRING_DATASOURCE_PASSWORD=<database-password>
```

Use the internal host only from a Web Service in the same Render account and region
as the database. Do not place the username or password in the JDBC URL. Confirm all
three values against the database's current private connection details after a
credential change.

Set the Render HTTP health-check path to `/api/health`. The selected port matches the
previously verified shape and Render's default Web Service port, while `SERVER_PORT`
is the Spring setting that makes this application listen on that port.

`GET /api/health` returning a successful response demonstrates that the Spring
application is serving HTTP. The controller does not query PostgreSQL, so it is not
an independent database health or readiness probe. Render recommends including
operation-critical dependencies in a health check, but this project's current
endpoint intentionally does not establish that stronger signal.

## Normal deployment procedure

1. **Create or select Render Postgres.** Select PostgreSQL major version 16 and the
   intended region. If an existing database is selected, identify its current Flyway
   history and schema state before deploying; do not assume it is empty or compatible.
2. **Configure the Web Service.** Start the Web Service creation form, connect the
   authorized repository, select the Docker runtime so Render builds the existing
   root `Dockerfile`, select the same region as the database, and select the reviewed
   branch. Do not click **Create Web Service** until steps 3 through 5 are complete.
   Do not add a custom build command, start command, Blueprint, or alternate image
   path.
3. **Pin the deployment decision.** Confirm the branch head is the recorded intended
   commit before allowing the initial deploy. For a later manual deployment, use
   **Manual Deploy > Deploy a specific commit** and enter the reviewed SHA. Render
   documents that the Dashboard specific-commit path disables auto-deploys.
4. **Supply external configuration.** In the form's advanced settings, add every
   required variable from
   [Production configuration](#production-configuration), using current private
   Render Postgres values and independently managed JWT signing material. Saving
   environment changes on an existing service can trigger a deployment, so verify
   the revision and all variable names before saving.
5. **Configure routing and health.** Set `SERVER_PORT=10000` and the Web Service
   health-check path to `/api/health`.
6. **Start the selected deployment.** Choose **Create Web Service** only after the
   initial settings are complete, or start the reviewed manual deployment on an
   existing service. Follow the deploy on the service's Events page. Confirm the
   event identifies the intended commit rather than merely assuming the linked
   branch was unchanged.
7. **Inspect build and startup logs.** Expect the Docker build to package the
   application with Java 17. At runtime, confirm the `prod` profile, port `10000`,
   datasource initialization, Flyway activity, Hibernate initialization, and the
   HTTP server reaching its started state. Do not copy private endpoints, credentials,
   tokens, signing material, or secret game-key values from logs.
8. **Confirm schema startup.** Flyway must validate applied migration checksums and
   apply any pending tracked versions before Hibernate successfully validates the
   resulting schema. An up-to-date database can legitimately report that no migration
   was necessary.
9. **Verify HTTP reachability.** Wait for Render to mark the service live, then call
   `/api/health` over the assigned HTTPS URL and require a successful response with
   `status` equal to `UP`. Treat this only as application HTTP evidence.
10. **Verify database-backed behavior when appropriate.** If approved non-secret
    verification data and identities already exist, exercise the smallest relevant
    authenticated read or other application operation. Keep credentials, Bearer
    tokens, password hashes, and returned game-key values out of the record. Do not
    manufacture permanent fixtures merely for this runbook.

## Migration handling

These repository rules apply to every deployment:

- Flyway owns the normal application schema. Pending versioned migrations run during
  application startup before Hibernate schema validation.
- Automatic Flyway baselining remains disabled. Do not enable it to bypass a
  non-empty schema with no valid Flyway history.
- Hibernate uses `ddl-auto: validate`; it does not create, update, or repair the
  normal schema.
- Never edit an already-applied migration. Add a separately reviewed next version for
  a future schema change.
- Do not introduce manual competing schema initialization to make a deployment pass.
  Follow the explicit adoption boundary in
  [Database migrations](DATABASE_MIGRATIONS.md#existing-development-schemas) or stop
  for a separately reviewed production data/schema plan.

A migration failure is a deployment failure. Do not repeatedly restart the service
or alter Flyway history until the failed state and PostgreSQL transaction outcome are
understood.

## Startup and deployment failure investigation

Use the Render event and bounded application logs while preserving the
[security logging boundary](SECURITY.md#request-correlation-and-application-owned-logging).

| Failure area | Inspect and respond |
| --- | --- |
| Wrong revision | Compare the Render deploy commit with the recorded intended full SHA. Check whether auto-deploy selected a newer branch head. Stop the deploy decision until the intended revision is clear. |
| Docker build | Inspect the first failing Docker build step and Maven Wrapper output. Confirm Render is using the root `Dockerfile` and repository contents for the intended commit. Do not add a second build path as a workaround. |
| Required configuration | Confirm every required variable name exists, `SPRING_PROFILES_ACTIVE` is `prod`, `SERVER_PORT` is `10000`, the JWT key is valid Base64 for at least 32 decoded bytes, and any token-lifetime override is within the documented range. Never print the values. |
| PostgreSQL connection | Confirm both resources are in the same region, the JDBC URL uses the current internal host and database name, the username and password match current credentials, PostgreSQL is available, and the database identity has the required migration and application privileges. |
| Flyway validation or migration | Identify the failing version and whether the database is empty, current, partially migrated, or has incompatible history. Check for checksum mismatch or a non-empty schema without history. Do not edit an applied migration, enable automatic baselining, delete history, or improvise schema changes. |
| Hibernate validation | Treat a missing or mismatched table, column, constraint, or type as a schema/application compatibility failure. Compare the selected revision's migrations and mappings with the current schema; do not switch Hibernate to schema creation or update. |
| Port or health check | Confirm the application actually reached its started state on port `10000`, `SERVER_PORT` is spelled correctly, and Render's path is exactly `/api/health`. Distinguish a process that never bound a port from a started process returning an unhealthy HTTP status. |
| Cold start | The prior free-tier exercise observed starts around two minutes and a temporary no-open-port report. Use logs and the final service state instead of treating that duration as a guarantee. Continued startup failure still requires diagnosis. |
| Health is `UP` but database work fails | Investigate PostgreSQL connectivity and the failing application operation. `/api/health` does not independently query the database. |

## Controlled application rollback

Deployment rollback was not exercised by this project. The following is a
Render-documented operator procedure plus repository-defined schema-safety checks,
not observed rollback evidence.

### Decide whether application rollback is safe

Before pressing a rollback or deploy button, record privately:

1. the full commit SHA and Render event for the currently deployed or failing
   revision;
2. the full commit SHA and successful deploy selected as last known good;
3. whether the current revision started far enough for Flyway to apply migrations,
   and the resulting Flyway/schema version;
4. the migration set and schema expectations of the last-known-good revision;
5. whether that older application remains compatible with the database as it exists
   now; and
6. any environment, health-check, instance, database credential, or other platform
   configuration changed independently of the application commit.

Review the migrations and JPA mappings in both revisions. Do not infer compatibility
merely because the older deploy once succeeded. An additive migration can still make
old code unsafe, and a changed constraint or data representation can be incompatible.

**Safe stopping point:** if the older application is incompatible with the already
migrated database, or compatibility cannot be established, do not treat application
rollback as safe. Leave database state intact and require a separately reviewed
schema/data remediation or forward-fix plan. This runbook does not authorize a
destructive database change.

### Roll back to a retained deploy artifact

When the selected last-known-good deploy is schema-compatible and Render still
retains its build artifact:

1. Open the Web Service's **Events** page.
2. Find the selected previous successful deploy and choose **Rollback**.
3. On the confirmation page, verify the target deploy and choose
   **Rollback to this deploy**.
4. Follow the new rollback deploy through startup and complete
   [Post-rollback verification](#post-rollback-verification).
5. Confirm auto-deploy remains disabled during incident handling. Render documents
   that a rollback triggered in the Dashboard disables auto-deploys.

Render documents that this path creates a new deploy using the target deploy's
retained build artifact. Artifact retention depends on the workspace plan, so an old
deploy might no longer be selectable.

Render also documents that rollback reuses the target deploy's build artifact,
Docker command, health-check path, and service environment variables for that
rollback, but it does not overwrite the service's current configuration settings;
the next standard deploy uses current settings. Not every platform setting rolls
back, and environment-group values themselves are not rewound. Compare the official
[rollback configuration table](https://render.com/docs/rollbacks#whats-rolled-back)
with the private change record instead of assuming the entire service was restored
to an earlier state.

### Deploy a known-good commit when needed

If a retained artifact is unavailable, or a reviewed rebuild is the appropriate
recovery path:

1. Confirm the known-good commit still exists in the linked repository and passes the
   schema-compatibility decision above.
2. From **Events**, choose **Manual Deploy > Deploy a specific commit**.
3. Paste or select the recorded full commit SHA and choose **Deploy Commit**.
4. Verify the resulting event identifies that exact commit, follow startup, and
   complete post-rollback verification.
5. Keep auto-deploy disabled. Render documents that the Dashboard specific-commit
   path disables it automatically; CLI and API paths do not, so an operator using
   those interfaces must disable it separately.

Do not use **Restart service** as a rollback: Render documents that restart deploys
the same currently deployed commit rather than selecting an older application
revision.

After the incident is resolved, re-enable auto-deploy only when the linked branch's
latest commit is approved for deployment. Otherwise the next automatic deployment
can reintroduce the revision that was intentionally removed.

### Application rollback is not database rollback

Rolling back the Render Web Service does not rewind PostgreSQL rows, Flyway schema
history, or applied migrations. It does not restore a database backup and does not
edit migration checksums. No destructive or automatic database rollback is defined
here. Database backup, restore, schema remediation, and data remediation require
their own reviewed procedure and evidence.

## Post-rollback verification

For either application-revision recovery path:

1. Confirm the Render event and runtime revision match the selected known-good full
   SHA.
2. Inspect startup logs for successful datasource initialization, the expected
   Flyway validation/migration state, successful Hibernate schema validation, and the
   HTTP server starting on port `10000`.
3. Confirm Render marks the service live and `/api/health` returns a successful
   response with `status` equal to `UP`.
4. Remember that the health response is not independent PostgreSQL evidence.
5. When suitable approved identities and data are available, verify the smallest
   relevant PostgreSQL-backed application behavior, such as an authenticated read or
   idempotent replay whose expected result is already known. Do not expose secrets or
   introduce permanent verification data.
6. Recheck current service configuration because a later standard deploy will use
   current settings, not necessarily every setting reused for the rollback deploy.
7. Record the result, remaining limitations, and the intentional auto-deploy state in
   the private incident or change record.

## Operational risks and evidence limits

The [deployment verification](DEPLOYMENT_VERIFICATION.md) establishes only one
bounded Render deployment and one controlled restart. Deployment rollback was not
exercised. The temporary Web Service and Render Postgres resources were removed after
verification, so there is no retained project environment to inspect or recover.

Free-tier cold-start behavior was observed. The project has not established high
availability, autoscaling, production traffic behavior, backup recovery, disaster
recovery, monitoring, alerting, or general production reliability. It also has not
established that the current health endpoint detects a later database outage. Treat
Render's documented platform capabilities as provider documentation, not as evidence
that this application has exercised or satisfied them.
