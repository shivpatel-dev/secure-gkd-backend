# Allocation Failure and Stack-Trace Debugging Notes

These notes are a companion to
[`ALLOCATION_RUNTIME_DEBUGGING.md`](ALLOCATION_RUNTIME_DEBUGGING.md). They focus on
diagnosing failures from the current implementation of:

`POST /api/games/{gameCode}/allocations`

They do not define new API behavior or repeat the complete allocation flow. Use the
main runtime document for the complete recommended documentation path.

## Evidence scope

The descriptions below preserve the evidence available when this document was
originally prepared: current source and existing test code were inspected, but no
test, local request, debugger session, or running application was used for that
original investigation. Therefore:

- **Static source inspection** describes behavior directly established by the
  implementation.
- **Inspected test coverage** identifies assertions present in the test suite; those
  tests were inspected, not run for the original documentation change.
- **Original runtime reproduction** is `None` for every failure below. No HTTP
  response, logged stack trace, Hibernate exception chain, or PostgreSQL error was
  captured while this document was prepared.

The project's later
[`ALLOCATION_CONCURRENCY.md`](ALLOCATION_CONCURRENCY.md) investigation adds separate
**PostgreSQL-backed integration evidence** for one duplicate-allocation collision. It
observed the preserved cause chain through the service, Spring persistence-exception
translation, Hibernate, the PostgreSQL driver, and PostgreSQL's unique constraint.
That later test called the Spring-managed service from two worker threads; it did not
reproduce the failure through HTTP, and it supplies no evidence for the other failure
paths below. No allocation failure path in this document is claimed as a
running-server HTTP reproduction.

## HTTP responses, Java exceptions, and logged stack traces

These are related views of a failure, but they are not interchangeable:

- An **HTTP error response** is what the client receives. It has a status and may have
  a serialized response body. It normally does not expose the complete server-side
  exception chain.
- A **Java exception** is the in-process object thrown while the request is handled. It
  has a type, message, stack trace, and possibly a cause.
- A **logged stack trace** is a text rendering of an exception at a particular logging
  point. Spring may add servlet, proxy, transaction, repository, Hibernate, and JDBC
  frames around the useful application frames. Whether a trace is logged, and how much
  is shown, depends on the runtime and logging configuration.

`GlobalExceptionHandler` maps `MethodArgumentNotValidException` to the project's
`400 Bad Request` `ApiError` with field errors. Its generic unexpected-failure boundary
maps allocation service `IllegalArgumentException` and `IllegalStateException`
failures to a safe `500 Internal Server Error` `ApiError` with empty `fieldErrors`.
The service exceptions and their cause chains remain available to server-side
diagnostics but are not serialized into the public response. This source-derived
contract does not change the original evidence limits above or prove what a deployed
runtime logged.

## Reading an exception chain

Start at the outer exception and then follow each `Caused by` section:

- The **outer exception** is the exception presented to the caller or logging point. It
  often describes the application-level meaning of the failure.
- The **cause chain** is the sequence reached by repeatedly calling `getCause()`.
- The **root cause** is the deepest available cause. It may identify a database or
  driver symptom, but it is not automatically the best place to change project code.
- The **first useful project frame** is the first relevant frame in a cause section
  whose class begins with `com.shiv.securegkd`. It shows where project code threw,
  translated, or allowed the failure to cross a boundary.

For the duplicate-allocation path, static source inspection guarantees this part of
the chain:

`IllegalStateException` -> `DataIntegrityViolationException`

The service creates the outer `IllegalStateException`; the caught
`DataIntegrityViolationException` is its cause. The original investigation did not
reproduce a deeper chain. The later controlled concurrency test separately observed
additional Hibernate `ConstraintViolationException` and PostgreSQL `PSQLException`
causes; see [`ALLOCATION_CONCURRENCY.md`](ALLOCATION_CONCURRENCY.md) for the exact
arrangement, database evidence, and limits.

## Identifying the layer where a failure began

