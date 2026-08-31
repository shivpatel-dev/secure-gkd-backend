# AllocationCreated event contract

The allocation service owns the versioned `AllocationCreated` JSON contract. Version
1 is defined in
`com.shiv.securegkd.allocation.event.AllocationCreated` and is intentionally separate
from HTTP DTOs, JPA entities, Kafka APIs, and the
`com.shiv.securegkd.allocation.outbox.AllocationOutbox` persistence type.

For each newly created Allocation, the synchronous allocation transaction creates one
version-1 event, serializes it to JSON, and persists its publication intent in the
allocation service's `allocation_outbox` table. An idempotent replay creates no event
or outbox row. An optional background publisher sends committed pending rows to
Kafka after allocation commits. The independent allocation-audit service consumes
that published contract and persists a downstream audit fact in its own PostgreSQL
database. The services share neither application tables nor Java implementation
types.

## Version 1 fields

The intended serialized representation is JSON with these exact property names:

| Property | JSON type | Meaning |
| --- | --- | --- |
| `eventId` | string (UUID) | Identity of one logical `AllocationCreated` event. A retry or duplicate publication of that same logical event must retain this value. |
| `schemaVersion` | integer | Explicit contract version. This contract requires `1`. It is independent of application and deployment versions. |
| `occurredAt` | string (ISO-8601 UTC timestamp) | When the allocation service creates the event intent. This is event metadata, not the authoritative allocation time. |
| `allocationId` | integer | Durable allocation-service identity of the represented Allocation. This identifies the allocation, not the event. |
| `allocatedAt` | string (ISO-8601 UTC timestamp) | Authoritative allocation time copied from the Allocation. |
| `gameId` | integer | Durable allocation-service identity of the associated Game. |
| `gameCode` | string | Non-secret Game business code observed for the allocation. |
| `requestId` | string | Server-generated request-correlation identifier associated with the allocation request. It is diagnostic correlation data only. |

`eventId`, `allocationId`, and `requestId` have different roles. `eventId` identifies
one logical event and is the value that remains stable across future retries or
duplicate publication. `allocationId` identifies the authoritative Allocation.
`requestId` correlates diagnostics; it is not event identity, allocation identity, an
allocation idempotency key, or a delivery-deduplication key.

`occurredAt` and `allocatedAt` also remain distinct. `occurredAt` records when the
allocation service creates the event intent. `allocatedAt` comes from the Allocation
and remains the authoritative allocation timestamp.

A representative version-1 payload is:

```json
{
  "eventId": "018f47a2-5d91-7d37-a7f8-4d781f28b983",
  "schemaVersion": 1,
  "occurredAt": "2026-08-30T09:10:11Z",
  "allocationId": 42,
  "allocatedAt": "2026-08-30T09:10:10Z",
  "gameId": 7,
  "gameCode": "DEMO-GAME",
  "requestId": "f49f5ba7-53ee-4c8b-95af-29e75831176a"
}
```

## Event semantics

`AllocationCreated` states that the allocation service successfully created one new
Allocation. Returning an already-existing Allocation for an idempotent replay does
not represent another `AllocationCreated` event.

The Allocation and its PostgreSQL-backed transaction remain authoritative for
allocation correctness. Kafka and audit processing do not decide whether synchronous
allocation succeeds. Audit state is eventually consistent and may lag the allocation
state. Publication or delivery may be duplicated, and this contract does not imply
exactly-once or effectively-once end-to-end processing.

The outbox row stores the same `eventId`, schema version, and `occurredAt` as the JSON
payload, references the authoritative Allocation, and starts with `published_at` set
to `NULL`. The Allocation, its IdempotencyRecord, and this event intent commit or roll
back together. PostgreSQL also permits only one outbox intent per Allocation.
After Kafka acknowledges a send, the publisher conditionally records an Instant-based
UTC `published_at` value in a separate transaction. That timestamp means only that
the producer received successful Kafka acknowledgement; it does not mean a consumer
processed the event or that downstream persistence succeeded.

## Compatibility and evolution

Version-1 property names, types, and meanings must not be removed, renamed, or
incompatibly reinterpreted while `schemaVersion` remains `1`. Additive fields are
compatible only when older consumers can safely ignore unknown properties; the Java
version-1 contract does so during deserialization.

A breaking change to a required property name, type, or meaning requires a new schema
version. Version changes must be deliberate and must not be inferred from an
application or deployment version. Consumers must inspect the explicit
`schemaVersion` instead of assuming that every message uses the newest shape. This
contract uses JSON and the existing `Instant` model; it introduces no schema registry,
Avro, or Protobuf boundary.

## Ownership, transport, and sensitive data

The allocation service owns the contract, event-intent creation, and publication.
`secure-gkd.allocation-created` is the Kafka transport. The allocation-audit service
owns only the audit records it derives; it does not own or redefine allocation
semantics and does not access allocation-service tables. Its consumer-owned Java
record explicitly supports schema version 1 and ignores compatible unknown JSON
properties. It accepts a record only when the string Kafka key parses as the same UUID
as payload `eventId`.

Version 1 contains no GameKey code or other secret game-key value. It also contains
no serialized GameKey, Allocation, Game, or other JPA entity; allocation idempotency
key; username or password material; Bearer token; JWT contents or signing material;
datasource credentials; arbitrary request payload; or arbitrary request header.
`AllocationResponse` is not reused because its `keyCode` is the allocated secret.

Transactional-outbox persistence, asynchronous publication, audit consumption, and
audit-owned persistence are implemented. The audit group is
`secure-gkd-allocation-audit`, starts at `earliest` for a new group, disables Kafka
auto-commit, and uses record acknowledgement so successful processing returns only
after the audit database transaction. The audit record retains `eventId` as
`source_event_id`, and audit-owned PostgreSQL enforces that value as unique. The
consumer uses an atomic insert-or-ignore operation targeting only that uniqueness
rule: an unseen identity creates one service-owned audit record, while a repeated
identity completes successfully without replacing or changing the existing row.
No separate processed-event table is needed.

The database commit and Kafka offset are not one distributed transaction. A failure
after persistence but before offset progress can still redeliver the same `eventId`;
the durable uniqueness rule makes that redelivery a no-op rather than a second audit
effect. Kafka publication and delivery therefore remain at-least-once, and this is
not an exactly-once end-to-end guarantee.

Application-owned retry classification, retry limits/backoff, dead-letter topics,
and poison-message recovery are not implemented. Malformed JSON, unsupported
versions, key/payload mismatches, and genuine persistence failures stop listener
processing and are logged with bounded metadata rather than the complete payload.
Newly persisted, intentionally ignored duplicate, and failed outcomes have distinct
bounded log events. Pending and published outbox rows and audit rows have no
automatic retention or cleanup policy. Later work must preserve the synchronous and
PostgreSQL-backed correctness boundary described in
[Architecture decision 7](DECISIONS.md#7-add-one-asynchronous-boundary-for-allocation-audit-processing).
