# Architecture decisions

This document records the major engineering decisions behind the implemented
two-service architecture. It explains why the boundaries matter and what they do not
provide. For the current runtime structure, complete request-to-audit flow, failure
semantics, and repository evidence, start with
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

## 6. Keep the service containers provider-neutral

### Context and decision

The root `Dockerfile` and `audit-service/Dockerfile` define separate deployable Java
17 container artifacts for the allocation and audit services. Each runtime image
contains only its packaged Spring Boot JAR and runs as a distinct non-root user. The
runtime contracts stay provider-neutral: the deployment environment supplies each
service's datasource configuration and other environment-specific settings, plus the
allocation service's production profile, JWT signing material, and effective port
routing and the asynchronous components' Kafka connectivity.

PostgreSQL and Kafka provisioning, connectivity, credentials, and operations remain
deployment responsibilities outside the service images. Each service runs its own
Flyway migration and validation followed by Hibernate schema validation. The audit
service is a background process and exposes no public HTTP API.

### Consequences and limits

The same service-image contracts can be used without embedding one provider's build
or startup model in the repository. They do not themselves provide PostgreSQL,
Kafka, topics, TLS, network topology, backups, scaling, high availability,
monitoring, or secret-manager integration.

Render is bounded historical verification of the allocation-service contract only:
one temporary Web Service and managed PostgreSQL instance were exercised and then
removed. The audit service and Kafka path were not part of that external exercise.
Render is not an architectural dependency, and the repository does not claim that a
continuously running deployment exists.

See [Deployment runtime](DEPLOYMENT_RUNTIME.md) and
[Render deployment verification](DEPLOYMENT_VERIFICATION.md).

## 7. Add one asynchronous boundary for allocation audit processing

### Status and current-state evidence

**Transactional outbox, asynchronous Kafka publication, and independent audit
consumption/persistence implemented.** Secure GKD contains the authoritative
synchronous allocation service and one independently deployable allocation-audit
background service with its own PostgreSQL persistence. The allocation transaction,
outbox publisher, Kafka transport, idempotent audit consumer, bounded retry, and
dead-letter recovery are current repository behavior rather than a target
architecture. [Architecture](ARCHITECTURE.md) is authoritative for the runtime flow,
failure matrix, and implementation, migration, Compose, test, and CI evidence.

### Context and decision

Allocation audit is a justified asynchronous responsibility because it records an
independent downstream account of an allocation after the allocation service has
decided and committed the authoritative result. Audit processing can have a separate
availability, failure, recovery, retention, and deployment lifecycle without owning
key selection, idempotency, or allocation correctness. Making that work synchronous
would add audit or transport availability to the caller's critical path even though
an audit-processing delay should not prevent a valid allocation.

The implemented design therefore adds exactly one independently deployable
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

Kafka is the asynchronous transport. The versioned `AllocationCreated`
contract states the fact represented by one successfully created Allocation so the
audit service can process that fact independently. The event does not transfer
ownership of allocation behavior or become a command that determines whether
allocation succeeds. Secret game-key values must never cross this event boundary.
The complete event fields, versioning and compatibility rules, identity semantics,
and current-versus-future boundary are defined in the
[AllocationCreated event contract](ALLOCATION_CREATED_EVENT.md).

### Transaction and communication boundaries

The allocation service's PostgreSQL transaction remains the sole correctness
boundary. It atomically persists a newly created Allocation, IdempotencyRecord, and
`AllocationCreated` outbox intent, then returns the allocated GameKey synchronously.
Kafka publication and audit processing occur after that transaction and cannot turn
a valid committed allocation into a failure.

The allocation and audit transactions are separate local transactions, and Kafka
offset progress is separate again. Publication and delivery are therefore
at-least-once: acknowledged publication can be repeated when status recording is
uncertain, and audit persistence can be redelivered when offset progress is
uncertain. Audit-owned uniqueness on the stable `eventId` makes the repeated audit
effect a successful no-op, but that idempotency is not an exactly-once distributed
transaction. Audit state is eventually consistent. The complete recovery behavior,
including non-retryable contract failures, bounded retryable failures, dead-letter
publication, and dead-letter publication failure, is consolidated in
[Architecture](ARCHITECTURE.md#allocation-event-contract-outbox-persistence-and-publication).

### Why use a transactional outbox

Writing allocation state to PostgreSQL and publishing to Kafka as independent
operations is an unsafe dual write. If PostgreSQL commits and the Kafka publish fails,
the allocation exists without its audit event. If Kafka accepts the event before the
database transaction later fails or rolls back, downstream processing can observe an
allocation that never committed. Reversing the operation order does not remove both
failure windows.

The selected reliability boundary is therefore a transactional outbox owned by the
allocation service. The current implementation commits the allocation state and
publish intent atomically in one PostgreSQL transaction. An independent scheduled
publisher delivers committed pending intents to Kafka and records `published_at`
only after producer acknowledgement. This avoids losing the intent
across the database/Kafka boundary while keeping Kafka outside the allocation
transaction. It still permits duplicate publication and delivery. The audit
consumer's database-backed idempotent effect makes those duplicates harmless for
one logical source event, but PostgreSQL commit and Kafka offset progress remain
separate operations and do not provide exactly-once end-to-end delivery.

### Rejected alternatives and tradeoffs

| Alternative | Reason rejected |
| --- | --- |
| Split Game, GameKey, Allocation, or IdempotencyRecord into separate services | These records participate in one cohesive allocation workflow and PostgreSQL-backed invariant set. Splitting them would distribute the core correctness transaction without an independent business or operational boundary. |
| Make audit processing a synchronous allocation dependency | Audit latency or unavailability would delay or reject an otherwise valid allocation even though audit processing does not decide allocation correctness. |
| Write PostgreSQL state and publish directly to Kafka as independent operations | This creates the dual-write failure windows addressed by the transactional outbox. |
| Share one application-owned database or application-owned tables between allocation and audit services | Shared persistence would bypass the event contract, couple deployments and schema evolution, and blur ownership. Each service owns and accesses only its persistence. |
| Add a separate audit processed-event table | The audit record already retains the stable source event UUID, so making that column unique supplies durable duplicate protection without a second persistence model. |
| Add more services merely to increase the microservice count | Additional network and operational boundaries would add failure modes without isolating another justified responsibility. The single audit boundary is the smallest distributed extension that provides independent audit processing. |

The tradeoff is accepting Kafka and another deployable service, eventual audit
consistency, duplicate-delivery handling, and additional operations. That cost is
justified only because audit processing can evolve and recover independently while
the allocation service remains authoritative and synchronous; it is not a general
decision to decompose the existing domain into microservices. The Compose broker's
single-node, plaintext, replication-factor-one topology is local-development
infrastructure, not evidence of a production Kafka deployment. Automated dead-letter
replay is not implemented. Pending and published outbox rows, dead-letter records,
and audit rows have no application-owned automatic retention or cleanup policy.
