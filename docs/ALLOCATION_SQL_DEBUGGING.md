# Allocation SQL and Lazy-Loading Debugging Notes

These notes focus on SQL and association loading for:

`POST /api/games/{gameCode}/allocations`

For the complete request flow and failure paths, see
[`ALLOCATION_RUNTIME_DEBUGGING.md`](ALLOCATION_RUNTIME_DEBUGGING.md) and
[`ALLOCATION_FAILURE_DEBUGGING.md`](ALLOCATION_FAILURE_DEBUGGING.md).
Use the main runtime document for the complete recommended documentation path.

## Evidence scope

This document combines **static source inspection** with one successful
**PostgreSQL-backed repository evidence**. SQL from that test retains that label. The
new-allocation and replay statement shapes remain labeled **source-derived** because
the historical SQL-inspection application did not start far enough to accept
requests.

The first application attempt failed before Maven started because Java was not
available on that PowerShell process's `PATH`. A later retry prepended the configured
JDK `bin` directory for the current process; full-path Java reported OpenJDK 17.0.18
and `.\mvnw.cmd -version` reported Maven 3.9.16.

The controlled application retry used temporary command-line SQL logging and a
temporary initializer:

```powershell
.\mvnw.cmd '-Dspring-boot.run.arguments=--server.port=18041 --spring.output.ansi.enabled=never --logging.level.org.hibernate.SQL=DEBUG --spring.sql.init.mode=always --spring.sql.init.data-locations=file:./.issue41-runtime-seed.sql --spring.jpa.defer-datasource-initialization=true' spring-boot:run
```

Maven and Spring Boot started, and the PostgreSQL JDBC connection reported database
version 16.14. Startup then failed on the first temporary initializer statement:

```text
ERROR: relation "idempotency_records" does not exist
```

At the time of this historical inspection, Hibernate schema creation was disabled and
the repository did not yet contain Flyway migrations, so startup did not create the
missing schema. Therefore:

- the application never became ready to accept HTTP requests;
- neither allocation request was sent;
- no new-allocation or replay SQL was observed;
- no endpoint statement counts were established;
- the initializer changed no database rows; and
- the temporary seed and cleanup files were removed without changing tracked
  configuration.

Representative endpoint SQL below describes source-derived statement shapes rather
than captured Hibernate output. Generated aliases, selected columns, statement count,
and insert timing may differ when the endpoint can be run against an initialized
schema.

Later running-server investigations successfully exercised new allocations through
embedded Tomcat and PostgreSQL; see
[`ALLOCATION_REQUEST_THREADS.md`](ALLOCATION_REQUEST_THREADS.md),
[`ALLOCATION_OBJECT_LIFETIME.md`](ALLOCATION_OBJECT_LIFETIME.md),
[`JVM_MEMORY_AND_GC.md`](JVM_MEMORY_AND_GC.md), and
[`ALLOCATION_JFR_PROFILING.md`](ALLOCATION_JFR_PROFILING.md). Those tests were designed
for thread, reference, memory, or JFR evidence, not complete Hibernate SQL capture.
They do not retroactively supply endpoint statement counts, replay lazy-loading
sequences, query timings, or query plans for this document.

## Successful new allocation

**Source-derived.** The new-allocation branch implies the following persistence
operations in order:

1. Look up the idempotency key.
2. Look up the game by code.
3. Select the first key for that game that has no allocation.
4. Insert the allocation and flush it.
5. Save the idempotency record.

No successful new-allocation request ran during the historical SQL inspection, so
there is no observed endpoint statement count for this path. Later successful
running-server requests did not capture a complete SQL sequence.

Representative statement shapes are:

```sql
select ...
from idempotency_records
where idempotency_key = ?
```

```sql
select ...
from games
where code = ?
```

```sql
select game_key_columns
from game_keys
where game_id = ?
  and not exists (
      select allocation_id
      from allocations
      where allocations.game_key_id = game_keys.id
  )
order by game_keys.id
fetch first ? rows only
```

```sql
insert into allocations (allocated_at, game_key_id, ...)
values (?, ?, ...)
```

```sql
insert into idempotency_records
    (allocation_id, created_at, idempotency_key, ...)
values (?, ?, ?, ...)
```

