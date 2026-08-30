# Architecture decisions

This document records the major engineering decisions behind the current
single-service backend and clearly identified target architecture. It explains why
the boundaries matter, what they do not provide, and whether a decision is already
implemented. For the current system structure and request flows, start with
[Architecture](ARCHITECTURE.md).

## 1. Keep allocation synchronous and transactional

### Context and decision

Allocating a GameKey changes related PostgreSQL state and must return the allocated
key in the HTTP response. `AllocationService.allocate` therefore owns one synchronous
Spring-managed `@Transactional` boundary. For a new allocation, the Game lookup,
available-GameKey lookup, Allocation persistence, IdempotencyRecord persistence, and
`AllocationCreated` outbox-intent persistence all participate in that transaction,
along with response construction.

`AllocationRepository.saveAndFlush` sends the Allocation insert to PostgreSQL while
the transaction remains active. The flush makes an immediate constraint failure
available to the service's exception translation; it is not an independent commit.
Spring commits after the transactional method completes successfully and rolls back
when an unchecked failure leaves it.

### Consequences and limits

The Allocation, IdempotencyRecord, and outbox intent succeed or fail as one
service-owned unit. In a
PostgreSQL-backed transaction test, a forced IdempotencyRecord persistence failure
after the Allocation flush left neither row stored and made the GameKey available
again. A separate forced outbox-persistence failure left none of the three new rows
stored. This is evidence for those controlled rollback paths, not a claim that
flushing commits separately.

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

## 7. Add one asynchronous boundary for allocation audit processing

### Status and current-state evidence

**Transactional-outbox persistence implemented; publication and audit behavior not
implemented.** Secure GKD currently runs as one synchronous Spring Boot service
backed by PostgreSQL. Docker Compose provides one local-development Kafka broker and
a provisioned `secure-gkd.allocation-created` topic as transport infrastructure. The
allocation service owns the version-1 `AllocationCreated` JSON contract and now
creates and persists one outbox intent for each new Allocation. The application still
has no Kafka client dependency or configuration, outbox publisher, event publication,
allocation-audit consumer/service, or audit-service persistence. The architecture
below distinguishes the implemented local transaction from later publication and
audit work.

The current allocation behavior remains authoritative. `AllocationService.allocate`
owns the PostgreSQL-backed transaction containing Game and GameKey lookup, Allocation,
IdempotencyRecord, and outbox-intent persistence, constraint enforcement, and response
construction.
The allocated game key continues to be returned synchronously. Kafka and downstream
audit availability do not participate in that implemented path.

The local Compose broker is a single combined broker/controller in KRaft mode with
one-partition, replication-factor-one topic settings. Its plaintext listeners and
single-node durability are intentionally local-development choices, not a production
Kafka deployment.

### Context and decision

Allocation audit is a justified asynchronous responsibility because it records an
independent downstream account of an allocation after the allocation service has
decided and committed the authoritative result. Audit processing can have a separate
availability, failure, recovery, retention, and deployment lifecycle without owning
key selection, idempotency, or allocation correctness. Making that work synchronous
would add audit or transport availability to the caller's critical path even though
an audit-processing delay should not prevent a valid allocation.

The target design therefore adds exactly one independently deployable
allocation-audit consumer/service. Ownership remains deliberately coarse:

| Owner | Responsibility and persistence boundary |
| --- | --- |
| Existing Secure GKD backend, acting as the allocation service | Owns Game, GameKey, Allocation, IdempotencyRecord, and `allocation_outbox` behavior and tables. It remains the sole authority for allocation success, synchronous responses, replay behavior, and the PostgreSQL constraints that protect allocation invariants. |
| Allocation-audit service | Consumes committed-allocation events and owns the audit records it derives. It does not select keys, change allocations, decide idempotency outcomes, or participate in allocation correctness. |

The services do not share application-owned persistence tables and must not use
direct database access as their integration contract. The allocation service does
not write the audit service's tables, and the audit service does not read or write
the allocation service's Game, GameKey, Allocation, IdempotencyRecord, or outbox
table.

Kafka is the intended asynchronous transport. The versioned `AllocationCreated`
contract states the fact represented by one successfully created Allocation so the
audit service can later process that fact independently. The event does not transfer
ownership of allocation behavior or become a command that determines whether
allocation succeeds. Secret game-key values must never cross this event boundary.
The complete event fields, versioning and compatibility rules, identity semantics,
and current-versus-future boundary are defined in the
[AllocationCreated event contract](ALLOCATION_CREATED_EVENT.md).

