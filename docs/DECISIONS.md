# Architecture decisions

This document records the major engineering decisions behind the current
single-service backend. It explains why the implemented boundaries matter and what
they do not provide. For the system structure and request flows, start with
[Architecture](ARCHITECTURE.md).

## 1. Keep allocation synchronous and transactional

### Context and decision

Allocating a GameKey changes related PostgreSQL state and must return the allocated
key in the HTTP response. `AllocationService.allocate` therefore owns one synchronous
Spring-managed `@Transactional` boundary. For a new allocation, the Game lookup,
available-GameKey lookup, Allocation persistence, IdempotencyRecord persistence, and
response construction all participate in that transaction.

`AllocationRepository.saveAndFlush` sends the Allocation insert to PostgreSQL while
the transaction remains active. The flush makes an immediate constraint failure
available to the service's exception translation; it is not an independent commit.
Spring commits after the transactional method completes successfully and rolls back
when an unchecked failure leaves it.

### Consequences and limits

The Allocation and IdempotencyRecord succeed or fail as one service-owned unit. In a
PostgreSQL-backed transaction test, a forced IdempotencyRecord persistence failure
after the Allocation flush left neither row stored and made the GameKey available
again. This is evidence for that controlled rollback path, not a claim that flushing
commits separately.

Allocation is not split across services, asynchronous, or distributed. The caller
waits for PostgreSQL work and receives the result from the same request path.

See [Allocation runtime and debugging](ALLOCATION_RUNTIME_DEBUGGING.md) for the full
flow and evidence categories.

## 2. Persist exact-key idempotency with the allocation

### Context and decision

Clients can repeat an allocation request after an uncertain response. The service
checks the exact submitted idempotency key before starting new allocation work. An
existing IdempotencyRecord returns its linked original Allocation result without
looking up the requested Game, selecting another GameKey, or persisting another
Allocation or IdempotencyRecord.

For a new allocation, the service persists an IdempotencyRecord linking the exact key
to the new Allocation inside the same allocation transaction. PostgreSQL requires
the idempotency key and allocation reference in that table to be unique.

### Consequences and limits

A replay returns the original result and does not consume another GameKey. Lookup is
by idempotency key alone, so the replay response comes from the stored Allocation and
does not cross-check the request path's `gameCode`.

This mechanism is request-replay protection. It is not authentication,
authorization, rate limiting, entitlement or quota enforcement, or distributed
message deduplication.

See [Allocation runtime and debugging](ALLOCATION_RUNTIME_DEBUGGING.md) and
[Security](SECURITY.md#input-and-abuse-boundaries).

## 3. Let PostgreSQL constraints provide final duplicate-allocation protection

### Context and decision

The application queries for the first GameKey that has no Allocation, but that query
does not lock or reserve the candidate. Concurrent transactions can therefore select
the same key. PostgreSQL's unique constraint on `allocations.game_key_id` is the final
protection that permits only one Allocation for a GameKey.

`saveAndFlush` exposes a losing insert's `DataIntegrityViolationException` inside the
service, which translates it to the established unavailable-key
`IllegalStateException`. The unchecked exception leaves the transactional boundary,
and the losing transaction rolls back. A controlled PostgreSQL-backed test observed
two transactions select the same key, one complete, and the other follow this
constraint-failure and rollback path without retaining an IdempotencyRecord.

### Consequences and limits

Correctness depends on database constraints as well as application selection. The
migrated schema also requires unique Game codes, unique GameKey codes, unique
idempotency keys, and unique Allocation references across IdempotencyRecord rows.
These constraints protect invariants even when competing work reaches PostgreSQL.

The current design provides no selection locking, reservation, retry, cache,
application-level serialization, or custom transaction-isolation setting. The
controlled two-transaction test establishes the observed collision outcome, not
throughput, fairness, collision frequency, or a need for one of those alternatives.

See [Allocation concurrency evidence](ALLOCATION_CONCURRENCY.md) and
[Database migrations](DATABASE_MIGRATIONS.md).

## 4. Make Flyway the PostgreSQL schema owner

### Context and decision

Normal PostgreSQL schema creation and evolution belongs to versioned Flyway SQL
migrations. Spring Boot runs pending migrations at startup, after which Hibernate
uses `ddl-auto: validate` to check that the migrated schema matches the entity
mappings. Hibernate does not create or update the normal schema.

Applied migrations are not rewritten as the normal evolution mechanism; a schema
change is added as a new versioned migration. Flyway validation remains enabled and
automatic baselining remains disabled.

### Consequences and limits

Schema history is explicit and repeatable, and a mismatch fails rather than being
silently repaired by Hibernate. The database identity used at startup must be able to
manage Flyway history, apply pending migrations, and perform normal application work.

An existing non-empty schema without Flyway history cannot be adopted automatically.
It requires either the documented equivalence check and deliberate baseline process,
or an explicitly approved recreation when its data is disposable. A non-equivalent
schema requires a separately reviewed adoption plan.

See [Database migrations](DATABASE_MIGRATIONS.md) and
[Configuration profiles](CONFIGURATION_PROFILES.md).

## 5. Use database-backed credentials and stateless JWT authorization

### Context and decision

Credential authentication loads a persisted identity by unique username and compares
the submitted password with its one-way encoded password value through Spring
Security's `PasswordEncoder`. Successful authentication issues a short-lived,
HS256-signed JWT Bearer access token containing the username and the identity's one
established role. Protected requests are stateless: Spring Security validates the
signed token on every request instead of creating an HTTP authentication session.

The only application roles are `USER` and `ADMIN`. Endpoint authorization is
explicit, and all unclassified routes are denied by default. Datasource credentials
and JWT signing material are supplied externally rather than committed or built into
the application.

### Consequences and limits

The service can authenticate pre-provisioned identities and authorize its current
routes without keeping server-side login sessions. JWT signing provides integrity
and authenticity, not confidentiality, and a persisted role change does not alter an
already-issued token before it expires.

The application does not provide registration, account management, refresh tokens,
token revocation, signing-key rotation, MFA, or an external identity provider.
Identity and credential provisioning remain an external operational responsibility.

See [Security](SECURITY.md) and
[Configuration profiles](CONFIGURATION_PROFILES.md).

## 6. Keep the application container provider-neutral

### Context and decision

The repository `Dockerfile` defines one deployable Java 17 application-container
artifact. Its runtime image contains the packaged Spring Boot JAR and runs it as a
non-root user. The runtime contract stays provider-neutral: the deployment
environment supplies the production profile, datasource configuration, JWT signing
material, effective port routing, and other environment-specific settings.

PostgreSQL provisioning, connectivity, credentials, and operations remain deployment
responsibilities outside the application image. Application startup runs Flyway
migration and validation, followed by Hibernate schema validation, before the HTTP
service is ready.

### Consequences and limits

The same container contract can be used without embedding one provider's build or
startup model in the repository. It does not itself provide a database, TLS, network
topology, backups, scaling, high availability, monitoring, or secret-manager
integration.

Render is bounded historical verification of this contract: one temporary Web
Service and managed PostgreSQL instance were exercised and then removed. Render is
not an architectural dependency, and the repository does not claim that a
continuously running Render deployment exists.

See [Deployment runtime](DEPLOYMENT_RUNTIME.md) and
[Render deployment verification](DEPLOYMENT_VERIFICATION.md).