| Signal | Likely starting layer | First place to inspect |
| --- | --- | --- |
| `MethodArgumentNotValidException`, field error for `idempotencyKey`, and the controller breakpoint is not reached | Spring MVC binding and Jakarta Bean Validation | `AllocationRequest.idempotencyKey`, `AllocationController.allocate`, and `GlobalExceptionHandler.handleValidationException` |
| `Game not found: ...` or `No available game keys for game: ...` | Allocation service decision | The matching `orElseThrow` in `AllocationService.allocateNewGameKey` |
| Failure crosses `allocationRepository.saveAndFlush(...)` | Spring Data repository/persistence boundary | The call and catch block in `AllocationService.saveAllocation` |
| Frames under `org.springframework.dao` or a `DataIntegrityViolationException` | Spring persistence-exception translation | The repository operation and the exception's cause |
| Frames under `org.hibernate` | Hibernate ORM | The first Hibernate cause below the repository exception |
| Frames under `org.postgresql` or a PostgreSQL SQL state/constraint message | PostgreSQL JDBC/database layer | The deepest driver cause, then work outward to the first project frame |

Do not infer a PostgreSQL root cause merely from the outer service exception. Confirm
it from an actually captured cause chain before treating it as observed database
behavior. The concurrency note provides that confirmation only for its controlled
duplicate-allocation scenario.

## Failure paths

### Missing, blank, or whitespace-only `idempotencyKey`

**What current source establishes**

`AllocationRequest.idempotencyKey` has
`@NotBlank(message = "idempotencyKey is required")`, and the controller request is
annotated with `@Valid`. A missing JSON property (which binds as `null`), an empty
string, or a whitespace-only string violates `@NotBlank`. Validation occurs before the
controller method body runs.

Spring raises `MethodArgumentNotValidException`, which the validation-specific method
in `GlobalExceptionHandler` maps to `400 Bad Request` with an `ApiError` containing:

- `error`: `Bad Request`
- `message`: `Validation failed`
- the request path
- `fieldErrors.idempotencyKey`: `idempotencyKey is required`

This describes a missing property in an otherwise readable JSON body. A missing or
malformed request body is a different binding failure and is not documented here.

**Useful breakpoints**

1. Break on `MethodArgumentNotValidException` if the debugger supports exception
   breakpoints.
2. Break at `GlobalExceptionHandler.handleValidationException` and inspect
   `exception.getBindingResult().getFieldErrors()`.
3. Place a breakpoint at `AllocationController.allocate`; it should not be reached for
   this validation failure.

**Existing tests**

- `AllocationControllerTests.blankIdempotencyKeyReturnsBadRequest` asserts the exact
  `400` response fields for an empty string.
- `AllocationControllerTests.validationFailureDoesNotCallAllocationService` asserts
  that an empty-string validation failure does not call the service.
- No inspected test separately submits a missing property or a whitespace-only value;
  those cases are established by the current `@NotBlank` constraint, not by separate
  test methods.

**Original runtime reproduction:** None. No later reproduction is linked for this
validation path.

### Missing `Game`

**What current source establishes**

For a new idempotency key, `GameRepository.findByCode(gameCode)` returning empty causes:

`IllegalArgumentException("Game not found: " + gameCode)`

The exception is created in `AllocationService.allocateNewGameKey`. It has no explicit
cause. Key selection and allocation persistence are not reached. The generic
unexpected-failure handler returns the common safe `500 Internal Server Error`
`ApiError`; it does not expose the exception message or introduce an allocation-domain
status mapping.

**Useful breakpoints**

1. Break at `gameRepository.findByCode(gameCode)` and inspect the returned `Optional`.
2. Break at the missing-game `orElseThrow` in
   `AllocationService.allocateNewGameKey`.
3. Use an exception breakpoint for `IllegalArgumentException`; then find the
   `AllocationService` project frame before inspecting surrounding Spring frames.

**Existing tests**

- `AllocationServiceTests.allocateFailsWhenGameDoesNotExist` uses repository mocks and
  asserts the exception type and `Game not found: UNKNOWN` message. It also asserts
  that the game-key and allocation repositories are not called and that no
  idempotency record is saved.
- The focused controller test covers a representative allocation service exception at
  the HTTP boundary and asserts the safe generic `500` contract. It does not reproduce
  this exact missing-Game service path.

**Original runtime reproduction:** None. No later reproduction is linked for this
missing-game path.

### No available `GameKey`

**What current source establishes**

When the game exists but
`GameKeyRepository.findAvailableByGame(game, PageRequest.of(0, 1))` returns no rows,
the service causes:

`IllegalStateException("No available game keys for game: " + gameCode)`

The exception is created in `AllocationService.allocateNewGameKey` and has no explicit
cause. Allocation and idempotency-record persistence are not reached. The generic
unexpected-failure handler returns the common safe `500 Internal Server Error`
`ApiError` without exposing the exception message.

**Useful breakpoints**

1. Break before and after `gameKeyRepository.findAvailableByGame(...)` and inspect the
   result list and `Pageable`.
