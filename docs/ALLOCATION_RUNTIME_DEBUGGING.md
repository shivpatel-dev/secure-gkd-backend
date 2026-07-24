# Allocation Runtime and Debugging Notes

These notes describe the current implementation of:

`POST /api/games/{gameCode}/allocations`

They are intended for tracing the existing behavior, not for defining new API behavior.

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
- `IdempotencyRecordRepositoryTests`
  - verifies saving and finding the key-to-allocation record;
  - verifies duplicate idempotency keys and duplicate allocation references are
    rejected.
- `GameRepositoryTests`
  - verifies a game can be found by its code.

The service tests use repository mocks to isolate workflow decisions. The repository
tests are JPA slice tests and isolate persistence queries and database constraints.
