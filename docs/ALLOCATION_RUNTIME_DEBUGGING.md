# Allocation Runtime and Debugging Notes

This document is the entry point for the complete allocation runtime, debugging, and
performance documentation set. It describes the current implementation of:

`POST /api/games/{gameCode}/allocations`

These notes are intended for tracing existing behavior, not for defining new API
behavior. The linked documents preserve the commands, arrangements, observed values,
and limitations of each investigation instead of repeating them here.

## Recommended reading order

1. Start with the request and transaction flow in this document, then use
   [`ALLOCATION_FAILURE_DEBUGGING.md`](ALLOCATION_FAILURE_DEBUGGING.md) to separate
   HTTP responses, Java exceptions, cause chains, and logged stack traces.
2. Continue with [`ALLOCATION_SQL_DEBUGGING.md`](ALLOCATION_SQL_DEBUGGING.md) for
   source-derived SQL shapes and the bounded repository observation, then
   [`ALLOCATION_DATASOURCE_LIFECYCLE.md`](ALLOCATION_DATASOURCE_LIFECYCLE.md) for
   rollback, Hibernate logical connections, HikariCP, and pool-lifecycle limits.
3. Read [`ALLOCATION_CONCURRENCY.md`](ALLOCATION_CONCURRENCY.md) for the controlled
   two-transaction PostgreSQL collision, followed by
   [`ALLOCATION_REQUEST_THREADS.md`](ALLOCATION_REQUEST_THREADS.md) for synchronous
   embedded-Tomcat request-thread execution during a confirmed PostgreSQL wait.
4. Follow the Java references and request-bound persistence context in
   [`ALLOCATION_OBJECT_LIFETIME.md`](ALLOCATION_OBJECT_LIFETIME.md), then distinguish
   reachability, heap and non-heap observations, and garbage-collection limits in
   [`JVM_MEMORY_AND_GC.md`](JVM_MEMORY_AND_GC.md).
5. Finish with [`ALLOCATION_JFR_PROFILING.md`](ALLOCATION_JFR_PROFILING.md) for the
   bounded JFR samples and duration-bearing events, then
   [`BACKEND_PERFORMANCE_RISKS.md`](BACKEND_PERFORMANCE_RISKS.md) for the resulting
   evidence-based risk assessment and the measurements that remain absent.

## Evidence categories used across the set

- **Static source inspection** establishes what tracked source, configuration, and
  dependencies declare.
- **Mock-based test evidence** isolates controller or service decisions without JPA,
  Hibernate, or PostgreSQL.
- **PostgreSQL-backed repository or integration evidence** exercises the configured
  PostgreSQL datasource without necessarily crossing an HTTP server boundary.
- **Running-server evidence** sends real HTTP requests through embedded Tomcat and the
  application stack; each detailed note states whether PostgreSQL was also verified.
- **JDK management-interface evidence** is limited to point-in-time JVM management
  readings and cumulative counters from the bounded memory observation.
- **JFR-recorded evidence** consists of samples and configured events from one bounded
  whole-process recording, not a complete trace or benchmark.
- **Framework-supported interpretation** connects observed boundaries using Spring,
  Hibernate, HikariCP, servlet, JDBC, and JVM behavior that the specific observation
  did not measure directly.
- **Unmeasured or unverified behavior** remains explicitly labeled; absence from a
  bounded observation is not proof that an event, cost, or production risk is absent.

Historical documents retain the evidence available when they were prepared. Later
evidence is linked and labeled separately rather than rewritten as part of an older
observation.

## How the runtime evidence connects

1. An HTTP request enters embedded Tomcat on a servlet worker. Spring MVC binds and
   validates `AllocationRequest`, and the controller delegates synchronously to the
   Spring-managed `AllocationService` proxy.
2. The proxy opens the `@Transactional` service boundary. Spring Data JPA delegates
   repository work to Hibernate within the transaction-bound persistence context.
3. Hibernate reaches PostgreSQL through the Spring-managed HikariCP datasource and a
   transaction-associated JDBC connection. PostgreSQL executes the lookups and
   writes, may make the synchronous request thread wait, and enforces the unique
   allocation and idempotency constraints.
4. `saveAndFlush` sends the allocation insert before the service continues; it does
   not commit independently. A successful method return is followed by transaction
   commit. An unchecked failure triggers rollback, and the controlled rollback and
   concurrency tests verified the resulting PostgreSQL state for their scenarios.
5. While the persistence context is active, the service follows DTO and entity
   references and constructs `AllocationResponse`. After transaction completion the
   request persistence context closes and its entities become detached; the
   controller returns the DTO for JSON serialization rather than exposing entities.
