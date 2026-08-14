# JVM Memory and Garbage-Collection Fundamentals

This note records one bounded, project-specific observation of JVM memory while ten
real allocation requests pass through the embedded servlet server. It is a functional
integration test with management-interface snapshots, not a load test, benchmark,
allocation-rate measurement, leak diagnosis, or performance investigation.

For the complete request flow and recommended documentation path, start with
[`ALLOCATION_RUNTIME_DEBUGGING.md`](ALLOCATION_RUNTIME_DEBUGGING.md).

The reference flow, stack-frame references, managed and detached entities, strong
reachability, and the distinction between garbage-collection eligibility and actual
reclamation are established in
[`ALLOCATION_OBJECT_LIFETIME.md`](ALLOCATION_OBJECT_LIFETIME.md). They are linked
rather than repeated here.

For a bounded Java Flight Recorder view of sampled execution, waits, socket I/O,
sampled allocation, and GC-event presence or absence during four real requests, see
[`ALLOCATION_JFR_PROFILING.md`](ALLOCATION_JFR_PROFILING.md).

## Evidence boundaries

- **Static source inspection** covers the Java version, dependencies, allocation
  controller and service, DTOs, entities, repositories, application configuration,
  existing integration tests, and runtime documentation.
- **Running-server evidence** comes from the PostgreSQL-backed
  `AllocationJvmMemoryGcIntegrationTests`. It uses a random-port embedded server, ten
  sequential real HTTP requests, JDBC database-product metadata, and final database
  queries.
- **JDK management-interface evidence** consists of three point-in-time readings from
  `MemoryMXBean`, `MemoryPoolMXBean`, and `GarbageCollectorMXBean` during that focused
  run.
- **Framework-supported interpretation** explains the likely lifetimes of Spring MVC,
  Jackson, Spring transaction, Hibernate persistence-context, HikariCP, and Spring
  singleton objects based on their documented roles and the inspected request path.
- **Not directly observed** are per-object allocation rates, the instant an object
  became unreachable, reclamation of any particular object, retained-object graphs,
  a memory leak, a memory bottleneck, or a need for optimization.

## Controlled request sequence

The test starts Spring Boot with `RANDOM_PORT`, preserves the configured datasource,
and uses the schema initialized by tracked Flyway migrations. It verifies through
JDBC metadata that the database product is PostgreSQL, creates one controlled `Game`
with ten available `GameKey` rows, and takes its first memory checkpoint after those
fixtures and the application are ready.

Java's HTTP client then sends ten sequential `POST` requests to
`/api/games/{gameCode}/allocations`. Each request has a distinct idempotency key, a
five-second connection timeout, and a 15-second request timeout; the test has a
60-second timeout. Every response is asserted to be `201 Created` and deserialized as
a client-side `AllocationResponse`. The responses are held in an `ArrayList` whose
initial capacity and resulting asserted size are ten; the fixed loop is the bound on
retention.

The second checkpoint occurs while that list still holds ten strong references. After
the database assertions, `clear()` removes those ten element references and the empty
list is asserted before the third checkpoint. Clearing the list does not prove that
the response objects immediately became unreachable from every source, that a
collector ran, or that memory was reclaimed.

The final PostgreSQL state was exactly ten allocations and ten idempotency records.
Controlled-data joins found all ten allocations under the test game and all ten
matching prefixed idempotency records; individual repository checks found every exact
idempotency key.

## Heap, non-heap, and allocation pressure

Heap memory contains ordinary Java objects. In this request path, short-lived examples
include request JSON strings and buffers, server-side `AllocationRequest` DTOs,
client- and server-side `AllocationResponse` DTOs, Jackson serialization objects,
HTTP request/response objects, transaction-support objects, and Hibernate
persistence-context objects and entity references used during each transaction.
Creating these and other temporary objects contributes allocation pressure: it creates
work for heap allocation and eventual collection. This test does not measure an
allocation rate or attribute a byte delta to any one type.

Non-heap memory includes areas used for loaded class metadata and compiled code. Some
ordinary heap objects also live much longer than one request, including Spring
singleton controllers, services, repositories, application infrastructure, the
`EntityManagerFactory`, embedded-server infrastructure, and HikariCP datasource and
connection-pool infrastructure. Loaded classes, class metadata, and compiled methods
also remain useful across requests. "Longer-lived" is a lifecycle description, not
proof that an object is permanent.

All values below are bytes from one Java 17.0.18 process and are run-specific
point-in-time observations. They are not test expectations. A negative management
value is reported as **undefined**, not as a measured negative size.

