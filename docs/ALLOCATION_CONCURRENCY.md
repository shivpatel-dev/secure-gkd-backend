# Allocation Concurrency Evidence

This note records one controlled PostgreSQL-backed test of the current allocation
workflow when two transactions compete for one game key. It complements the complete
[`ALLOCATION_RUNTIME_DEBUGGING.md`](ALLOCATION_RUNTIME_DEBUGGING.md) flow and does not
define new allocation behavior.

## Evidence categories

- **Static source inspection** covers `AllocationService`, `findAvailableByGame`, the
  allocation mapping, and the idempotency mapping.
- **PostgreSQL-backed integration-test evidence** comes from
  `AllocationConcurrencyIntegrationTests`.
- **Framework-supported inference** is limited to Spring's rollback behavior after the
  unchecked service exception leaves the `@Transactional` proxy.

No HTTP request, running server, CI job, stress test, or production-scale workload was
used.

## Controlled overlap

The test creates one game with exactly one key and calls
`AllocationService.allocate` twice with different idempotency keys. A fixed two-thread
executor invokes the Spring-managed service proxy, so each worker enters its own
`@Transactional` call. At the repository boundary the test verified that both workers
had active, distinct Spring transaction statuses on distinct threads.

A test-only spy around `GameKeyRepository.findAvailableByGame` delegates to a real
Spring Data repository proxy backed by the thread-bound `EntityManager`. After each
real query returns, a bounded two-party barrier holds that transaction until the other
query has also returned. The test recorded that both queries selected the same single
key before either service call could continue to allocation persistence. Barrier
waits, future waits, the test, and executor termination all have bounded timeouts.

This is stronger evidence of overlap than starting two tasks at approximately the
same time: both allocation transactions completed key selection before either passed
that boundary.

## Observed outcome

Both `findAvailableByGame` calls saw the key as available. The query excludes keys
that already have an allocation, but it does not reserve or lock the selected row.
Because the barrier stopped both transactions immediately after selection, neither
had inserted an allocation when either query ran.

After the barrier released:

- one call returned a successful `AllocationResponse`;
- the competing call failed with
  `IllegalStateException("Selected game key is no longer available for game: GTA5")`;
- its preserved cause chain contained
  `DataIntegrityViolationException`, Hibernate
  `ConstraintViolationException`, and PostgreSQL `PSQLException`;
- the PostgreSQL exception reported SQL state `23505` and identified
  `game_key_id`; and
- PostgreSQL output identified the violated test-schema constraint as
  `allocations_game_key_id_key`.

`AllocationRepository.saveAndFlush` is the application workflow boundary that sends
the allocation insert to PostgreSQL while execution remains inside
`AllocationService.saveAllocation`. That lets the service catch Spring's
`DataIntegrityViolationException`, translate it to the allocation-specific
`IllegalStateException`, and preserve the database failure as its cause.

The final protection is PostgreSQL's unique constraint for
`allocations.game_key_id`, derived from the unique `Allocation.gameKey` mapping.
Application selection reduces candidates and the service translates the losing
outcome; PostgreSQL prevents both allocations from being stored.

## Final database state

After both futures completed, direct database queries and repository assertions
established:

- exactly one allocation row existed, referencing the single game key;
- exactly one idempotency row existed;
- that row referenced the persisted allocation and used the successful attempt's
  idempotency key; and
- no idempotency row existed for the failed attempt.

The losing transaction had flushed its attempted allocation but left no allocation or
idempotency row. The final persisted state is PostgreSQL-backed rollback evidence.
Spring rolling the transaction back when the unchecked translated exception crossed
the transactional proxy is framework-supported interpretation consistent with that
observed final state.

## Reproduction

After loading the configured datasource variables into the current PowerShell process
without printing them, the focused command was:

```powershell
.\mvnw.cmd "-Dtest=AllocationConcurrencyIntegrationTests" test
```

Actual result:

- Maven and the test started under Java 17.0.18.
- The configured datasource reported PostgreSQL 16.14.
- Result: 1 test, 0 failures, 0 errors, 0 skipped.
- Maven completed with `BUILD SUCCESS`.

The complete suite command was:

```powershell
.\mvnw.cmd test
```

Actual result: 36 tests, 0 failures, 0 errors, 0 skipped, and `BUILD SUCCESS`.

No diagnostic logging setting was added to tracked configuration, and no raw output
was retained.

## Limits

This test controls one two-transaction collision against one key in a test schema
created with `ddl-auto=create-drop`. It demonstrates the current selection race,
exception translation, database constraint, and final state for that scenario. It
does not establish production throughput, timing, fairness, probability of collision,
behavior under larger workloads, HTTP behavior, connection-pool capacity, or a need
for locking, retries, isolation changes, or performance work.
