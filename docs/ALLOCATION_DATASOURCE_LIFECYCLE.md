# Allocation Datasource and Connection-Pool Lifecycle

This document describes how the current allocation workflow reaches PostgreSQL through
Spring Data JPA, Hibernate, the Spring-managed datasource, and its connection pool. It
is a companion to the complete
[`ALLOCATION_RUNTIME_DEBUGGING.md`](ALLOCATION_RUNTIME_DEBUGGING.md) request flow and
the SQL-focused
[`ALLOCATION_SQL_DEBUGGING.md`](ALLOCATION_SQL_DEBUGGING.md) notes.

## Evidence scope

The claims below use four evidence categories:

- **Static source inspection** covers the tracked application configuration,
  `AllocationService`, repository interfaces, and
  `AllocationTransactionRollbackTests`.
- **Resolved dependency evidence** identifies the datasource, pool, ORM, and JDBC
  artifacts on this project's runtime classpath.
- **Observed PostgreSQL-backed test evidence** comes from one focused execution of
  `AllocationTransactionRollbackTests` with temporary command-line logging.
- **Framework-supported inference** explains connection acquisition and pool return
  where the focused output showed the surrounding lifecycle but not the physical pool
  event itself.

No allocation HTTP request was run. The observation did not exhaust the pool, tune it,
compare concurrent requests, or prove reuse of one physical connection by later
transactions.

## Current datasource path

The current path is:

```text
AllocationService.allocate
  -> Spring transaction management
  -> Spring Data JPA repositories
  -> Hibernate
  -> Spring-managed DataSource
  -> HikariCP
  -> PostgreSQL JDBC driver
  -> PostgreSQL
```

**Static source inspection.**
`AllocationController` delegates the endpoint request to
`AllocationService.allocate`. The service is annotated with `@Transactional` and
calls Spring Data repository interfaces. Neither the service nor those interfaces
call `DriverManager` or directly open a JDBC connection for normal repository work.
Spring Data invokes Hibernate, and Hibernate obtains JDBC access from the
Spring-managed `DataSource`.

The rollback test does call `dataSource.getConnection()` directly once, solely to
assert that the configured database product is PostgreSQL. That diagnostic assertion
is separate from the allocation service's normal repository path.

## Actual datasource and pool identity

**Resolved dependency evidence.**
The resolved dependency command was:

```powershell
.\mvnw.cmd "-Dincludes=org.springframework.boot:spring-boot-starter-jdbc,org.springframework:spring-jdbc,org.springframework:spring-orm,org.hibernate.orm:hibernate-core,org.postgresql:postgresql,com.zaxxer:HikariCP" dependency:tree
```

It completed with `BUILD SUCCESS` and resolved this relevant path:

```text
spring-boot-starter-data-jpa 3.5.14
  -> spring-boot-starter-jdbc 3.5.14
     -> HikariCP 6.3.3
     -> spring-jdbc 6.2.18
  -> hibernate-core 6.6.49.Final
  -> spring-data-jpa 3.5.11
     -> spring-orm 6.2.18
postgresql 42.7.10 (runtime)
```

That tree proves that HikariCP is available transitively, but availability alone does
not prove it is the selected runtime pool. The focused test supplied the second part
of the evidence:

**Observed PostgreSQL-backed test evidence.**

- Hikari logged `HikariPool-1 - Starting...` and `Start completed`.
- Hikari logged pooled connections whose delegate class was
  `org.postgresql.jdbc.PgConnection`.
- Hibernate reported that it was connecting through
  `HikariDataSource (HikariPool-1)`.

Together, the resolved tree and runtime output identify the active test datasource as
`com.zaxxer.hikari.HikariDataSource`, backed by HikariCP 6.3.3 and PostgreSQL JDBC
42.7.10. This observation establishes the implementation selected for the current
test application context. It does not establish that every future deployment must use
the same pool if its dependencies or datasource configuration change.

## Explicit configuration and framework-supplied behavior

**Static source inspection.**
The following values are explicitly present in tracked `application.yaml`:

| Setting | Explicit project value |
| --- | --- |
| `spring.datasource.url` | `SPRING_DATASOURCE_URL`, with fallback `jdbc:postgresql://localhost:5432/secure_gkd` |
| `spring.datasource.username` | `SPRING_DATASOURCE_USERNAME`, with fallback `secure_gkd_user` |
| `spring.datasource.password` | `SPRING_DATASOURCE_PASSWORD`; no credential value is tracked |
| `spring.datasource.driver-class-name` | `org.postgresql.Driver` |
| `spring.jpa.hibernate.ddl-auto` | `none` |
| `spring.jpa.open-in-view` | `false` |

The focused test explicitly overrides `spring.jpa.hibernate.ddl-auto` to
`create-drop`. Its `@AutoConfigureTestDatabase(replace = NONE)` annotation keeps the
configured datasource instead of replacing it with an embedded test database. There
is no tracked `src/test/resources` configuration.

The project does **not** explicitly set:

- `spring.datasource.type`;
- Hikari pool size, timeout, lifetime, or pool-name properties;
- Hibernate's physical connection handling mode; or
- permanent logging levels for the datasource, pool, transaction manager, or JDBC
  connection lifecycle.

Spring Boot selected Hikari in the observed test context because it was available and
no different datasource type was configured. Pool settings not listed in tracked
configuration are framework- or environment-supplied values, not explicit project
settings. The diagnostic output showed runtime pool state, but this document does not
promote those observed values into project configuration or tuning guidance.

## Transaction and connection lifecycle

### Transaction start and acquisition

**Static source inspection and observed test evidence.**
When a caller reaches `AllocationService.allocate` through its Spring proxy,
`JpaTransactionManager` starts the transaction before the method body runs. The
focused output showed:

- creation of a transaction named
  `com.shiv.securegkd.allocation.AllocationService.allocate`;
- a new JPA `EntityManager`;
- exposure of that JPA transaction through a Hibernate JDBC connection handle.

`@Transactional` declares the boundary; it does not make application code manually
open a JDBC connection. Hibernate obtains JDBC access through the datasource. The
first repository operation in `allocate` is the idempotency-key lookup, so the
workflow needs database access at that point at the latest.

The focused logger did not emit a physical Hikari checkout event correlated
specifically with the service transaction. Therefore, whether the physical connection
was acquired during transaction setup or deferred until the first SQL statement was
not directly observed. It is framework-supported inference that Hibernate obtains it
from Hikari no later than the first required JDBC operation.

### Transaction-bound repository work

**Static source inspection.**
All repository calls inside `allocate`, `allocateNewGameKey`, and `saveAllocation`
execute within the public method's transaction.

**Observed PostgreSQL-backed test evidence.**
During the focused run,
`JpaTransactionManager` logged that a repository operation found the thread-bound
`EntityManager` and participated in the existing allocation transaction.

`AllocationRepository.saveAndFlush` flushes the allocation insert while the service
transaction is still active. It does not commit independently. The later
idempotency-record save remains part of the same transaction.

### Completion, rollback, and pool eligibility

**Observed PostgreSQL-backed test evidence.**
The test deliberately makes the idempotency repository throw after the allocation
flush. The observed sequence for the service transaction was:

1. create the allocation transaction and `EntityManager`;
2. expose a Hibernate JDBC connection handle;
3. have repository work participate in that existing transaction;
4. initiate and perform a JPA rollback;
5. close the transaction's `EntityManager`; and
6. log Hibernate logical-connection closing and closed events.

The test then queried PostgreSQL through new repository transactions and confirmed
that neither the flushed allocation nor an idempotency record remained. This is
database-backed rollback evidence.

**Framework-supported inference.**
Closing the Hibernate logical connection after transaction completion makes its
underlying pooled connection eligible to be returned to Hikari. With a pooled
datasource, normal logical `Connection.close()` handling returns the connection to the
pool rather than necessarily closing the physical PostgreSQL socket. That pool-return
interpretation is framework-supported inference: the selected logger showed logical
connection closure but did not emit a transaction-specific Hikari return event.

A later transaction may borrow the same physical pooled connection, but it has a new
transaction and logical connection lifecycle. This run did not compare physical
connection identities across transactions and therefore does not claim that reuse
occurred.

## Controlled PostgreSQL-backed observation

After loading the local datasource variables into the current PowerShell process
without printing them, the exact Maven command was:

```powershell
.\mvnw.cmd "-Dtest=AllocationTransactionRollbackTests" `
  "-Dspring.output.ansi.enabled=never" `
  "-Dlogging.level.com.zaxxer.hikari.HikariDataSource=DEBUG" `
  "-Dlogging.level.com.zaxxer.hikari.pool.HikariPool=DEBUG" `
  "-Dlogging.level.org.springframework.orm.jpa.JpaTransactionManager=DEBUG" `
  "-Dlogging.level.org.hibernate.resource.jdbc.internal.LogicalConnectionManagedImpl=TRACE" `
  test
```

The settings were command-line-only; no tracked logging configuration or raw output
was created.

Actual result:

- Maven started and completed with `BUILD SUCCESS`.
- `AllocationTransactionRollbackTests` started and ran one test.
- Result: 1 test, 0 failures, 0 errors, 0 skipped.
- The test's JDBC metadata assertion identified PostgreSQL, and Hibernate reported
  PostgreSQL 16.14.
- Runtime output identified `HikariDataSource (HikariPool-1)` and
  `org.postgresql.jdbc.PgConnection`.
- Transaction-manager output showed the named allocation transaction, its
  transaction-bound JPA context, and rollback.
- Hibernate output showed logical connection closure after transaction completion.
- Post-rollback repository assertions confirmed the flushed allocation and
  idempotency record were absent.

The observation did not prove the exact instant of physical pool checkout, emit a
transaction-specific Hikari return event, prove reuse of a physical connection, or
exercise the HTTP endpoint.