6. Stack frames and infrastructure retain Java references only according to their
   lifetimes. Objects become eligible for collection only when they are no longer
   reachable; neither detachment nor request completion proves immediate reclamation.
7. JDK management interfaces supplied bounded memory snapshots and collector
   counters. JFR supplied sampled stacks, allocation samples, and duration-bearing
   wait and socket events across a separate four-request run. Neither supplies a
   complete per-request or per-query trace.
8. The performance assessment combines those categories. It identifies synchronous
   blocking as an observed execution characteristic, but no current bottleneck,
   endpoint statement count, representative query timing or plan, allocation-rate
   problem, pool-capacity limit, or optimization need has been established.

Use the specialized documents above for exact commands, test arrangements, SQL
shapes, event counts, stack frames, observed values, and complete limitations.

## Request entry and responsibilities

The request body is represented by `AllocationRequest` and contains an `idempotencyKey`.
Spring MVC binds the JSON body and applies `@Valid` before the controller method body
runs. `AllocationRequest.idempotencyKey` is annotated with `@NotBlank`, so a missing,
blank, or whitespace-only value produces a validation failure. `GlobalExceptionHandler`
maps `MethodArgumentNotValidException` to the project's `400 Bad Request` `ApiError`
response. The `gameCode` path variable has no separate validation annotation.

After validation, `AllocationController.allocate` passes the path variable and request
to `AllocationService.allocate`. When the service returns, the controller responds with
`201 Created` and the returned `AllocationResponse`. This is true for both a new
allocation and an idempotency replay.

The components divide responsibility as follows:

| Component | Current responsibility |
| --- | --- |
| Spring MVC and `AllocationRequest` | Bind and validate the request body. |
| `AllocationController` | Receive the request, delegate to the service, and return `201 Created`. |
| `AllocationService` | Run the allocation workflow and build the response. |
| Repositories | Load the idempotency record and game, select an available key, and persist records. |
| Database constraints | Enforce one allocation per game key and unique idempotency mappings. |

## Transaction boundary

`AllocationService.allocate` is the public service entry point and is annotated with
`@Transactional`. When it is called through the Spring-managed service proxy, the
transaction begins before the method body runs. It covers the repository operations
performed by `allocate`, `allocateNewGameKey`, and `saveAllocation`, as well as entity
association access while `toResponse` builds the response.

After the method returns successfully, Spring attempts to commit the transaction. If
an unhandled runtime exception leaves the method, the transaction is rolled back.

## Runtime paths

### Successful new allocation

1. `AllocationService.allocate` checks that the request object is non-null.
2. `IdempotencyRecordRepository.findByIdempotencyKey` looks for the request's
   idempotency key.
3. When no record exists, `GameRepository.findByCode` loads the `Game` for
   `gameCode`.
4. `GameKeyRepository.findAvailableByGame` selects keys for that game which have no
   matching `Allocation`. The query orders by `GameKey.id`, and the service supplies
   `PageRequest.of(0, 1)`, so the first available key is used.
5. `AllocationRepository.saveAndFlush` persists an `Allocation` for the selected key.
   `Allocation.prePersist` supplies `allocatedAt` when needed.
6. `IdempotencyRecordRepository.save` persists an `IdempotencyRecord` that stores the
   request's idempotency key and a one-to-one reference to the saved allocation.
7. `toResponse` reads the allocation, its game key, and the key's game to create an
   `AllocationResponse(gameCode, keyCode, allocatedAt)`.
8. The controller returns that response with `201 Created`.

`saveAndFlush` is important at step 5 because it sends the allocation insert to the
database while execution is still inside `saveAllocation`. If another request has
already allocated the selected key, the database uniqueness violation is therefore
raised inside the method's `try` block and can be translated to the service's
`IllegalStateException`.

### Successful idempotency replay

When `findByIdempotencyKey` returns an existing record, the service follows its
`allocation` relationship and calls `toResponse` for that original allocation. It does
not look up the requested game, select another key, save another allocation, or save
another idempotency record.

The lookup is currently by idempotency key alone. On a replay, the response's
`gameCode`, `keyCode`, and `allocatedAt` come from the allocation already linked to
that key; the request path's `gameCode` is not used for an additional match. The
controller still returns `201 Created`.

### Missing game

For a new idempotency key, an empty result from `GameRepository.findByCode` causes:

`IllegalArgumentException("Game not found: " + gameCode)`

No key or allocation persistence follows. There is no project-specific exception
handler for this exception, so it propagates to Spring's normal error handling.

### No available game key

If the game exists but `findAvailableByGame` returns no rows, the service causes:

`IllegalStateException("No available game keys for game: " + gameCode)`

No allocation or idempotency record is saved. There is no project-specific exception
handler for this exception, so it propagates to Spring's normal error handling.

