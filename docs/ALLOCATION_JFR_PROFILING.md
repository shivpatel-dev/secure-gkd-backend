# Bounded Allocation Profiling with Java Flight Recorder

This note records one small, fixed Java Flight Recorder (JFR) observation of four
sequential allocation requests. It is a functional profiling observation, not a load
test, stress test, benchmark, production trace, or basis for throughput, capacity,
percentile-latency, scalability, or tuning claims.

For the complete request flow and recommended documentation path, start with
[`ALLOCATION_RUNTIME_DEBUGGING.md`](ALLOCATION_RUNTIME_DEBUGGING.md).

The synchronous request-thread and PostgreSQL-blocking model is established in
[`ALLOCATION_REQUEST_THREADS.md`](ALLOCATION_REQUEST_THREADS.md), including the
distinction between a Tomcat worker, a Hikari connection, and a PostgreSQL session.
The Java reference and persistence-context lifetimes are described in
[`ALLOCATION_OBJECT_LIFETIME.md`](ALLOCATION_OBJECT_LIFETIME.md). Heap, non-heap,
allocation pressure, collector counters, and reclamation limits are described in
[`JVM_MEMORY_AND_GC.md`](JVM_MEMORY_AND_GC.md). Those investigations are linked rather
than repeated here.

## Evidence boundaries

- **Static source inspection** covers Java 17, the existing dependencies, allocation
  controller and service, repositories, datasource configuration, integration tests,
  and runtime documentation.
- **Running-server evidence** comes from the PostgreSQL-backed
  `AllocationJfrProfilingIntegrationTests`: random-port embedded Tomcat, JDBC metadata,
  four real HTTP responses, and final SQL-backed state assertions.
- **JFR-recorded evidence** consists of the event metadata, events, stack
  samples, duration-bearing events, and thread associations parsed from one bounded
  recording with the standard Java 17 consumer API.
- **Client-side elapsed-time observations** use `System.nanoTime()` around each
  synchronous HTTP call and around the four-request sequence.
- **Framework-supported interpretation** identifies familiar Spring, Hibernate,
  HikariCP, PostgreSQL JDBC, Tomcat, and Java HTTP-client frames and explains what the
  corresponding JFR event types measure.
- **Not established** are per-query latency, a complete trace of every operation, a
  production slow path, a bottleneck, an optimization need, or behavior on another
  run, process, machine, JVM configuration, or workload.

## Controlled sequence and recording boundaries

The test starts Spring Boot with `RANDOM_PORT`, retains the configured datasource,
and limits schema creation and removal to its test context. JDBC metadata must report
`PostgreSQL` before profiling continues. The test creates one controlled `Game` with
exactly four available `GameKey` rows. An unprofiled `GET /api/health` initializes the
dispatcher before the allocation recording begins.

After the application, dispatcher, datasource, and fixtures are ready, the test
constructs the four HTTP requests and JFR recording. It starts JFR immediately before
the fixed loop, sends four sequential real `POST` requests to
`/api/games/{gameCode}/allocations`, and stops JFR in a `finally` block immediately
after the loop returns or fails. Each request uses a distinct idempotency key and a
15-second HTTP timeout; the client has a five-second connection timeout and the test
has a 90-second timeout.

All four responses were `201 Created`. The response key codes matched the four
fixtures. Final PostgreSQL-backed checks found exactly four allocations and four
idempotency records, all under the controlled game and all with the expected distinct
keys.

The stopped recording is dumped only to JUnit's temporary directory, parsed, closed,
and explicitly deleted in `finally`. The test then verifies that the `.jfr` path no
longer exists. No recording, extracted event dump, stack trace, or profiler report is
tracked.

## JFR configuration and APIs

The test uses only the Java 17 `jdk.jfr` and `jdk.jfr.consumer` APIs:

- `Configuration.getConfiguration("profile")` and `new Recording(configuration)`;
- `Recording.start()`, `stop()`, `dump(Path)`, metadata accessors, and `close()`;
- `RecordingFile.readEventTypes()`, `hasMoreEvents()`, and `readEvent()`;
- `RecordedEvent`, `RecordedStackTrace`, `RecordedFrame`, and `RecordedThread` for
  summaries.

The base configuration was the active JDK's standard `profile` configuration, label
`Profiling`; no profiling dependency or permanent application configuration was
added. The recording name was `bounded-allocation-requests-issue-57`, `toDisk` was
true, the automatic duration cap was 75 seconds, and the maximum size was 33,554,432
bytes (32 MiB).

