# Allocation Object and Persistence-Context Lifetime

This note records one focused execution of
`POST /api/games/{gameCode}/allocations` through the real embedded servlet server. It
complements the full request flow in
[`ALLOCATION_RUNTIME_DEBUGGING.md`](ALLOCATION_RUNTIME_DEBUGGING.md), the SQL and
entity relationships in [`ALLOCATION_SQL_DEBUGGING.md`](ALLOCATION_SQL_DEBUGGING.md),
and the observed request stack in
[`ALLOCATION_REQUEST_THREADS.md`](ALLOCATION_REQUEST_THREADS.md). Those explanations
are linked rather than repeated here.

## Evidence boundaries

- **Static source inspection** covers the controller, service, DTO records, entities,
  repositories, configuration, and focused test.
- **PostgreSQL-backed running-server evidence** comes from
  `AllocationObjectLifetimeIntegrationTests`. It starts Spring Boot with a random-port
  embedded Tomcat server, retains the configured datasource, verifies JDBC metadata,
  and sends one real HTTP request.
- **Framework-supported interpretation** explains stack-frame local references, JPA
  detachment after a persistence context closes, and JSON object reconstruction.
- **Not directly observed** are the instant when an object becomes unreachable, its
  eligibility for garbage collection, and any garbage collector reclamation.

This is one bounded integration-test observation, not heap analysis, profiling, or a
garbage-collection test.

## Controlled request and reference flow

The test creates the minimum database state: one `Game` and one `GameKey`. Java's
HTTP client sends raw JSON, not a client-side `AllocationRequest`, with a five-second
connection timeout and a 15-second request timeout. The test itself has a 30-second
timeout. Spring MVC deserializes that JSON into the server-side `AllocationRequest`.

Test-only Spring spies observe the concrete controller and service while calling their
real implementations. Repository spies are observation points around separate real
Spring Data repository delegates created by `JpaRepositoryFactory`; the delegates use
Spring's transaction-aware `EntityManager` proxy. This avoids attempting to call an
abstract repository-interface method as a Mockito "real method" and preserves the
application's actual JPA behavior.

Direct `isSameAs` reference comparisons established this server-side chain:

1. The controller and service received the same `AllocationRequest` object.
2. The `Game` returned from `findByCode` was the same object supplied to
   `findAvailableByGame`.
3. The selected `GameKey.getGame()` was that same `Game` object.
4. The new `Allocation.getGameKey()` was the selected `GameKey` object.
5. `saveAndFlush` returned the same new `Allocation` object supplied to it.
6. The new `IdempotencyRecord.getAllocation()` was that saved `Allocation` object.
7. The service's `AllocationResponse` was the same object placed in the controller's
   `ResponseEntity` body.

The HTTP response remained `201 Created`. Deserializing its JSON in the test created a
separate client-side `AllocationResponse`; it was directly verified not to be the
server response object. HTTP serialization transfers a data representation, not the
server's Java reference.

No identity hash was needed for these conclusions. An identity hash, if printed during
another run, would be only a run-specific diagnostic token: it is neither a physical
memory address nor a guaranteed-unique object identifier.

## Transaction and persistence context

The passing assertions observed an active Spring transaction at service entry, game
lookup, allocation persistence, and idempotency-record persistence. At service entry,
the test obtained the actual transaction-bound JPA `EntityManager` with
`EntityManagerFactoryUtils.getTransactionalEntityManager` and unwrapped its Hibernate
`Session`. Direct comparisons showed the same `EntityManager` at the game lookup,
allocation save, and idempotency save boundaries.

Managed-state observations inside that active context were:

| Object | Observation |
| --- | --- |
| Repository-loaded `Game` | Managed after lookup and at available-key lookup |
| Repository-selected `GameKey` | Managed after available-key lookup |
| New `Allocation` before `saveAndFlush` | Not managed |
| Same `Allocation` after `saveAndFlush` | Managed |
| New `IdempotencyRecord` before `save` | Not managed |
| Returned `IdempotencyRecord` after `save` | Managed |

After the HTTP call completed, both the captured request `EntityManager` and unwrapped
Hibernate `Session` reported `isOpen() == false`. Because tracked configuration sets
`spring.jpa.open-in-view=false`, the request does not retain that context for response
serialization. The observed entities therefore cease to be managed by that completed
request's closed context. "Detached" describes that persistence relationship; it does
not mean that the Java objects were destroyed.

The test's trace deliberately retained strong references to the DTOs and entities so
it could assert identity after the request. Those objects consequently remained
reachable from the test even after becoming detached. Once all relevant strong
references disappear, an object may become eligible for collection. Eligibility does
not guarantee when, or whether, a garbage collector will reclaim it. This test does
not call `System.gc()`, create heap pressure, use weak-reference clearing as an
assertion, or claim that reclamation was observed.

## Stack frames and referenced objects

The controller and service invocations contribute stack frames while their methods
execute. Parameters and local variables in those frames contain references; the
`AllocationRequest`, `Game`, `GameKey`, `Allocation`, `IdempotencyRecord`, Hibernate
objects or proxies, and `AllocationResponse` are referenced Java objects. Passing a
reference into the next method creates another reference to the same object; it does
not copy that object. The direct comparisons above demonstrate this at adjacent
application boundaries.

The previously captured request-stack evidence is documented in
[`ALLOCATION_REQUEST_THREADS.md`](ALLOCATION_REQUEST_THREADS.md). A stack snapshot
shows active frames, not all heap objects, and a local reference is not the referenced
object itself. HTTP deserialization and serialization are separate boundaries where
new server- or client-side objects may be constructed from JSON.

## Database outcome and reproduction

JDBC metadata identified PostgreSQL, and Hibernate reported PostgreSQL 16.14. After
the successful response, repository assertions found exactly one allocation and one
idempotency record. The record used the requested idempotency key and referenced the
same allocation row identified during the request.

After loading the local datasource environment into the current PowerShell process
without printing it, the focused Maven Wrapper command was:

```powershell
.\mvnw.cmd "-Dtest=AllocationObjectLifetimeIntegrationTests" test
```

Actual focused result: 1 test, 0 failures, 0 errors, 0 skipped, and `BUILD SUCCESS`.

The complete-suite command is:

```powershell
.\mvnw.cmd test
```

Actual complete-suite result: 38 tests, 0 failures, 0 errors, 0 skipped, and
`BUILD SUCCESS`.

The focused test observes one successful new-allocation path. It does not prove object
reclamation, describe physical memory addresses, inspect a heap, measure allocations,
or replace the existing unit, repository, rollback, concurrency, datasource, SQL, and
request-thread evidence.
