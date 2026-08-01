# Backend Performance Risks and Evidence

This assessment applies to the current Secure GKD backend, especially
`POST /api/games/{gameCode}/allocations`. It summarizes existing evidence instead of
repeating the underlying experiments, and it does not recommend an optimization.

## Evidence categories and existing records

- **Static source inspection**: current Java source, `application.yaml`, `pom.xml`,
  and test source.
- **Mock-based test evidence**: `AllocationServiceTests` and controller tests verify
  workflow and HTTP decisions without executing JPA or PostgreSQL.
- **PostgreSQL-backed integration evidence**: repository, rollback, and concurrency
  tests exercise a PostgreSQL test schema created with test-only
  `ddl-auto=create-drop`.
- **Running-server evidence**: bounded random-port integration tests send real HTTP
  requests through embedded Tomcat and PostgreSQL.
- **JFR-recorded evidence**: one bounded four-request recording supplies samples and
  duration-bearing events, not a benchmark or complete trace.
- **Framework-supported interpretation**: conclusions about Spring transactions,
  lazy loading, servlet workers, and Hikari lifecycle where the framework model is
  consistent with, but not fully measured by, the observations.
- **Unmeasured**: production latency, throughput, capacity, pool saturation, query
  plans at representative cardinality, allocation rates, and production log volume.

The detailed evidence remains in:

- [allocation SQL and lazy loading](ALLOCATION_SQL_DEBUGGING.md), including the one-row
  available-key query and why fixed multiple statements are not N+1;
- [transaction flow and rollback](ALLOCATION_RUNTIME_DEBUGGING.md) and
  [controlled PostgreSQL concurrency](ALLOCATION_CONCURRENCY.md);
- [synchronous request-thread blocking](ALLOCATION_REQUEST_THREADS.md) and
  [datasource/connection-pool lifecycle](ALLOCATION_DATASOURCE_LIFECYCLE.md);
- [Java object and persistence-context lifetime](ALLOCATION_OBJECT_LIFETIME.md),
  [JVM memory and GC](JVM_MEMORY_AND_GC.md), and
  [bounded JFR profiling](ALLOCATION_JFR_PROFILING.md).

## Priority summary

| Classification | Current conclusion |
| --- | --- |
| Currently observed | The allocation endpoint performs synchronous JDBC work on its servlet request thread; a controlled PostgreSQL wait kept that worker and transaction occupied until the database unblocked. This is an execution characteristic, not evidence of a current throughput or capacity problem. |
| Worth monitoring as usage grows | Database/transaction duration, connection-pool wait and saturation, the available-key query on representative data, and logging volume in the deployed environment. None is currently demonstrated as a bottleneck. |
| Not evidenced | N+1 behavior, excessive repeated queries, unnecessary object allocation, a long-transaction problem, pool exhaustion, missing/ineffective production indexes, excessive/sensitive production logging, or a reproducible bottleneck that warrants optimization. |
| Not applicable to current exposed behavior | A large allocation result or unbounded collection response: key selection is limited to one row and the endpoint returns one `AllocationResponse`. Caching is not implemented and is not currently justified. |

## Risk assessment

### Repeated queries

- **Current implementation and evidence:** static inspection of `AllocationService`
  shows a fixed workflow: idempotency lookup; on a new allocation, game lookup,
  one available-key selection, allocation persistence, and idempotency persistence.
  The replay path may initialize a fixed lazy chain. The SQL note records one
  PostgreSQL-backed `findAvailableByGame` invocation as one select; endpoint statement
  counts and replay lazy-select counts were not observed.
- **Status and consequence:** **not observed as a problem**. If fixed or duplicate
  queries later dominate request time, they would add database work and hold the
  synchronous request/transaction resources longer.
- **Investigate when:** repeated comparable request traces show redundant statement
  shapes or query time is a material part of reproducible request delay.
- **Optimize only with:** branch-specific SQL counts and timings from a representative
  controlled workload, followed by a change that removes demonstrated duplicate work
  without changing allocation or idempotency behavior.

### N+1 query behavior

- **Current implementation and evidence:** static inspection finds no service loop
  over a parent collection. `findAvailableByGame` contains its `not exists` predicate
  in one statement and the service supplies `PageRequest.of(0, 1)`. The endpoint
  returns one `AllocationResponse`, not a collection. PostgreSQL-backed repository
  evidence observed no per-key follow-up allocation query.
- **Status and consequence:** **structurally bounded and not observed**. Multiple fixed
  statements for different workflow steps, or fixed lazy loads for one replay, do not
  demonstrate N+1. A real N+1 path would make query count grow with collection size.
- **Investigate when:** a collection endpoint or batch workflow is introduced, or SQL
  capture shows the same association query repeating once per returned item.
- **Optimize only with:** a reproducible query-count-versus-result-size relationship
  and a focused test that preserves DTO and transaction behavior.

