# Allocation Request-Thread Evidence

This note records one controlled PostgreSQL-backed execution of:

`POST /api/games/{gameCode}/allocations`

through the real embedded servlet server. It complements the complete
[`ALLOCATION_RUNTIME_DEBUGGING.md`](ALLOCATION_RUNTIME_DEBUGGING.md) request flow and
the datasource details in
[`ALLOCATION_DATASOURCE_LIFECYCLE.md`](ALLOCATION_DATASOURCE_LIFECYCLE.md). It does
not define new allocation behavior or provide load-test or capacity evidence. For the
Java objects referenced by these request frames and their transaction-bound
persistence-context lifetime, see
[`ALLOCATION_OBJECT_LIFETIME.md`](ALLOCATION_OBJECT_LIFETIME.md).
For sampled execution, duration-bearing wait and socket events, allocation samples,
and client-observed timing from four bounded requests, see
[`ALLOCATION_JFR_PROFILING.md`](ALLOCATION_JFR_PROFILING.md).

## Evidence categories

- **Static source inspection** covers `application.yaml`, the allocation controller
  and service, repository interfaces, and the focused test.
- **Resolved dependency evidence** identifies the embedded servlet-container
  artifacts on this project's runtime classpath.
- **PostgreSQL-backed runtime evidence** comes from
  `AllocationRequestThreadIntegrationTests`, which starts the embedded server and
  sends a real HTTP request.
- **Framework-supported inference** is limited to interpreting unconfigured runtime
  limits as framework-supplied values and explaining the synchronous execution model.

The observation is one bounded test request, not a benchmark or production trace.

## Embedded server and effective limits

The resolved dependency command was:

```powershell
.\mvnw.cmd "-Dincludes=org.springframework.boot:spring-boot-starter-web,org.springframework.boot:spring-boot-starter-tomcat,org.apache.tomcat.embed:tomcat-embed-core" dependency:tree
```

It completed with `BUILD SUCCESS` and resolved:

```text
spring-boot-starter-web 3.5.14
  -> spring-boot-starter-tomcat 3.5.14
     -> tomcat-embed-core 10.1.54
```

The focused runtime then reported Apache Tomcat 10.1.54 and observed these active
types and connector values:

| Runtime observation | Value |
| --- | --- |
| Web server | `org.springframework.boot.web.embedded.tomcat.TomcatWebServer` |
| HTTP protocol | `org.apache.coyote.http11.Http11NioProtocol` |
| Worker executor | `org.apache.tomcat.util.threads.ThreadPoolExecutor` |
| Maximum worker threads | `200` |
| Accept count | `100` |
| Maximum connections | `8192` |

**Static source inspection:** tracked configuration contains no `server.*` or
`server.tomcat.*` setting. These are therefore runtime-observed, framework-supplied
effective values for this test context, not explicit project settings or recommended
tuning values. A deployment can change them through configuration or a different
server implementation.

## Controlled request

The focused test uses `@SpringBootTest(webEnvironment = RANDOM_PORT)` and the
configured datasource with test-only `ddl-auto=create-drop`. It creates one game and
one key, then Java's `HttpClient` sends a JSON `POST` to the random-port allocation
endpoint. Standalone `MockMvc` is not involved.

A test-only servlet filter records request entry. Spring spies record controller,
service, and `GameKeyRepository.findAvailableByGame` boundaries while delegating to
the real behavior. The repository spy delegates the real selection query through the
thread-bound JPA `EntityManager`; after the query returns, it performs the controlled
database wait without replacing the allocation workflow.

The test holds a PostgreSQL session-level advisory lock on a separate connection. In
the request transaction, the request thread records `pg_backend_pid()` and then calls
`pg_advisory_xact_lock` for the same key. The observing test thread polls
`pg_stat_activity` for that PID until PostgreSQL reports `wait_event_type = 'Lock'`
and `wait_event = 'advisory'`. Only after that confirmation does it assert that the
HTTP future is incomplete and capture the focused Java stack.

Lock acquisition, observation, the database statement, HTTP request, cleanup, and
the test all have bounded timeouts. In particular, synchronization waits are 10
seconds, the blocked database statement is bounded at 25 seconds, the HTTP request at
20 seconds, and the test at 40 seconds. The lock holder releases in a `finally` block.