`AllocationRepository.saveAndFlush` requires the allocation insert to be flushed
before `saveAllocation` returns. This lets a database constraint failure surface
inside its `try` block. `IdempotencyRecordRepository.save` does not explicitly flush;
the persistence provider may issue its insert while saving an identity-generated
entity or at a later flush or commit. The exact timing was not observed.

`findAvailableByGame` returns a managed `GameKey`, and the `Allocation` created by the
service directly references that object. The earlier game lookup also leaves the
corresponding `Game` managed in the same persistence context. Consequently,
`toResponse` is not expected to need an additional select on this branch: it walks
objects already loaded or created in the current transaction. This conclusion is
source-derived, not confirmed by a SQL log.

**Existing-test coverage.**
`AllocationServiceTests.allocateSavesAllocationAndIdempotencyRecordForNewKey` uses
repository mocks to verify the branch and page size of one. It does not execute JPA,
Hibernate, SQL, or PostgreSQL.

## Successful idempotency replay

**Source-derived.** The replay branch performs the idempotency-key lookup and skips the
game lookup, available-key query, allocation insert, and idempotency-record insert.
The response still needs the original allocation, its game key, and the game.

No replay request ran during the historical SQL inspection, so there is no observed
statement count or observed lazy-initialization sequence for this path. The later
running-server investigations exercised new allocations, not a replay SQL capture.

All three entity relationships on that path are explicitly lazy:

```text
IdempotencyRecord.allocation -> Allocation.gameKey -> GameKey.game
```

With newly loaded entities and ordinary Hibernate proxies, the expected statement
sequence is one idempotency-record select followed by selects that initialize the
allocation, game key, and game as `toResponse` accesses them:

```sql
select ...
from idempotency_records
where idempotency_key = ?
```

```sql
select ...
from allocations
where id = ?
```

```sql
select ...
from game_keys
where id = ?
```

```sql
select ...
from games
where id = ?
```

These are representative, source-derived shapes. The precise number of statements can
depend on what is already present in the persistence context and on provider behavior;
this sequence was not captured at runtime.

**Existing-test coverage.**
`AllocationServiceTests.allocateReturnsOriginalAllocationForExistingIdempotencyKey`
verifies with mocks that the replay avoids the game, game-key, and allocation
repositories and does not save another idempotency record. Its entities are ordinary
Java objects rather than Hibernate proxies, so it cannot establish replay SQL or lazy
loading behavior.

## `findAvailableByGame`

`GameKeyRepository.findAvailableByGame` declares JPQL that:

- restricts keys to the supplied `Game`;
- excludes any key for which an `Allocation` exists;
- orders remaining keys by `GameKey.id`; and
- accepts a `Pageable`.

The service passes `PageRequest.of(0, 1)`, so Hibernate applies a one-row limit. The
query returns `GameKey` entities only; it contains no fetch join and does not eagerly
load `GameKey.game`. The `not exists` subquery is part of the single key-selection
statement. It is not a separate select executed once per returned key.

`GameKeyRepositoryTests.findAvailableByGameExcludesAllocatedGameKeys` and
`findAvailableByGameReturnsFirstAvailableGameKeyWhenLimitedToOne` are PostgreSQL-backed
JPA slice tests when their configured datasource is available because
`@AutoConfigureTestDatabase(replace = NONE)` prevents replacement with an embedded
database. They cover exclusion, ordering, and pagination.

**PostgreSQL-backed repository evidence.** This focused command ran the second test
with temporary Hibernate SQL logging:

```powershell
.\mvnw.cmd '-Dtest=GameKeyRepositoryTests#findAvailableByGameReturnsFirstAvailableGameKeyWhenLimitedToOne' '-Dlogging.level.org.hibernate.SQL=DEBUG' test
```

The test connected through `org.postgresql.jdbc.PgConnection`, reported PostgreSQL
16.14, and completed with one test run, no failures, no errors, no skips, and
`BUILD SUCCESS`. Excluding schema setup, test-data cleanup, and fixture inserts, the
`findAvailableByGame` invocation emitted exactly one select:

