# Containerized local environment

Docker Compose can build and run the backend with PostgreSQL 16 and one Apache Kafka
4.3.1 broker without a host Java, Maven, PostgreSQL, or Kafka installation. This
workflow is for local development; it is not a production deployment configuration.

## Prerequisites and local secrets

Install Docker with Compose support. From the repository root, copy `.env.example`
to an untracked `.env` file and fill both required blank values:

- `SPRING_DATASOURCE_PASSWORD` is the local password shared by PostgreSQL and the
  application.
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
docker compose build application
docker compose up --detach
```

The `database` service owns PostgreSQL 16 and the named `postgres-data` volume. Its
port is internal to the Compose network and is not published to the host. The
`application` service uses the existing `local` Spring profile and connects to
`jdbc:postgresql://database:5432/secure_gkd` as `secure_gkd_user`. Compose waits for
the PostgreSQL readiness check before starting the application.

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
then describes it and exits. This topic is transport infrastructure only: it does not
define an `AllocationCreated` payload, schema, compatibility contract, producer, or
consumer.

The application still depends only on healthy PostgreSQL. It shares the Compose
network with Kafka, but it has no Kafka client and does not use Kafka for startup,
allocation correctness, or request processing.

The application image uses Java 17. Its build stage invokes the repository Maven
Wrapper, and the runtime stage contains the packaged Spring Boot application without
the Maven build toolchain.

On a new volume, application startup runs the existing Flyway migrations before
Hibernate validates the resulting schema. No manual schema SQL or seeded identity is
part of this workflow.

## Verify readiness, topic setup, and connectivity

Check service state and the public health endpoint on the published application port:

```sh
docker compose ps --all
curl http://localhost:8080/api/health
```

The health response should report `"status":"UP"`. If startup does not complete,
remember that this endpoint reports application HTTP health and does not independently
probe PostgreSQL or Kafka.

The broker should be healthy and topic initialization should have exited with code
zero. Query the broker directly to verify the topic and its local partition and
replication settings:

```sh
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --list
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --describe --topic secure-gkd.allocation-created
```

The description should report `PartitionCount: 1`, `ReplicationFactor: 1`, and one
partition whose leader, replica, and in-sync replica are the local broker.

Verify the application container's network path without adding application Kafka
behavior:

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

If startup or a check does not complete, inspect bounded recent logs rather than
exposing the rendered Compose configuration:

```sh
docker compose logs --tail 100 database kafka kafka-topic-init application
```

Common local Kafka failures include another process already using host port `29092`,
using `localhost:29092` from a container instead of `kafka:9092`, using `kafka:9092`
from the host where the Compose hostname is not resolvable, or stale local Kafka data
that does not match the tracked single-node cluster configuration. Check `docker
compose ps --all`, the bounded logs above, and the listener address before considering
a reset.

## Stop, retain, or reset data

Normal shutdown removes the containers and network but retains the named PostgreSQL
and Kafka volumes, so the next startup reuses local database and broker state. The
topic initializer can run again safely:

```sh
docker compose down
docker compose up --detach
```

To deliberately discard all local PostgreSQL and Kafka data and reproduce a clean
Flyway- and topic-initialization-backed startup, remove both Compose volumes and then
start again:

```sh
docker compose down --volumes
docker compose up --detach
```

Volume deletion is destructive. `docker compose down --volumes` deletes both local
database contents and Kafka broker/topic state. Use it only when all Compose-managed
local data is disposable. After either startup, repeat the application, broker,
topic, and connectivity checks above.