### Unnecessary object creation

- **Current implementation and evidence:** static inspection shows small request and
  response records plus the entities and persistence objects required by one
  allocation. Running-server object-lifetime evidence follows those references across
  one request. The memory test records bounded JVM snapshots, and JFR records sampled
  allocations during four requests; neither measures per-type allocation rates or
  identifies waste.
- **Status and consequence:** **not evidenced**. If excessive temporary allocation
  emerges, it could increase allocation pressure and GC work.
- **Investigate when:** comparable profiles show sustained allocation-rate or GC-cost
  growth attributable to specific types on the request path.
- **Optimize only with:** repeatable per-type allocation/retention evidence and a
  controlled result showing a meaningful improvement without obscuring DTO/entity
  boundaries or changing behavior.

### Large or unbounded result sets

- **Current implementation and evidence:** `findAvailableByGame` returns a `List`, but
  the allocation service always passes `PageRequest.of(0, 1)` and then selects its
  first element. `AllocationController.allocate` returns a single
  `AllocationResponse`. The game lookup also returns one optional result; no current
  public controller returns an entity collection. Repository tests confirm the
  one-result page behavior.
- **Status and consequence:** **structurally bounded for current exposed paths**. If a
  future endpoint exposes inherited repository collection operations without paging,
  database transfer and heap retention could grow with row count.
- **Investigate when:** a collection/batch endpoint is added or representative traces
  show result size and materialization contributing materially to memory or latency.
- **Optimize only with:** a defined result-size contract, representative cardinality,
  and measured evidence supporting pagination, streaming, or projection changes.

### Blocking synchronous work

- **Current implementation and evidence:** static inspection and resolved dependencies
  show Spring MVC, embedded Tomcat, JPA, and JDBC. PostgreSQL-backed running-server
  evidence observed the same Tomcat worker from HTTP entry through a deliberately
  blocked PostgreSQL call; the response completed only after the database unblocked.
  JFR also recorded socket and waiting events during a bounded request sequence.
- **Status and consequence:** synchronous blocking is **observed**, but a performance
  problem is **not demonstrated**. Slow database work can keep a request worker and
  transaction-associated resources occupied for longer; no throughput or capacity
  limit has been measured.
- **Investigate when:** representative requests show persistent database waits,
  worker-queue growth, or request time dominated by blocking downstream work.
- **Optimize only with:** correlated request, worker, database-wait, and latency
  evidence under an approved representative workload. Current evidence does not
  justify asynchronous, reactive, executor, virtual-thread, or pool-tuning changes.

### Unnecessarily long transactions

- **Current implementation and evidence:** `AllocationService.allocate` has one
  `@Transactional` boundary covering the idempotency lookup, game/key selection,
  writes, and DTO construction. PostgreSQL-backed tests observe that boundary,
  rollback after a flushed insert, and an active transaction during the controlled
  database wait. No normal-path transaction-duration distribution was measured.
- **Status and consequence:** **currently unverified; not demonstrated as too long**.
  If transactions become slow, they can retain a connection and database resources
  longer and widen the overlap window between allocators.
- **Investigate when:** transaction timings correlate with slow SQL, lock waits,
  connection acquisition delay, or request delay in representative executions.
- **Optimize only with:** reproducible transaction-boundary timing and operation-level
  attribution showing which in-transaction work can safely change while preserving
  rollback, idempotency, and duplicate-allocation semantics.

### Connection-pool pressure

- **Current implementation and evidence:** the datasource note resolves and observes
  HikariCP with PostgreSQL in the test context. Running-server evidence associates one
  request transaction with a JDBC handle and PostgreSQL session. Tracked configuration
  sets no Hikari sizes or timeouts. Existing observations did not exhaust the pool,
  establish its deployment capacity, or prove physical-connection reuse.
- **Status and consequence:** **currently unverified; worth monitoring as concurrency
  grows**. If checkout demand exceeds available connections, requests can wait for a
  connection and synchronous workers can remain occupied.
- **Investigate when:** pool metrics show sustained pending acquisition, timeouts, or
  high utilization correlated with request delay and database session activity.
- **Optimize only with:** deployment-specific pool configuration and correlated
  worker, pool, transaction, and database measurements. A single connection identity
  or framework default is not capacity evidence.

### Missing or ineffective database indexes

- **Current implementation and evidence:** entity mappings source-derive uniqueness
  requirements for `games.code`, `game_keys.code`, `allocations.game_key_id`,
  `idempotency_records.idempotency_key`, and `idempotency_records.allocation_id`.
  PostgreSQL-backed tests using a Hibernate-created test schema observe those
  uniqueness rules; the concurrency evidence directly identifies the generated
  `allocations_game_key_id_key` constraint. The available-key query filters by
  `game_keys.game_id`, checks `allocations.game_key_id`, and orders by key ID.
  Production uses `ddl-auto: none`, and no tracked migration or schema definition was
  found, so the deployed schema's indexes were not established here.