```sql
select
    gk1_0.id,
    gk1_0.code,
    gk1_0.created_at,
    gk1_0.game_id
from game_keys gk1_0
where gk1_0.game_id = ?
  and not exists (
      select a1_0.id
      from allocations a1_0
      where a1_0.game_key_id = gk1_0.id
  )
order by gk1_0.id
fetch first ? rows only
```

Hibernate kept the `not exists` predicate inside that one SQL statement and generated
`fetch first ? rows only` for the `PageRequest.of(0, 1)` limit. No separate
per-key allocation query was emitted by this repository method.

## Lazy association checkpoints

| Association | Mapping | Access that may initialize it |
| --- | --- | --- |
| `IdempotencyRecord.allocation` | `@OneToOne(fetch = LAZY)` | The replay path reaches the allocation before `toResponse`; accessing allocation state may initialize its proxy. |
| `Allocation.gameKey` | `@OneToOne(fetch = LAZY)` | `AllocationService.toResponse` calls `allocation.getGameKey()`. |
| `GameKey.game` | `@ManyToOne(fetch = LAZY)` | `AllocationService.toResponse` calls `gameKey.getGame()`, then `game.getCode()`. |

A getter returning a proxy does not necessarily execute SQL immediately. SQL is needed
when Hibernate must initialize that proxy to read entity state. The first access that
requires state is the useful logging or debugger checkpoint.

`toResponse` can therefore cause lazy-loading selects on a replay, where the object
graph starts from a repository-loaded `IdempotencyRecord`. It is not expected to cause
them on the new-allocation branch because that branch already has the managed `Game`
and `GameKey`, and directly constructs the `Allocation`.

## Transaction and open-in-view

`spring.jpa.open-in-view=false` prevents the web layer from relying on an open
persistence context after the service returns. `AllocationService.allocate` is
`@Transactional`, and `toResponse` runs before that public method returns. Its lazy
association access therefore occurs while the transaction and service persistence
context are active.

Moving entity traversal outside that boundary could leave uninitialized associations
detached and cause a lazy-initialization failure. The current DTO conversion avoids
that dependency: the controller receives an `AllocationResponse`, not a JPA entity.

## Multiple statements versus N+1

Several distinct statements are normal for this workflow. The service performs
different jobs against different tables: idempotency lookup, game lookup, available
key selection, allocation persistence, idempotency persistence, and possibly lazy
initialization during a replay. A statement count greater than one is not itself an
N+1 problem.

N+1 describes one query that loads multiple parent items followed by a repeated
per-item query while iterating those items. The allocation endpoint produces one
response and limits key selection to one row. No inspected service loop traverses a
collection and initializes an association once per element.

The observed repository method used one select and did not repeat an allocation query
per returned key. The endpoint paths did not run under this SQL capture, so there is
still no runtime evidence for the replay's lazy-initialization sequence. Taken
together with the absence of a collection traversal in the inspected service source,
the evidence does not establish an N+1 pattern. Such a conclusion would require the
same association select to repeat in proportion to a result-set size, not merely fixed
lazy selects for one replay.

## Reproducible inspection checkpoints

The focused repository-test command above was confirmed against the configured
PostgreSQL datasource. The historical SQL inspection predates the tracked Flyway
baseline and did not create the normal application schema. Current application and
running-server test startup applies the versioned migrations, but those tests did not
enable a complete endpoint SQL capture.

Once that prerequisite is satisfied, use a controlled request with
command-line-only SQL logging and remove any temporary seed rows afterward. Useful
checkpoints are:

1. Log `org.hibernate.SQL` at `DEBUG` for the inspection process only.
2. Send one allocation request with a new idempotency key, then repeat the identical
   request.
3. Separate SQL emitted before the first response from SQL emitted before the replay
   response.
4. Break at `AllocationService.allocate` after the idempotency lookup to identify the
   selected branch.
5. Break before and after `GameKeyRepository.findAvailableByGame` to correlate its
   single SQL statement with the returned page.
6. Break at each line of `AllocationService.toResponse` and watch when the allocation,
   game-key, and game proxies become initialized.
7. Confirm the datasource from the successful runtime itself before labeling any
   observation PostgreSQL-specific.

Do not use `AllocationServiceTests` as SQL evidence: those tests mock every repository
and intentionally do not start JPA.
