# Secure GKD Backend

The current single-service design and runtime flows are summarized in
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

Local containerized and direct host startup choices are documented in
[`docs/APPLICATION_STARTUP.md`](docs/APPLICATION_STARTUP.md).

The implemented security model, hardening boundaries, and current limitations are
consolidated in [`docs/SECURITY.md`](docs/SECURITY.md).

Environment responsibilities, profile activation, and required datasource variables
are documented in
[`docs/CONFIGURATION_PROFILES.md`](docs/CONFIGURATION_PROFILES.md).

Database schema ownership and local adoption rules are documented in
[`docs/DATABASE_MIGRATIONS.md`](docs/DATABASE_MIGRATIONS.md).

Docker Compose setup for the application and PostgreSQL is documented in
[`docs/CONTAINERIZED_LOCAL_ENVIRONMENT.md`](docs/CONTAINERIZED_LOCAL_ENVIRONMENT.md).

The provider-neutral deployment contract is documented in
[`docs/DEPLOYMENT_RUNTIME.md`](docs/DEPLOYMENT_RUNTIME.md).

Observed Render deployment evidence is recorded in
[`docs/DEPLOYMENT_VERIFICATION.md`](docs/DEPLOYMENT_VERIFICATION.md).

The operator procedure for Render deployment and application rollback is in
[`docs/DEPLOYMENT_RUNBOOK.md`](docs/DEPLOYMENT_RUNBOOK.md).

Credential authentication and Bearer-token usage are documented in
[`docs/API_NOTES.md`](docs/API_NOTES.md).

With the application running, generated OpenAPI JSON is available at
`/v3/api-docs` and Swagger UI at `/swagger-ui.html`. Copyable requests and exercise
prerequisites are in [`docs/API_EXAMPLES.md`](docs/API_EXAMPLES.md).
