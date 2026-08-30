# AllocationCreated event contract

The allocation service owns the versioned `AllocationCreated` JSON contract. Version
1 is defined in
`com.shiv.securegkd.allocation.event.AllocationCreated` and is intentionally separate
from HTTP DTOs, JPA entities, Kafka APIs, and any future transactional-outbox
persistence type.

This repository currently defines only the contract. The synchronous allocation flow
does not create, persist, or publish an `AllocationCreated` event. There is no outbox,
Kafka application client, producer, consumer, allocation-audit service, audit
persistence, or distributed end-to-end event processing.

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

`occurredAt` and `allocatedAt` also remain distinct. `occurredAt` records when future
event-creation work creates the event intent. `allocatedAt` comes from the Allocation
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
allocation correctness. Kafka availability and future audit processing do not decide
whether synchronous allocation succeeds. Future publication may be duplicated, and
this contract does not imply exactly-once end-to-end delivery.

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

The allocation service owns the contract and will eventually own creation of the
event. `secure-gkd.allocation-created` is the intended Kafka transport. A later
allocation-audit service will consume the contract and own only the audit records it
derives; it will not own or redefine allocation semantics.

Version 1 contains no GameKey code or other secret game-key value. It also contains
no serialized GameKey, Allocation, Game, or other JPA entity; allocation idempotency
key; username or password material; Bearer token; JWT contents or signing material;
datasource credentials; arbitrary request payload; or arbitrary request header.
`AllocationResponse` is not reused because its `keyCode` is the allocated secret.

Transactional-outbox persistence, event creation, Kafka publication and recovery,
allocation-audit consumption and persistence, and consumer idempotency remain future
work. Their later implementation must preserve the synchronous and PostgreSQL-backed
correctness boundary described in [Architecture decision 7](DECISIONS.md#7-add-one-asynchronous-boundary-for-allocation-audit-processing).