## Observed thread and transaction

One successful focused run recorded:

| Observation | Value |
| --- | --- |
| Request-thread name | `http-nio-auto-1-exec-1` |
| JVM thread ID | `30` |
| JVM thread identity hash | `2094850236` |
| Blocked snapshot state | `RUNNABLE` |
| Spring transaction name | `com.shiv.securegkd.allocation.AllocationService.allocate` |
| Spring transaction identity hash | `1821454813` |
| JDBC connection-handle identity hash | `926150878` |
| PostgreSQL backend PID | `27108` |
| Database metadata | PostgreSQL 16.14 |

Thread IDs, identity hashes, connection-handle identities, backend PIDs, and the
auto-numbered thread name are run-specific. The test records them dynamically and
asserts that one identical `Thread` identity appears at servlet-filter entry,
controller entry, service entry, repository entry, and the controlled database call.

The passing assertions observed no active Spring transaction at servlet-filter or
controller entry. The same named transaction was active, with the same transaction
status identity, at service, repository, and database boundaries. This matches the
`@Transactional` service boundary: the controller calls the Spring-managed service
proxy, which opens the transaction around `AllocationService.allocate`.

While PostgreSQL reported the advisory-lock wait, the request thread's focused stack
contained actually observed frames from:

- Java NIO and `SocketInputStream` socket reading;
- the PostgreSQL driver (`PGStream`, `QueryExecutorImpl`, `PgStatement`, and
  `PgPreparedStatement`);
- Hikari prepared-statement proxies;
- Spring `JdbcTemplate`;
- the test-only repository instrumentation and the real `AllocationService` path;
- the Spring transaction proxy and `AllocationController`; and
- Tomcat's application filter chain, valves, and `CoyoteAdapter`.

The snapshot state was `RUNNABLE`; Java commonly reports a thread waiting in native
socket I/O this way. The test does not reinterpret that state as CPU execution. No
Hibernate frame remained in this snapshot because the real Hibernate repository
selection had returned before the test-only JDBC advisory-lock call began.

After the observer released the lock, the existing request completed with
`201 Created` and the normal `AllocationResponse`. PostgreSQL-backed assertions found
exactly one allocation, exactly one idempotency record, and the requested idempotency
key.

## Threads, connections, and sessions

These are different resources:

- The Tomcat request thread is the Java worker executing the servlet, controller,
  transactional service, repository boundary, and blocking JDBC call.
- The JDBC connection handle is obtained from the Spring-managed Hikari datasource
  and associated with the active transaction. Its Java identity is not a thread ID.
- The PostgreSQL backend PID identifies the database server session handling that
  connection. It is not a Java thread identity or a Hikari pool slot number.

The run identified `HikariDataSource (HikariPool-1)` and a PostgreSQL
`PgConnection`, consistent with the resolved datasource evidence in
[`ALLOCATION_DATASOURCE_LIFECYCLE.md`](ALLOCATION_DATASOURCE_LIFECYCLE.md). It did not
prove later reuse of the same physical connection or compare pool capacity with the
Tomcat worker limits.

## Practical consequence and limits

This endpoint is synchronous. In the observed request, the same Tomcat worker stayed
occupied from HTTP entry through the database wait and could return the response only
after PostgreSQL unblocked. Slow synchronous database or other downstream work has
the same practical relationship: its request worker remains assigned until that work
completes or fails.

This single controlled request does not measure throughput, latency, fairness,
capacity, pool exhaustion, or production behavior. It does not justify changing
Tomcat or Hikari settings, and it adds no production tracing, logging, locking, or
asynchronous behavior.

## Reproduction

After loading the configured datasource variables into the current PowerShell
process without printing them, the focused command was:

```powershell
.\mvnw.cmd "-Dtest=AllocationRequestThreadIntegrationTests" test
```

Actual result: 1 test, 0 failures, 0 errors, 0 skipped, and `BUILD SUCCESS`.

The complete suite command was:

```powershell
.\mvnw.cmd test
```

Actual result: 37 tests, 0 failures, 0 errors, 0 skipped, and `BUILD SUCCESS`.