These are the relevant effective event settings. Values not listed as overrides came
from the Java 17 `profile` configuration.

| Events | Effective setting |
| --- | --- |
| `jdk.ExecutionSample`, `jdk.NativeMethodSample` | enabled, 10 ms period, stack traces |
| `jdk.ThreadPark`, `jdk.JavaMonitorEnter`, `jdk.JavaMonitorWait` | enabled, 10 ms threshold, stack traces |
| `jdk.SocketRead`, `jdk.SocketWrite` | enabled, 0 ns threshold, stack traces |
| `jdk.FileRead`, `jdk.FileWrite`, `jdk.FileForce` | enabled, 10 ms threshold, stack traces |
| `jdk.CPULoad` | enabled, 20 ms period |
| `jdk.ThreadCPULoad` | enabled, profile-default 10 s period |
| `jdk.ObjectAllocationSample` | enabled, `100/s` throttle, stack traces |
| `jdk.ObjectAllocationInNewTLAB`, `jdk.ObjectAllocationOutsideTLAB` | disabled by `profile`; stack-trace setting present |
| `jdk.GarbageCollection` | enabled, 0 ms threshold |
| `jdk.GCPhasePause`, levels 1 and 2 | enabled, 0 ms threshold |
| `jdk.GCPhasePause`, levels 3 and 4 | disabled by `profile` |
| `jdk.ThreadStart`, `jdk.ThreadEnd` | enabled; thread-start stack traces enabled |
| `jdk.JavaThreadStatistics` | enabled, 1 s period |
| `jdk.JVMInformation`, `jdk.OSInformation`, `jdk.CPUInformation`, `jdk.VirtualizationInformation` | enabled at chunk start |
| `jdk.PhysicalMemory` | enabled for every chunk |

## Observed recording and client timings

The successful focused run reported Eclipse Adoptium Java 17.0.18, OpenJDK 64-Bit
Server VM, JVM process 30256, and PostgreSQL 16.14. JFR assigned recording ID 1. The
recording duration was 1,122,328,400 ns and the temporary file size was 556,041 bytes.
Both are run-specific observations; the automated test asserts only positive
structural values, not these exact values.

Client-observed elapsed values from the same run were:

| Request | Status | Elapsed |
| ---: | ---: | ---: |
| 1 | 201 | 771.701 ms |
| 2 | 201 | 61.366 ms |
| 3 | 201 | 55.376 ms |
| 4 | 201 | 30.546 ms |
| Complete fixed sequence | four successful requests | 920.360 ms |

These values include the client-observed wall-clock wait for each complete HTTP
exchange. They are not latency limits, benchmark results, or expectations for another
run. In particular, the isolated first-request difference is not by itself a
reproducible slow path.

## Observed event categories

The consumer read non-empty event-type metadata and events successfully. The table
reports actual counts from this run. Zero means that the enabled relevant event was
not observed in this short recording; it does not prove that the underlying activity
never occurred.

| Category | Observed event names and counts | Category total |
| --- | --- | ---: |
| Sampled execution | `jdk.ExecutionSample` 10; `jdk.NativeMethodSample` 63 | 73 |
| Java thread lifecycle/statistics | `jdk.ThreadStart` 1; `jdk.ThreadEnd` 0; `jdk.JavaThreadStatistics` 0 | 1 |
| Blocking or waiting | `jdk.ThreadPark` 21; `jdk.JavaMonitorEnter` 0; `jdk.JavaMonitorWait` 0 | 21 |
| Socket I/O | `jdk.SocketRead` 60; `jdk.SocketWrite` 45 | 105 |
| File I/O | `jdk.FileRead` 0; `jdk.FileWrite` 0; `jdk.FileForce` 0 | 0 |
| CPU load | `jdk.CPULoad` 0; `jdk.ThreadCPULoad` 0 | 0 |
| Sampled allocation | `jdk.ObjectAllocationSample` 96; exact TLAB allocation events disabled | 96 |
| Garbage collection | `jdk.GarbageCollection` 1 | 1 |
| GC pauses | `jdk.GCPhasePause` 1; level 1: 4; level 2: 2 | 7 |
| JVM/OS metadata | `jdk.JVMInformation` 1; `jdk.OSInformation` 1; `jdk.CPUInformation` 1; `jdk.PhysicalMemory` 2; `jdk.VirtualizationInformation` 1 | 6 |