2. Break at the no-key `orElseThrow` in
   `AllocationService.allocateNewGameKey`.
3. Use an exception breakpoint for `IllegalStateException` and identify the
   `AllocationService` project frame.

**Existing tests**

- `AllocationServiceTests.allocateFailsWhenNoGameKeyIsAvailable` uses repository mocks
  and asserts the exception type and message. It also asserts that no allocation or
  idempotency record is saved.
- `GameKeyRepositoryTests.findAvailableByGameExcludesAllocatedGameKeys` and
  `findAvailableByGameReturnsFirstAvailableGameKeyWhenLimitedToOne` cover the
  repository query behavior relevant to deciding whether a key is available.
- The focused controller test covers a representative allocation service exception at
  the HTTP boundary and asserts the safe generic `500` contract. It does not reproduce
  this exact no-available-key service path.

**Original runtime reproduction:** None. No later reproduction is linked for this
no-available-key path.

### Duplicate-allocation persistence failure

**What current source establishes**

`Allocation.gameKey` maps to a unique, non-null `allocations.game_key_id`. The service
calls `AllocationRepository.saveAndFlush`, so a persistence failure raised for that
insert occurs while execution is still inside `AllocationService.saveAllocation`.

That method catches `DataIntegrityViolationException` and throws:

`IllegalStateException("Selected game key is no longer available for game: " + gameCode, exception)`

The outer exception is therefore the service's `IllegalStateException`, and its direct
cause is the caught `DataIntegrityViolationException`. The catch translates a
persistence-layer failure into the allocation-specific message without discarding the
cause chain. The idempotency-record save follows this method and is not reached after
the translated failure.

The generic unexpected-failure handler maps the outer `IllegalStateException` to the
common safe `500 Internal Server Error` `ApiError`. Neither its message nor the
`DataIntegrityViolationException` cause is placed in the public response. The project
still does not define a dedicated allocation-domain HTTP status for this failure.

**Useful breakpoints**

1. Break immediately before `allocationRepository.saveAndFlush(...)` in
   `AllocationService.saveAllocation`.
2. Enable a caught exception breakpoint for `DataIntegrityViolationException` to stop
   before translation.
3. Break inside the `catch` block and compare the caught exception with the cause on
   the newly created `IllegalStateException`.
4. If a real trace contains deeper causes, inspect the Spring Data/Hibernate boundary,
   then the deepest PostgreSQL driver cause, and return to the first
   `AllocationService.saveAllocation` project frame.

**Existing tests**

- `AllocationServiceTests.allocateFailsWhenSelectedGameKeyIsAlreadyAllocatedByConcurrentRequest`
  makes the mocked repository throw `DataIntegrityViolationException` and asserts the
  translated `IllegalStateException`, its message, its cause type, and that no
  idempotency record is saved. It does not exercise Hibernate or PostgreSQL.
- `AllocationRepositoryTests.rejectsDuplicateAllocationForGameKey` is a JPA repository
  test configured not to replace the datasource. It asserts that saving a second
  allocation for one game key raises `DataIntegrityViolationException`. It does not
  exercise the service translation or HTTP layer.
- The later `AllocationConcurrencyIntegrationTests` execution captured and asserted
  the service, Spring, Hibernate, and PostgreSQL cause chain and the final database
  state for a controlled collision. It did not exercise the HTTP layer or assert
  logged output.
- The focused controller test covers a representative allocation service exception at
  the HTTP boundary and asserts that the generic `500` body omits a test-only internal
  message, cause detail, and exception class names. It does not reproduce a database
  collision.

**Original runtime reproduction:** None. **Later PostgreSQL-backed integration
evidence:** the controlled collision documented in
[`ALLOCATION_CONCURRENCY.md`](ALLOCATION_CONCURRENCY.md); no HTTP failure response or
logged stack trace was captured.

## Practical debugging order

For an unfamiliar allocation failure:

1. Record the HTTP status and body separately from any server exception.
2. Decide whether the controller method was reached. If not, inspect binding and
   validation first.
3. Read the outer exception type and message, then follow every cause to the deepest
   available cause.
4. For each cause, find the first relevant `com.shiv.securegkd` frame.
5. Use repository, Hibernate, and PostgreSQL frames to identify the crossed boundary,
   but make conclusions only from the cause chain actually present.
6. Compare the path with the specific existing test above; do not treat a mocked
   service test as proof of a PostgreSQL runtime result.
