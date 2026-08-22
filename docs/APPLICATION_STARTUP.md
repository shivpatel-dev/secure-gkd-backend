# Application startup

Choose one of the supported local startup paths:

| Path | Use it when |
| --- | --- |
| Docker Compose | You want the most self-contained workflow. Compose builds the application and runs it with PostgreSQL 16, so host Java, Maven, and PostgreSQL installations are not required. Follow the authoritative [containerized local environment](CONTAINERIZED_LOCAL_ENVIRONMENT.md) guide. |
| Direct host execution | You have Java 17 and a reachable PostgreSQL database on the host and want to run Spring Boot with the repository Maven Wrapper. Follow the steps below. |

## Direct host prerequisites

- Java 17 must be available to the Maven Wrapper. A globally installed Maven is not
  required.
- PostgreSQL must be running and reachable. PostgreSQL 16 is the repository's
  currently verified Compose and CI baseline.
- The default local connection expects the `secure_gkd` database at
  `jdbc:postgresql://localhost:5432/secure_gkd` and the `secure_gkd_user` database
  user. Create these through your normal local PostgreSQL administration process if
  they do not exist. The user must be able to create and validate the schema objects
  and Flyway schema-history table required by the migrations.
- TCP port `8080` must be available for the application.

## Supply the host-process environment

Make these values available to the Maven process through your shell or another
untracked environment mechanism:

| Variable | Requirement |
| --- | --- |
| `SPRING_DATASOURCE_PASSWORD` | Required. Password for the selected PostgreSQL user; there is no repository fallback. |
| `JWT_SIGNING_KEY_BASE64` | Required. Valid Base64 representing at least 32 bytes of signing material. |
| `SPRING_DATASOURCE_URL` | Optional. Overrides the local default `jdbc:postgresql://localhost:5432/secure_gkd`. |
| `SPRING_DATASOURCE_USERNAME` | Optional. Overrides the local default `secure_gkd_user`. |
| `JWT_ACCESS_TOKEN_LIFETIME` | Optional. Retains the existing `PT15M` default when omitted. |

Keep secret values outside source control and do not paste them into repository
documentation. The Compose `.env` workflow is specific to Compose; an unrelated
Maven or Java process does not automatically load that file. Direct host execution
must receive its environment from the shell that starts it or another untracked
mechanism. Reuse the safe signing-key generation guidance in the
[container guide](CONTAINERIZED_LOCAL_ENVIRONMENT.md#prerequisites-and-local-secrets),
and see [Security](SECURITY.md#jwt-boundary) for the JWT boundary.

## Build and start

From the repository root, build the application with the Maven Wrapper. This command
packages the application without running the complete test suite:

```powershell
.\mvnw.cmd -DskipTests package
```

```sh
./mvnw -DskipTests package
```

Then start Spring Boot directly from the host with the same externally supplied
environment values:

```powershell
.\mvnw.cmd spring-boot:run
```

```sh
./mvnw spring-boot:run
```

When no other profile is active, Spring Boot selects the default `local` profile.
During startup the application connects to PostgreSQL, Flyway validates the migration
history and applies pending versioned migrations, and Hibernate validates the
resulting schema. Flyway owns the schema: Hibernate does not create or update it, and
automatic Flyway baselining remains disabled. Do not bypass an unknown or incompatible
existing schema to make startup succeed; follow [Database migrations](DATABASE_MIGRATIONS.md)
for migration and existing-schema adoption rules. See [Configuration profiles](CONFIGURATION_PROFILES.md)
for the detailed profile responsibilities.

## Verify health

After startup completes, call the existing public health endpoint:

```sh
curl http://localhost:8080/api/health
```

`GET /api/health` should return HTTP `200` with a JSON response containing
`"status":"UP"`.

## Startup troubleshooting

| Failure | Check |
| --- | --- |
| The Maven Wrapper cannot start Java | Confirm `JAVA_HOME` and `PATH` resolve to a Java 17 runtime. Use the repository wrapper command for your shell, not a global `mvn`. |
| Configuration reports a missing datasource password | Supply `SPRING_DATASOURCE_PASSWORD` to the same host process that runs the Maven Wrapper. |
| JWT configuration is missing or rejected | Supply `JWT_SIGNING_KEY_BASE64` as valid Base64 for at least 32 bytes. Follow the linked signing-key guidance; do not use a committed or reusable example secret. |
| PostgreSQL connection fails | Confirm PostgreSQL is running and reachable, then check the datasource URL, username, and password. Confirm the `secure_gkd` database and `secure_gkd_user` user exist when using the defaults. |
| Flyway validation or schema adoption fails | Do not enable automatic baselining or let Hibernate recreate the schema. Follow [Database migrations](DATABASE_MIGRATIONS.md) to validate or deliberately adopt the existing schema. |
| The web server cannot bind | Free TCP port `8080` before retrying the normal local startup. |
| Values in Compose `.env` appear to be ignored | Compose loads its `.env` workflow; direct Maven execution does not. Supply the required values to the Maven process through the host shell or another untracked mechanism. |

For deeper container, configuration, migration, or security details, use the linked
authoritative documents rather than changing startup behavior.
