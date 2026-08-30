# Containerized local environment

Docker Compose can build and run the allocation service, the independent allocation-
audit consumer, their separate PostgreSQL 16 databases, and one Apache Kafka 4.3.1
broker without host Java, Maven, PostgreSQL, or Kafka installations. This workflow is
for local development; it is not a production deployment configuration.

## Prerequisites and local secrets

Install Docker with Compose support. From the repository root, copy `.env.example`
to an untracked `.env` file and fill all three required blank values:

- `SPRING_DATASOURCE_PASSWORD` is the local password shared by PostgreSQL and the
  application.
- `AUDIT_DATASOURCE_PASSWORD` is a separate local password shared only by the
  audit-owned PostgreSQL service and audit application.
- `AUDIT_DATABASE_HOST_PORT` is the non-secret loopback port used to reach the audit
  database from the host. The template uses `55432`; choose another available port
  when local port policy requires it.
- `JWT_SIGNING_KEY_BASE64` is Base64 for at least 32 bytes of signing material.

If OpenSSL is already installed, generate suitable local JWT material with:

```sh
openssl rand -base64 32
```

On Windows, either Windows PowerShell or modern PowerShell can generate the same
32-byte value without OpenSSL:

```powershell
$bytes = New-Object byte[] 32
$generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try {
    $generator.GetBytes($bytes)
    [Convert]::ToBase64String($bytes)
}
finally {
    $generator.Dispose()
}
```

Paste the generated output after `JWT_SIGNING_KEY_BASE64=` in `.env`. Never commit
`.env` or the generated value. `JWT_ACCESS_TOKEN_LIFETIME` is optional and retains
the existing `PT15M` default when omitted; accepted overrides remain whole seconds
from one second through 24 hours.

## Build and start

Validate the Compose model without rendering interpolated secret values, then build
and start the environment:

```sh
docker compose config --quiet
docker compose build application audit-service
docker compose up --detach
```

The `database` service owns PostgreSQL 16 and the named `postgres-data` volume. Its
port is internal to the Compose network and is not published to the host. The
`application` service uses the existing `local` Spring profile and connects to
`jdbc:postgresql://database:5432/secure_gkd` as `secure_gkd_user`. Compose waits for
the PostgreSQL readiness check before starting the application.

The `audit-database` service separately owns PostgreSQL 16, database
`secure_gkd_audit`, and the named `audit-postgres-data` volume. Container port `5432`
is published only on IPv4 loopback through `AUDIT_DATABASE_HOST_PORT`, which defaults
to `55432`. A host-launched audit service uses the same setting in its default JDBC
URL; an explicit `AUDIT_DATASOURCE_URL` still takes precedence. The Compose
`audit-service` continues to connect directly to
`jdbc:postgresql://audit-database:5432/secure_gkd_audit`, so changing the host port
does not change service-to-service networking. Its Flyway history and
`allocation_audit_record` table do not share the allocation database or its volume.

The `kafka` service is one combined broker/controller in KRaft mode, with no
ZooKeeper. It uses the explicitly pinned `apache/kafka:4.3.1` image and the named
`kafka-data` volume. The single-node replication and minimum in-sync-replica settings
are deliberate local-development settings, not production recommendations.

Kafka has two client access paths whose advertised listeners match their callers:

| Caller | Bootstrap address |
| --- | --- |
| Containers on the Compose network | `kafka:9092` |
| Tools running on the host | `localhost:29092` |

Both listeners use unauthenticated plaintext transport and must remain local-only.
Port `29092` is published only on the host's IPv4 loopback interface at
`127.0.0.1`; the controller listener is internal.

The broker health check requests Kafka topic metadata, so a merely running container
process is not considered ready. After the broker becomes healthy, the one-shot
`kafka-topic-init` service idempotently ensures that
`secure-gkd.allocation-created` exists with one partition and replication factor one,
then describes it and exits. Broker-side automatic topic creation is disabled so this
explicit initialization remains authoritative. This topic is transport infrastructure
only: it does not define the `AllocationCreated` payload, schema, or compatibility
contract.

The application still depends only on healthy PostgreSQL. It shares the Compose
network with Kafka and Compose enables its optional outbox publisher using
`kafka:9092`. Kafka health is deliberately absent from the application's startup
dependencies: a broker outage must not prevent startup, determine allocation
correctness, or participate in request processing. Committed pending rows recover
through later publisher polling.

The audit service depends on its own healthy database, the healthy broker, and
successful topic initialization. It consumes with group
`secure-gkd-allocation-audit`. These dependencies are one-way: the `application`
service has no dependency on `audit-service` or `audit-database`, so stopping either
audit component does not prevent synchronous allocation startup or health.

Both application images use Java 17, invoke the repository Maven Wrapper in their own
build stages, contain only their own packaged Spring Boot JAR at runtime, and run as
non-root users.

On new volumes, each application runs its own Flyway migrations before Hibernate
validates its own schema. No manual schema SQL or seeded identity is part of this
workflow.

## Verify readiness, topic setup, and connectivity

Check service state and the public health endpoint on the published application port:

```sh
docker compose ps --all
curl http://localhost:8080/api/health
```

The health response should report `"status":"UP"`. If startup does not complete,
remember that this endpoint reports application HTTP health and does not independently
probe PostgreSQL or Kafka.

