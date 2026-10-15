# Temporary AWS EC2 deployment runbook

This runbook describes the temporary, single-instance Secure GKD deployment verified
on October 14, 2026 and removed on October 15, 2026. It was a non-production exercise
of the complete allocation-to-audit path, not permanent hosting or production-grade
infrastructure. No AWS environment remains available.

[AWS deployment verification](AWS_DEPLOYMENT_VERIFICATION.md) records the historical
results supplied in [issue #127](https://github.com/shivpatel-dev/secure-gkd-backend/issues/127).
[Deployment runtime](DEPLOYMENT_RUNTIME.md) remains authoritative for provider-neutral
image, configuration, schema, health, and shutdown contracts. The separate
[Render runbook](DEPLOYMENT_RUNBOOK.md) retains its provider-specific procedure.

## Historical deployment shape

| Property | Observed configuration |
| --- | --- |
| Region | `ap-southeast-2` (Sydney) |
| Compute | One EC2 `m7i-flex.large`, 2 vCPUs and 8 GiB nominal RAM |
| Operating system | Ubuntu Server 24.04 LTS |
| Storage | Encrypted 40 GiB gp3 root EBS volume |
| SSH | EC2 Security Group restricted to the operator's client public IP |
| Container tooling | Docker Engine 29.9.0 and Docker Compose v5.6.0 |
| Deployment model | Existing repository `compose.yaml` plus an AWS-specific override outside the repository |
| Allocation profile and HTTP | `SPRING_PROFILES_ACTIVE=prod`; host binding `127.0.0.1:8080` |
| Database and Kafka access | Allocation database internal to Docker; no published host ports for Kafka or the audit database |
| Administration | `sudo` Docker commands; the Ubuntu user was not added to the Docker group |
| Secrets | Generated secrets and deployment environment variables stored outside Git |

The existing [six-service Compose topology](../compose.yaml) was used without tracked
application, Dockerfile, or Compose changes:

| Service | Responsibility and networking |
| --- | --- |
| `database` | Allocation-owned PostgreSQL 16 at `database:5432`; `postgres-data` volume |
| `audit-database` | Audit-owned PostgreSQL 16 at `audit-database:5432`; `audit-postgres-data` volume |
| `kafka` | One Kafka 4.3.1 KRaft broker/controller; container clients use `kafka:9092`; `kafka-data` volume |
| `kafka-topic-init` | One-shot initialization of source and dead-letter topics; successful exit is expected |
| `application` | Allocation HTTP service and enabled outbox publisher; only loopback HTTP published |
| `audit-service` | Independent Kafka consumer and audit persistence; no HTTP API |

Five long-running services started, and the topic initializer exited successfully.
Database and Kafka traffic stayed within the Compose network. Kafka used the existing
plaintext, single-node configuration; this is not evidence of external TLS, IAM
integration, broker redundancy, or production security. The `prod` profile selects
application configuration; it does not make the surrounding infrastructure production-ready.

## Proposed reproduction preparation

The following procedure and examples describe how to reproduce that bounded shape.
They are not a transcript of the deleted environment, its private paths, or its exact
override file. Recheck provider documentation and obtain authorization for a new
deployment before creating resources.

1. Select the reviewed repository revision intentionally. Record its full SHA and,
   for stronger provenance, the resulting image IDs/digests in a private deployment
   record. The historical checkout alone did not prove image-to-commit provenance.
2. In Sydney, prepare the instance, OS, and encrypted root volume described above.
   Review the volume's **Delete on termination** setting and plan cleanup before launch.
3. Restrict inbound TCP `22` to the current operator public IP as a `/32` rule. Do not
   open application, PostgreSQL, or Kafka ports to the internet. If the client IP
   changes, replace the restricted SSH rule instead of widening it. Keep SSH key
   material outside Git with client permissions acceptable to OpenSSH.
4. On the fresh Ubuntu host, follow Docker's official
   [Ubuntu installation procedure](https://docs.docker.com/engine/install/ubuntu/)
   to configure its signed apt repository and handle any conflicting packages. Then
   install Engine and the Compose plugin and check the daemon and versions:

   ```sh
   sudo apt update
   sudo apt install docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
   sudo systemctl is-active docker
   sudo docker version
   sudo docker compose version
   ```

   If the daemon is inactive, investigate and start it with `sudo systemctl start docker`.
   Package availability may differ from the historical versions. Use `sudo` for
   administration without adding the login user to the Docker group. Host Java and
   Maven are unnecessary: both existing Dockerfiles build with Java 17 and the
   repository Maven Wrapper.
5. Obtain a clean checkout of the reviewed revision through the authorized repository
   access method. Keep deployment files outside both the checkout and Docker build
   context; do not place access tokens in clone URLs or shell history. Check the
   checkout with `git status --short` and `git rev-parse HEAD` before building.

## External configuration example

Use an operator-owned directory outside the checkout, mode `0700`, with a deployment
environment file mode `0600`. Supply independently generated database passwords and
JWT signing material through a private editor or approved secret mechanism. Do not
print them, embed them in shell arguments, or retain them in transcripts. The file
needs these names; the angle-bracket values below are placeholders, not usable secrets:

```dotenv
SPRING_DATASOURCE_PASSWORD=<generated-allocation-database-password>
AUDIT_DATASOURCE_PASSWORD=<different-generated-audit-database-password>
JWT_SIGNING_KEY_BASE64=<Base64-for-at-least-32-random-bytes>
```

The base Compose model supplies each service's own JDBC URL and username, enables the
outbox publisher, and supplies container-network Kafka connectivity. See
[Configuration profiles](CONFIGURATION_PROFILES.md) for the full configuration contract.
Environment-based secrets are accessible to privileged host/Docker administrators;
this exercise did not implement a managed secret store.

An external `compose.aws.yaml` can express the historical profile and port boundaries:

```yaml
services:
  application:
    environment:
      SPRING_PROFILES_ACTIVE: prod
    ports: !override
      - "127.0.0.1:8080:8080"
  audit-database:
    ports: !override []
  kafka:
    ports: !override []
```

Docker's [Compose merge rules](https://docs.docker.com/reference/compose-file/merge/)
require Compose 2.24.4 or later for `!override`. Replacement matters: an ordinary
additional port entry can preserve the base file's wider binding. The allocation
database already has no host mapping. Removing Kafka's host mapping leaves its
container listener `kafka:9092` usable by the applications and topic initializer.
The audit service needs no `prod` profile; it owns its independent configuration.

## Startup and bounded inspection

In an Ubuntu shell, replace the example paths below with the new checkout and external
configuration directory. The proposed project name is fixed across commands to avoid
accidentally selecting a different stack or set of named volumes:

```sh
GKD_REPO=/path/to/secure-gkd-backend
GKD_DEPLOY=/path/to/external-deployment
gkd_compose() {
  sudo docker compose --project-name secure-gkd-aws \
    --project-directory "$GKD_REPO" --env-file "$GKD_DEPLOY/deployment.env" \
    -f "$GKD_REPO/compose.yaml" -f "$GKD_DEPLOY/compose.aws.yaml" "$@"
}
gkd_compose config --quiet
```

Before startup, privately inspect the effective port mappings and allocation profile
with synthetic configuration values if needed. Do not publish `compose config`
output containing real interpolated secrets or unrestricted container inspection
output. Confirm HTTP is loopback-only and neither database nor Kafka publishes a host
port. Then build, start, and inspect using the same context:

```sh
gkd_compose build application audit-service
gkd_compose up --detach
gkd_compose ps --all
gkd_compose logs --since 10m --tail 100 application audit-service kafka-topic-init
curl --fail --silent --show-error --max-time 10 http://127.0.0.1:8080/api/health
sudo docker stats --no-stream
df -h /
sudo docker system df
```

Expect both databases and Kafka to become healthy, both Spring services to run, and
`kafka-topic-init` to exit with code `0`. Check bounded startup logs for allocation
Flyway V1–V5, audit Flyway V1–V2, and successful application startup. HTTP `200` with
`status: UP` proves HTTP availability only; it independently checks neither database
nor Kafka. Logs and resource observations remain private until sanitized.

Run HTTP checks on EC2 loopback. If client access is needed for a new exercise, use an
authorized SSH tunnel bound to client loopback rather than opening port `8080` in the
Security Group. The historical evidence does not establish production HTTPS.

For functional checks, follow [API examples](API_EXAMPLES.md) using temporary synthetic
ADMIN/USER identities and separately provisioned GameKey inventory. Keep credentials,
tokens, password hashes, and returned GameKey values out of records. There is no
registration or GameKey-provisioning endpoint. Correlate the new allocation's persisted
outbox event and `published_at` with the audit row and consumer-reported topic,
partition, and offset; health alone cannot establish this path. The historical
assertions and reuse limits are recorded in [AWS verification](AWS_DEPLOYMENT_VERIFICATION.md).
The private verifier is not a tracked deliverable.

| Symptom | Bounded investigation |
| --- | --- |
| SSH rejected or unreachable | Check client key permissions, current `/32` Security Group rule, and the intended host privately. |
| Wrong stack, missing files, or Docker permission error | Use the same explicit project, base/override files, environment file, and `sudo` wrapper for every command. |
| Database or migration startup failure | Inspect service state and bounded logs; check owned datasource settings and schema history. Do not reset volumes or alter migrations to force startup. |
| Kafka or topic initialization failure | Inspect broker health and initializer exit/logs; check container listener connectivity and both topic definitions. |
| HTTP unavailable | Confirm application startup and loopback mapping. Separate configuration/startup failures from an HTTP response. |
| Allocation succeeds but audit is absent | Inspect pending/published outbox state, consumer logs, and the audit-owned database with bounded waits. Do not treat HTTP health as transport evidence. |
| Verification tooling fails | Check tool versions and supported options before attributing the failure to the application; see the recorded verifier lessons. |

## Persistence, termination, and cleanup

The three named Docker volumes store allocation PostgreSQL, audit PostgreSQL, and
Kafka data on the instance's EBS-backed root storage. Container replacement normally
preserves named volumes, but this repository-defined storage arrangement was not
tested through AWS restart/recovery. It supplies no backup, replication, or disaster
recovery. Volume deletion or root-volume deletion destroys this data. Do not use
`down --volumes` or broad Docker pruning during investigation.

For a planned shutdown, stop new verification requests and use the same context:

```sh
gkd_compose stop --timeout 60
gkd_compose ps --all
gkd_compose logs --since 5m --tail 100 application audit-service
```

Confirm stopped containers and inspect shutdown results before removing the host;
this proposed procedure is not historical restart or graceful-shutdown evidence.
AWS documents that [instance termination](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/terminating-instances.html)
is irreversible and deletes attached EBS volumes configured for deletion. Before an
authorized teardown, decide explicitly whether any data must be retained; backup and
restore were not verified here.

In the AWS console, verify the intended account, Sydney region, and exact exercise
resources privately. Terminate only the authorized instance and wait for `Terminated`.
Check for remaining exercise EBS volumes, owned snapshots, and Elastic IP allocations;
delete or release only resources confirmed to belong to the exercise. Do not interpret
termination alone as proof of their absence. Check network-interface associations
before deleting the dedicated Security Group, following AWS's
[Security Group deletion rules](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/deleting-security-group.html).
Delete the dedicated AWS SSH key-pair record and separately remove the corresponding
local private key and verify its absence; [AWS key-pair deletion](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/delete-key-pair.html)
does not delete the local file. Remove unneeded private deployment configuration and
temporary verification material under the operator's retention policy.

Record sanitized cleanup results and any residual resources. The October 15 cleanup
results are historical observations in [AWS verification](AWS_DEPLOYMENT_VERIFICATION.md),
not a claim that this reproduction procedure has been executed again.
