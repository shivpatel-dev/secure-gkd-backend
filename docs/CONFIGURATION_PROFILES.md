# Configuration profiles

The backend separates shared persistence behavior from environment-specific database
connection values with standard Spring Boot profiles.

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