The broker and both databases should be healthy, both applications should be running,
and topic initialization should have exited with code zero. Query the broker directly
to verify the topic and its local partition and replication settings:

```sh
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --list
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --describe --topic secure-gkd.allocation-created
```

The description should report `PartitionCount: 1`, `ReplicationFactor: 1`, and one
partition whose leader, replica, and in-sync replica are the local broker.

Verify the application container's Kafka network path:

```sh
docker compose exec application getent hosts kafka
docker compose exec application bash -c 'exec 3<>/dev/tcp/kafka/9092'
```

The first command must resolve `kafka`; the second exits successfully only when the
broker port can be opened from the application container. Kafka tools installed on
the host should use `localhost:29092`, not the Compose-only hostname.

Repeated topic initialization is safe and does not recreate or replace an existing
topic:

```sh
docker compose run --rm kafka-topic-init
```

Inspect acknowledged publications independently with the broker's command-line
consumer:

```sh
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka:9092 \
  --topic secure-gkd.allocation-created \
  --from-beginning \
  --property print.key=true \
  --property key.separator=' | '
```

The printed key is the persisted outbox `event_id`; the value is the exact stored
version-1 JSON payload. `published_at` becomes non-null only after producer
acknowledgement. It does not prove consumer processing. Stop the command after the
bounded messages needed for local verification. If the broker is unavailable, new
allocations remain valid and their outbox rows remain pending; after broker recovery,
the scheduled publisher retries them. A crash or database failure after Kafka accepts
a message but before `published_at` commits can produce the same key and payload more
than once.

## Verify audit consumption and independence

Produce one non-secret synthetic version-1 record directly with the local Kafka tool:

```sh
printf '%s\n' '018f47a2-5d91-7d37-a7f8-4d781f28b983|{"eventId":"018f47a2-5d91-7d37-a7f8-4d781f28b983","schemaVersion":1,"occurredAt":"2026-08-30T09:10:11Z","allocationId":42,"allocatedAt":"2026-08-30T09:10:10Z","gameId":7,"gameCode":"DEMO-GAME","requestId":"f49f5ba7-53ee-4c8b-95af-29e75831176a"}' \
  | docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh \
      --bootstrap-server kafka:9092 \
      --topic secure-gkd.allocation-created \
      --property parse.key=true \
      --property key.separator='|'
```

Then query only the audit-owned database:

```sh
docker compose exec audit-database psql \
  --username secure_gkd_audit_user \
  --dbname secure_gkd_audit \
  --command "select source_event_id, schema_version, source_allocation_id, source_game_id, game_code, request_id from allocation_audit_record order by persisted_at;"
```

The row should contain the synthetic source values. It must not contain a GameKey,
allocation idempotency key, credentials, token material, or arbitrary request data.
The audit service records a separate `audit_record_id` and `persisted_at` timestamp.

For bounded restart evidence, stop and start the audit service, wait for its listener
to rejoin the stable group, publish a second record with a new event UUID and source
identifiers, and verify the second row. Normal Spring shutdown closes the listener,
Kafka consumer, JPA context, and datasource cleanly:

```sh
docker compose stop audit-service
docker compose start audit-service
docker compose logs --tail 100 audit-service
```

To verify allocation independence, stop the audit service and confirm the allocation
application remains healthy:

```sh
docker compose stop audit-service
curl http://localhost:8080/api/health
docker compose start audit-service
```

This direct synthetic check is deliberately bounded. It does not claim a final
allocation-to-outbox-to-Kafka integration suite, duplicate-safe processing, or an
exactly-once database/offset transaction. A failure after audit commit but before
Kafka offset progress can create another audit row. Application-owned retry/backoff,
dead-letter handling, and poison-message recovery remain later work.

If startup or a check does not complete, inspect bounded recent logs rather than
exposing the rendered Compose configuration:

```sh
docker compose logs --tail 100 database audit-database kafka kafka-topic-init application audit-service
```

If Docker reports that the audit database host port cannot be bound, choose an
available loopback port in `.env`, for example
`AUDIT_DATABASE_HOST_PORT=55433`, rerun `docker compose config --quiet`, and start the
environment again. Do not change the container-side `5432` port or the
`audit-database:5432` service address.

Common local Kafka failures include another process already using host port `29092`,
using `localhost:29092` from a container instead of `kafka:9092`, using `kafka:9092`
from the host where the Compose hostname is not resolvable, or stale local Kafka data
that does not match the tracked single-node cluster configuration. Check `docker
compose ps --all`, the bounded logs above, and the listener address before considering
a reset.

## Stop, retain, or reset data

Normal shutdown removes the containers and network but retains both named PostgreSQL
volumes and the Kafka volume, so the next startup reuses local database and broker
state. The topic initializer can run again safely:

```sh
docker compose down
docker compose up --detach
```

To deliberately discard all allocation PostgreSQL, audit PostgreSQL, and Kafka data and reproduce a clean
Flyway- and topic-initialization-backed startup, remove both Compose volumes and then
start again:

```sh
docker compose down --volumes
docker compose up --detach
```

Volume deletion is destructive. `docker compose down --volumes` deletes both local
database contents and Kafka broker/topic state. Use it only when all Compose-managed
local data is disposable. After either startup, repeat both application, database,
broker, topic, and connectivity checks above.