The recording also contained configuration and runtime metadata such as
`jdk.ActiveRecording` 1, `jdk.ActiveSetting` 337, and class-loader, compiler, flag,
module, native-library, safepoint, process, and per-thread allocation-statistics
events. Those process-wide events provide recording context; they are not attributed
to one allocation request.

## Useful observed frames and thread roles

Execution samples actually included allocation-service project frames, Spring
transaction and Spring Data JPA frames, Hibernate query execution, HikariCP and
PostgreSQL JDBC result access, and embedded Tomcat filter-chain frames. Native-method
samples included the Java HTTP-client selector and Tomcat acceptor/poller work.

Socket events supplied duration-bearing I/O evidence with observed stacks through:

- the test's Java HTTP client;
- Tomcat request/response processing;
- the controller and transactional allocation service;
- Spring Data JPA and Hibernate;
- Hikari proxy statements and connection commit;
- PostgreSQL JDBC query execution, receive, flush, and commit paths.

The observed `ThreadPark` stacks included the test thread synchronously waiting in
Java HTTP-client `send`. No qualifying monitor-contention or monitor-wait event was
observed. A sampled frame or I/O event shows that code on that stack when that event
was captured; it is not a complete method trace and does not assign the event's full
duration to every frame on the stack.

For events with a thread association, the test categorized 438 event associations as
test-client activity (`main` and Java HTTP-client threads), 167 as servlet-request
activity (four `http-nio-...-exec-*` workers), 373 as JVM service activity (GC,
compiler, JFR, and VM threads), and 13 as unrelated background activity (including
Tomcat poller/utility threads and the test runner's stream flusher). These are
run-specific event-association counts, not thread utilization, request counts, or
CPU-time measurements. Events
without a thread association are omitted from this role summary.

## Interpreting time and samples

- **Wall-clock request duration** is the client's monotonic time from starting the
  synchronous send until the response body is received. It includes CPU execution,
  scheduling, waiting, network stack work, server work, database work, and other
  elapsed time visible to that client call.
- **Sampled CPU execution** is a periodic observation of a runnable stack. Counts of
  `ExecutionSample` or `NativeMethodSample` are neither elapsed milliseconds nor a
  record of every method invocation.
- **A duration-bearing JFR event**, such as a socket read or thread park, measures that
  event instance under its JFR definition and threshold. It does not measure the whole
  HTTP request merely because request frames appear in its stack.
- **Blocking or waiting time** requires suitable duration-bearing wait, park,
  contention, socket, or other events. A Java thread state or sampled native frame
  alone is not an elapsed blocking measurement.
- **Cumulative JVM metrics**, such as management-bean collector counts discussed in
  the memory note or per-thread allocation statistics present here, can include work
  before the recording or across unrelated threads. They are not automatically
  bounded-request deltas.
- **An event sample** deliberately represents selected occurrences. The 96 allocation
  samples do not mean that only 96 objects were allocated, and the absence of a file
  or CPU-load event does not establish absence of file activity or CPU use outside the
  event's settings and recording window. The one observed GC and its pause-phase
  events do not attribute that process-wide collection to the four requests.

## Bottleneck assessment and limitations

This run confirms that the bounded real request path produced inspectable CPU samples,
waiting events, socket I/O, allocation samples, and framework/application stacks while
preserving the functional database result. It does not identify a reproducible slow
path or support a production-bottleneck or optimization claim. The first request's
higher client elapsed time was observed once; no repeated comparable recordings,
query-by-query timing, CPU attribution, or controlled causal experiment establishes
why it differed.

JFR recorded the whole JVM process, so test-client work, servlet work, JVM service
threads, Tomcat/Hikari background work, test-runner work, JIT compilation, and other
runtime activity can share the same interval. Stack sampling can miss short work.
The socket threshold was deliberately zero for visibility, while other thresholds and
periods can omit shorter or less frequent activity. This evidence must not be
generalized to production or used as a benchmark.

## Reproduction and actual results

After loading the authorized datasource environment in the same PowerShell process
without printing it, the focused Maven Wrapper command was:

```powershell
.\mvnw.cmd "-Dtest=AllocationJfrProfilingIntegrationTests" test
```

Actual focused result: 1 test, 0 failures, 0 errors, 0 skipped, and `BUILD SUCCESS`.
The run supplied the PostgreSQL, HTTP, timing, recording, event, frame, and cleanup
evidence summarized above.

The complete-suite command is:

```powershell
.\mvnw.cmd test
```

Actual complete-suite result: 40 tests, 0 failures, 0 errors, 0 skipped, and
`BUILD SUCCESS`.