### Duplicate-allocation race or database constraint failure

Availability is checked before the allocation insert. If two requests select the same
key before either insert is visible to the other, the database remains the final
protection: `Allocation.gameKey` maps to the unique, non-null
`allocations.game_key_id` column. Only one allocation can reference a game key.

When `saveAndFlush` raises `DataIntegrityViolationException`, `saveAllocation` wraps it
as:

`IllegalStateException("Selected game key is no longer available for game: " + gameCode, cause)`

The idempotency record is not saved after this failure. The exception is not mapped by
a project-specific handler and therefore propagates to Spring's normal error handling.

`IdempotencyRecord` has separate unique constraints on `idempotency_key` and
`allocation_id`. These constraints preserve a unique key-to-allocation mapping. The
service uses `save`, not `saveAndFlush`, for this record and does not translate a
constraint failure from that operation.

## Useful debugging checkpoints

Use the following breakpoints in order, stopping when the observed path diverges:

1. `GlobalExceptionHandler.handleValidationException` to inspect request validation
   failures and field errors.
2. `AllocationController.allocate` to confirm a valid `gameCode` and
   `AllocationRequest` reached the controller.
3. `AllocationService.allocate` at the idempotency lookup to distinguish replay from
   new allocation.
4. `AllocationService.allocateNewGameKey` at `findByCode` to inspect the game lookup.
5. `AllocationService.allocateNewGameKey` at `findAvailableByGame` to inspect the
   selected page and returned key list.
6. `AllocationService.saveAllocation` immediately before and after `saveAndFlush`, and
   in its catch block, to isolate persistence or uniqueness failures.
7. `AllocationService.allocateNewGameKey` at the idempotency-record save to verify the
   key is linked to the just-saved allocation.
8. `AllocationService.toResponse` to verify the game code, key code, and allocation
   timestamp used in the response.

For a replay problem, also inspect the `IdempotencyRecord.allocation` association
returned by the repository. For a key-selection problem, inspect the return value of
`findAvailableByGame`; the JPQL excludes keys already referenced by an allocation and
orders the remaining keys by ID.

## Existing tests that isolate the flow

- `AllocationControllerTests`
  - verifies `201 Created` and the response fields for a valid request;
  - verifies blank-key validation and the `400 Bad Request` body;
  - verifies validation failure does not call the service.
- `AllocationServiceTests`
  - covers a successful new allocation and the page size of one;
  - covers returning the original allocation for an existing idempotency key;
  - covers a missing game and no available key;
  - covers translation of a duplicate-allocation `DataIntegrityViolationException`.
- `GameKeyRepositoryTests`
  - verifies allocated keys are excluded;
  - verifies limiting the ordered result to the first available key.
- `AllocationRepositoryTests`
  - verifies allocation persistence and lookup;
  - verifies the database rejects a second allocation for the same game key.
- `AllocationTransactionRollbackTests`
  - flushes an allocation, forces the later idempotency save to fail, and verifies
    through PostgreSQL-backed state that the transaction retained neither write.
- `AllocationConcurrencyIntegrationTests`
  - coordinates two Spring-managed transactions after both select the same key;
  - verifies the PostgreSQL uniqueness failure, service translation, and final
    allocation and idempotency state.
- `AllocationRequestThreadIntegrationTests`
  - sends one real HTTP request through embedded Tomcat and confirms that the same
    servlet worker remains on the synchronous path during a controlled PostgreSQL
    wait.
- `AllocationObjectLifetimeIntegrationTests`
  - follows direct Java references, managed entity state, response construction, and
    closure of the request-bound persistence context through one real HTTP request.
- `AllocationJvmMemoryGcIntegrationTests`
  - sends ten bounded real HTTP requests and records JDK management-interface memory,
    pool, and collector snapshots without claiming per-object reclamation.
- `AllocationJfrProfilingIntegrationTests`
  - records one bounded four-request sequence and verifies functional PostgreSQL state
    while collecting JFR samples and duration-bearing events.
- `IdempotencyRecordRepositoryTests`
  - verifies saving and finding the key-to-allocation record;
  - verifies duplicate idempotency keys and duplicate allocation references are
    rejected.
- `GameRepositoryTests`
  - verifies a game can be found by its code.

The controller and service tests are mock-based evidence. The repository tests and
rollback/concurrency tests are PostgreSQL-backed repository or integration evidence
when the configured datasource is available. The request-thread, object-lifetime,
memory, and JFR tests supply running-server evidence backed by PostgreSQL; only the
memory test supplies JDK management-interface evidence, and only the JFR test supplies
JFR-recorded evidence. See each specialized note for the exact run, result, and limit.