- **Status and consequence:** **not demonstrated; deployed-schema support is
  unverified** except for the controlled test-schema evidence above. If representative
  plans show avoidable high-cost scans as tables grow, allocation lookup time and
  transaction occupancy could increase.
- **Investigate when:** a bounded read-only catalog inspection of the actual controlled
  schema disagrees with required constraints, or representative-cardinality query
  plans and timings show a stable expensive access path. A sequential scan on a tiny
  test dataset is not sufficient evidence.
- **Optimize only with:** observed schema definitions plus repeatable
  `EXPLAIN (ANALYZE, BUFFERS)` or equivalent evidence on safe representative data,
  tied to measured request/database cost. This assessment performs no schema change.

### Excessive or sensitive logging

- **Current implementation and evidence:** static inspection finds no production
  logger calls and no tracked logging-level configuration. Some integration tests emit
  bounded diagnostics through test loggers or `System.out`; prior datasource and SQL
  investigations used command-line-only logging. Those are temporary test diagnostics,
  not permanent production logging. No deployed log sample or volume was inspected.
- **Status and consequence:** **not observed in production source; deployed behavior
  is unverified**. Excessive logging could add I/O/storage cost, while sensitive fields
  or exception details could create disclosure risk.
- **Investigate when:** deployed log-rate/storage metrics grow, request latency
  correlates with logging, or a controlled review finds credentials, keys, request
  bodies, or unredacted sensitive exception data.
- **Optimize only with:** a reproducible volume/cost or data-exposure finding and a
  defined retention/redaction requirement. Temporary diagnostics should remain
  bounded and untracked.

### Premature caching

- **Current implementation and evidence:** source and dependency inspection find no
  application cache, cache provider, or cache configuration. Existing SQL, memory,
  and JFR evidence does not identify a repeated expensive read whose cost caching
  would remove. Allocation availability and idempotency are consistency-sensitive
  database state.
- **Status and consequence:** **not applicable as an implemented risk and not
  justified as an optimization**. An ill-defined cache could serve stale availability
  or idempotency data and complicate invalidation while adding memory and operational
  cost.
- **Investigate when:** a repeated expensive operation is reproducibly demonstrated
  and its callers can state an acceptable staleness model.
- **Optimize only with:** measured benefit, explicit cache keys and bounds, an
  intentional consistency/invalidation strategy, and tests preserving allocation and
  idempotency invariants.

### Optimization without reproducible evidence

- **Current implementation and evidence:** the bounded JFR run observed samples,
  waits, socket I/O, allocations, and one run's client timings, but identified no
  reproducible slow path or bottleneck. The memory snapshots, tiny test schemas, fixed
  SQL statements, and controlled lock waits each have documented limits.
- **Status and consequence:** **no optimization need is evidenced**. Acting on an
  isolated sample or framework assumption could add complexity or alter correctness
  without improving representative behavior.
- **Investigate when:** a user-visible or operational symptom is repeatable with a
  controlled workload and can be correlated to a specific resource or operation.
- **Optimize only with:** a baseline, a falsifiable hypothesis, representative
  measurements, correctness checks, and a before/after result. Exact severity scores,
  latency targets, throughput limits, and production probabilities are intentionally
  not invented here.

## Inspection and verification for this assessment

The assessment used source and documentation inspection only; it did not run the
application, Maven tests, PostgreSQL queries, query plans, or a new JFR recording.
PostgreSQL and running-server claims above are reused from the linked records, which
contain their exact commands, observation procedures, results, and limitations.

Commands run for this assessment included:

```powershell
git rev-parse --show-toplevel
git branch --show-current
git status --short
rg --files
rg -n "@Transactional|PageRequest\.of\(0, 1\)|findAvailableByGame|ResponseEntity<AllocationResponse>|toResponse" src/main/java src/test/java
rg -n "@(Column|JoinColumn).*unique = true|ddl-auto|open-in-view|datasource|hikari|server\.|logging\.level" src/main/java src/main/resources src/test/java pom.xml
rg -n "Logger|LoggerFactory|System\.(out|err)|logging\.level" src/main/java src/main/resources src/test/java
rg --files | rg "(^|/|\\)(db|migration|migrations|schema)(/|\\)|schema\.(sql|ddl)$|logback|log4j"
git diff --check
git status --short
git diff --stat
git diff
```

Content review covered the controllers, services, repositories, entities, DTOs,
tracked configuration, Maven dependencies, focused unit/repository/integration test
source, and every linked evidence document. The final Git commands completed after
the documentation edits; `git diff --check` passed. No temporary diagnostic artifact,
tracked configuration change, source/test change, schema inspection, or PostgreSQL
execution was required for this documentation-only assessment.
