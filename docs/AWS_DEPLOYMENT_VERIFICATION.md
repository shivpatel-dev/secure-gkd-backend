# Temporary AWS EC2 deployment verification

This document records bounded historical evidence from the complete Secure GKD
allocation-to-audit deployment on October 14, 2026 and cloud-resource cleanup on
October 15, 2026. The environment has been deleted; it is not a currently available
service. The historical observations come from the approved
[issue #127](https://github.com/shivpatel-dev/secure-gkd-backend/issues/127), not a new
AWS verification performed while writing this document.

The [AWS runbook](AWS_DEPLOYMENT_RUNBOOK.md) describes the operational shape and a
separately labeled reproduction procedure. [Deployment runtime](DEPLOYMENT_RUNTIME.md)
defines provider-neutral contracts. The earlier [Render verification](DEPLOYMENT_VERIFICATION.md)
remains evidence for its allocation application and PostgreSQL exercise, including
one controlled restart; it did not cover Kafka or the independent audit service.

## Evidence categories and provenance

- **Repository-defined behavior** is established by source, configuration, migrations,
  and container definitions; it does not prove an AWS runtime outcome.
- **Observed AWS behavior** below is the bounded historical runtime evidence supplied
  by the approved issue, including persisted state and transport observations.
- **Not verified on AWS** identifies capabilities for which this exercise supplies
  no evidence. Proposed operator procedures are not historical observations.

The EC2 repository checkout was observed at
`ad8dcdfaee70cc39111338f768f3d5c1e676af12`. Built image-to-commit provenance was not
independently established. That checkout observation is not proof that the deployed
images were built from that commit.

## Infrastructure and startup observations

The environment used Sydney (`ap-southeast-2`), one EC2 `m7i-flex.large` with 2 vCPUs
and 8 GiB nominal RAM, Ubuntu Server 24.04 LTS, and an encrypted 40 GiB gp3 root EBS
volume. Observed tooling was Docker Engine 29.9.0 and Docker Compose v5.6.0.
The existing six-service Compose topology ran with an external AWS override and
externally stored configuration/secrets. The allocation application selected `prod`
and published HTTP only at `127.0.0.1:8080`. The allocation database stayed internal;
Kafka and the audit database had no published host ports. SSH access was restricted
by an EC2 Security Group, and Docker administration used `sudo` without Docker-group
membership for the Ubuntu user.

Observed startup results:

| Component or check | Historical result |
| --- | --- |
| Allocation PostgreSQL and audit PostgreSQL | Both containers healthy |
| Kafka | Container healthy |
| Allocation application and audit service | Both Spring Boot services running |
| `kafka-topic-init` | One-shot initializer completed successfully |
| Allocation Flyway history | V1–V5 applied |
| Audit Flyway history | V1–V2 applied |
| `GET /api/health` | HTTP `200`, `status: UP` |

Five services were long-running; successful exit of the sixth was expected.
Repository-defined health behavior establishes HTTP availability, not independent
database, Kafka, or audit readiness. Database and messaging evidence came from the
separate checks below. Bounded container-health and resource observations did not
establish long-term capacity.

## Authentication and protected API observations

Two temporary synthetic database-backed identities were provisioned with `ADMIN`
and `USER` roles solely for verification. No registration or identity-management
API was introduced. Observed results across the logical verification were:

| Operation | Historical result |
| --- | --- |
| ADMIN authentication at `POST /api/auth/token` | Successful |
| USER authentication at `POST /api/auth/token` | Successful |
| Unauthenticated protected requests or invalid credentials | Expected HTTP `401` |
| USER attempting ADMIN-only `POST /api/games` | Expected HTTP `403` |
| ADMIN Game creation | Successful; synthetic Game created through the API |
| Protected Game retrieval | Successful |
| Invalid requests covered by validation | Expected HTTP `400` |
| Missing Game retrieval | Expected HTTP `404` |
| Oversized covered requests | Expected HTTP `413` |

The repository's [API notes](API_NOTES.md) and [security rules](SECURITY.md) define
the authentication, authorization, and validation boundaries. The body-size filter
covers JSON POSTs to token issuance, Game creation, and allocation; the observed
`413` checks are not evidence of a universal request-size limit on every route.
Credentials, tokens, hashes, and secret response values are omitted.

## Allocation, idempotency, and failure observations

One synthetic GameKey was inserted directly into the allocation database for the
API-created Game because there is no public GameKey-provisioning endpoint. This
was temporary fixture provisioning, not a migration or a new API capability.

The successful USER allocation returned HTTP `201` and left one durable Allocation,
one corresponding IdempotencyRecord, and one corresponding transactional-outbox
event. Replaying the same allocation request returned HTTP `201` with the identical
allocation response and created no additional allocation, idempotency record, or
outbox event. No secret GameKey value or idempotency-key value is recorded here.

The exercised missing-game and exhausted-inventory **allocation** requests returned
generic HTTP `500` responses. The verifier checked that these failures left no
additional fixture rows. These are observed implementation results, not an
endorsement of the HTTP mapping or a general proof of every failure/rollback path.
Missing Game **retrieval**, separately, returned the expected HTTP `404`.

Repository-defined behavior in [AllocationService](../src/main/java/com/shiv/securegkd/allocation/AllocationService.java)
persists Allocation, IdempotencyRecord, and outbox intent in one transaction, while
an exact replay returns the original allocation. Its missing-game and inventory
exceptions reach the generic handler in
[GlobalExceptionHandler](../src/main/java/com/shiv/securegkd/GlobalExceptionHandler.java).
Those source observations explain the exercised results without changing them.

## Kafka-to-audit observations

The successful allocation produced a version-1 `AllocationCreated` event on
`secure-gkd.allocation-created`. Historical verification confirmed:

1. The originating event was durably persisted in `allocation_outbox`.
2. The publisher recorded `published_at` following Kafka acknowledgement.
3. The independent audit database contained one matching durable record.
4. The record's event, allocation, Game, request, and timestamp metadata matched
   the originating event. The audit-owned persistence timestamp remained a separate
   field rather than an originating event timestamp.
5. At the topic, partition, and offset reported by the audit consumer, the exact
   Kafka key and payload matched the stored outbox event.

Together these observations correlated Kafka delivery with durable audit-database
persistence. The [event contract](ALLOCATION_CREATED_EVENT.md) defines the fields
and sensitive-data exclusions, and [Architecture](ARCHITECTURE.md) explains the
repository's asynchronous delivery semantics. This bounded successful path is not
proof of exactly-once transport, duplicate-redelivery handling, or retry recovery
on AWS.

## Resumed verification and reused fixtures

The successful logical verification used run ID
`8436affd-3b29-4f4d-bfd8-c25f310baa6c`. Its final result was:

```text
SUCCESS (RESUMED): all executed assertions passed; REUSED operations were not newly exercised.
```

The initial successful allocation was retained while verification-script problems
were corrected. The final resumed run reused the same synthetic fixtures, verified
persisted state, and completed the remaining Kafka observation. The tables above
describe results across that logical exercise; they do not claim every operation
was freshly executed in the final run.

The original allocation was exercised earlier; retained fixture/state checks and
the remaining Kafka observation contributed to the resumed success. Operations
marked `REUSED` were not executed again. The supplied historical record does not
provide a complete per-assertion fresh/reused ledger, so no finer execution history
is inferred here. A resumed success does not establish a second independent clean
deployment or a fresh allocation-to-audit run.

## Troubleshooting observations

| Problem encountered | Operational lesson and classification |
| --- | --- |
| Windows SSH private-key permissions initially prevented authentication | Client key-permission problem; resolve privately without publishing key material or fingerprints. |
| Client public IP changed | Update the restricted SSH Security Group rule to the current client IP; network-access issue. |
| Verifier used incorrect Compose context and Docker privilege assumptions | Verification tooling problem; use consistent external files/project context and `sudo`. |
| Python naming conflict involving `http.client` | Early verifier defect, not an application defect. |
| Unsupported Kafka console-consumer option combination | Later verifier defect; use options supported by the installed Kafka tooling. |

Correcting the verifier allowed the complete bounded functional check to pass.
The allocation HTTP `500` mappings described above were application behavior;
the script failures were distinct verifier defects. Private diagnostics and the
temporary verifier/environment are not tracked documentation deliverables.

## Not verified on AWS

- Restart and recovery behavior.
- Concurrency or load performance.
- Kafka retry and dead-letter recovery.
- Duplicate Kafka redelivery handling.
- Backup and restore, or disaster recovery.
- High availability or autoscaling.
- Production HTTPS, external TLS, or IAM integration.
- Long-term retention or monitoring.
- Independent deployed image-to-commit provenance.

Neither nominal instance resources nor bounded observations prove sustained capacity
or production reliability. Repository tests and the earlier Render restart evidence
do not turn these untested AWS capabilities into observed AWS results.

## Observed resource cleanup

On October 15, 2026, cleanup in Sydney observed:

- The GKD EC2 instance reached `Terminated`.
- No EBS volumes remained.
- No owned EBS snapshots remained.
- No Elastic IP allocations remained.
- The GKD-specific Security Group was deleted after confirming no matching
  network-interface associations.
- The GKD-specific AWS SSH key-pair record was deleted.
- The corresponding local Windows private SSH key was deleted and its absence verified.

These are the issue's historical cleanup observations, not new AWS queries or a
claim about every region or future account state. No application data or Kafka
records were retained in a live environment after the EC2 root-volume deletion.
There is no retained AWS deployment to inspect. This record excludes account
identifiers, private/client IP addresses, SSH fingerprints, actual private paths,
credentials, signing material, tokens, password hashes, and secret GameKey values.