| Checkpoint | Heap used | Heap committed | Heap max | Non-heap used | Non-heap committed | Non-heap max |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Fixtures ready, before requests | 42,683,712 | 92,274,688 | 4,253,024,256 | 114,694,776 | 116,850,688 | undefined |
| Ten responses retained | 63,655,232 | 92,274,688 | 4,253,024,256 | 122,253,784 | 124,518,400 | undefined |
| Retained list cleared | 43,431,664 | 92,274,688 | 4,253,024,256 | 122,863,256 | 125,108,224 | undefined |

Heap used was higher at the retained-response checkpoint and lower after the list was
cleared. Those directions are not attributed to the ten DTOs: the process was also
allocating, compiling, loading classes, and collecting independently of those ten
references. Another run may show different directions, so neither the presence of ten
retained objects nor `clear()` supports a deterministic whole-heap claim.

## Memory-pool observations

The focused run exposed these valid pools. Types and used-byte readings are reported
at the same three checkpoints; pool maximums that the JVM did not define are shown as
undefined.

| Memory pool | Type | Used before | Used retained | Used after clear | Maximum |
| --- | --- | ---: | ---: | ---: | ---: |
| CodeHeap 'non-nmethods' | Non-heap | 1,389,056 | 1,408,512 | 1,408,512 | 5,898,240 |
| Metaspace | Non-heap | 84,797,552 | 90,379,736 | 90,785,224 | undefined |
| CodeHeap 'profiled nmethods' | Non-heap | 11,602,944 | 12,377,856 | 12,485,760 | 122,880,000 |
| Compressed Class Space | Non-heap | 12,235,056 | 13,001,472 | 13,050,064 | 1,073,741,824 |
| G1 Eden Space | Heap | 4,194,304 | 25,165,824 | 2,097,152 | undefined |
| G1 Old Gen | Heap | 32,573,952 | 32,573,952 | 35,195,392 | 4,253,024,256 |
| G1 Survivor Space | Heap | 5,915,456 | 5,915,456 | 6,139,120 | undefined |
| CodeHeap 'non-profiled nmethods' | Non-heap | 4,677,504 | 5,086,208 | 5,133,696 | 122,880,000 |

The automated test does not depend on any pool name, pool count, exact value, maximum,
or difference between checkpoints. It asserts only that pool metadata is inspectable
and that a reported `MemoryUsage` is internally valid where available.

## Garbage-collector observations

Collector counts and times are cumulative process-level values, not measurements
limited to the ten requests. This run exposed the following names and values:

| Collector | Before count / time (ms) | Retained count / time (ms) | After-clear count / time (ms) |
| --- | ---: | ---: | ---: |
| G1 Young Generation | 16 / 141 | 16 / 141 | 17 / 149 |
| G1 Old Generation | 0 / 0 | 0 / 0 | 0 / 0 |

The observation happened to include changes to the young-collector counters. The test
does not require a collection, a counter change, or a particular collector. A count or
time of `-1` is supported by the management API when a value is unavailable; the test
accepts and renders that value as undefined. It does not call `System.gc()`, create
artificial heap pressure, select or tune a collector, or enable GC logging.

## Retention and leak limits

The response list intentionally retains exactly ten client-side DTOs so their strong
reachability is unambiguous at the middle checkpoint. This bounded, deliberate
retention is not automatically a leak. Clearing it removes those particular list
element references, after which normal reachability rules determine eligibility.
Eligibility still does not guarantee when or whether reclamation will occur.

Unintentional continuing retention would mean objects remain strongly reachable
longer than intended - for example, if request DTOs were continually added to an
unbounded singleton collection or listener registry and never removed. Demonstrating
a memory leak would require stronger evidence such as sustained growth across
comparable workloads and retained-object analysis that identifies an unintended path
from a GC root. This bounded test supplies neither and makes no leak or performance
claim.

## Reproduction and actual results

After loading the authorized local datasource environment in the same PowerShell
process, the focused Maven Wrapper command was:

```powershell
.\mvnw.cmd "-Dtest=AllocationJvmMemoryGcIntegrationTests" test
```

Actual focused result: 1 test, 0 failures, 0 errors, 0 skipped, and `BUILD SUCCESS`.
JDBC metadata identified PostgreSQL 16.14. Maven and the running application reported
Java 17.0.18.

The complete-suite command is:

```powershell
.\mvnw.cmd test
```

Actual complete-suite result: 39 tests, 0 failures, 0 errors, 0 skipped, and
`BUILD SUCCESS`.

These results are PostgreSQL-backed running-server evidence plus JDK
management-interface evidence from one local process. They do not generalize the
numeric readings to another run, machine, collector, JVM configuration, or production
workload.