### Transaction and communication boundaries

The target flow preserves one authoritative synchronous transaction while keeping networked publication and downstream processing outside it:

1. The allocation service performs the existing allocation workflow in its
   PostgreSQL transaction. That transaction alone decides whether the allocation
   succeeds, and its database constraints remain the final correctness boundary.
2. The implemented outbox work persists the successful Allocation,
   IdempotencyRecord, and intent to publish `AllocationCreated` atomically in that same
   local transaction. If the transaction rolls back, neither the allocation state nor
   its publish intent is committed. An idempotency replay returns the original
   Allocation rather than creating another one, so it does not represent another
   `AllocationCreated` fact.
3. The caller receives the allocated key synchronously from the allocation service,
   consistent with the service's committed database state. Kafka or audit-service
   availability must not determine whether an otherwise valid allocation succeeds.
4. A separate outbox publisher publishes committed intents to Kafka outside the
   synchronous allocation transaction.
5. The allocation-audit service consumes and persists audit results in its own
   transaction, also outside the synchronous allocation transaction.

Once an allocation commits, publisher, Kafka, delivery, consumer, or audit-persistence
failures must not invalidate it. Recovery retries downstream work instead of
reversing the authoritative allocation.

### Consistency, delivery, and failure assumptions

The synchronous allocation response is consistent with the allocation service's
committed PostgreSQL state. Audit state is eventually consistent and may lag while
publication or processing recovers. The design assumes publication or delivery can
occur more than once; later consumer work must therefore make processing idempotent.
This is not an exactly-once end-to-end delivery claim.

Expected failure conditions include:

| Condition | Architectural consequence |
| --- | --- |
| Kafka is temporarily unavailable after allocation commits | The committed outbox intent remains available for a later publication attempt; the allocation remains valid. |
| The outbox publisher fails or restarts | It resumes from committed outbox state. An uncertain attempt can lead to duplicate publication. |
| An event is published or delivered more than once | The audit consumer must tolerate duplicates through later idempotent-processing design. |
| The audit consumer is unavailable | Audit state falls behind the allocation service's authoritative state until consumption can resume; the allocation remains valid. |
| Audit processing or audit persistence fails | The audit work can be retried according to later operational design; it does not roll back or invalidate the allocation. |

Retry timing, backoff, poison-message handling, and dead-letter policy are deliberately
deferred rather than implied by this decision.

### Why use a transactional outbox

Writing allocation state to PostgreSQL and publishing to Kafka as independent
operations is an unsafe dual write. If PostgreSQL commits and the Kafka publish fails,
the allocation exists without its audit event. If Kafka accepts the event before the
database transaction later fails or rolls back, downstream processing can observe an
allocation that never committed. Reversing the operation order does not remove both
failure windows.

The selected reliability boundary is therefore a transactional outbox owned by the
allocation service. The current implementation commits the allocation state and
publish intent atomically in one PostgreSQL transaction. A later independent
publisher will deliver committed intents to Kafka. This avoids losing the intent
across the database/Kafka boundary while keeping Kafka outside the allocation
transaction. It still permits duplicate publication and delivery, so it requires
idempotent downstream processing and does not provide exactly-once end-to-end
delivery.

### Rejected alternatives and tradeoffs

| Alternative | Reason rejected |
| --- | --- |
| Split Game, GameKey, Allocation, or IdempotencyRecord into separate services | These records participate in one cohesive allocation workflow and PostgreSQL-backed invariant set. Splitting them would distribute the core correctness transaction without an independent business or operational boundary. |
| Make audit processing a synchronous allocation dependency | Audit latency or unavailability would delay or reject an otherwise valid allocation even though audit processing does not decide allocation correctness. |
| Write PostgreSQL state and publish directly to Kafka as independent operations | This creates the dual-write failure windows addressed by the transactional outbox. |
| Share one application-owned database or application-owned tables between allocation and audit services | Shared persistence would bypass the event contract, couple deployments and schema evolution, and blur ownership. Each service owns and accesses only its persistence. |
| Add more services merely to increase the microservice count | Additional network and operational boundaries would add failure modes without isolating another justified responsibility. The single audit boundary is the smallest distributed extension that provides independent audit processing. |

The tradeoff is accepting Kafka and another deployable service, eventual audit
consistency, duplicate-delivery handling, and additional operations. That cost is
justified only because audit processing can evolve and recover independently while
the allocation service remains authoritative and synchronous; it is not a general
decision to decompose the existing domain into microservices.
