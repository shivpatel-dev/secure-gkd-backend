package com.shiv.securegkd.audit.persistence;

import com.shiv.securegkd.audit.event.AllocationCreated;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "allocation_audit_record")
public class AllocationAuditRecord {

    @Id
    @Column(name = "audit_record_id", nullable = false, updatable = false)
    private UUID auditRecordId;

    @Column(name = "source_event_id", nullable = false, updatable = false)
    private UUID sourceEventId;

    @Column(name = "schema_version", nullable = false, updatable = false)
    private int schemaVersion;

    @Column(name = "event_occurred_at", nullable = false, updatable = false)
    private Instant eventOccurredAt;

    @Column(name = "source_allocation_id", nullable = false, updatable = false)
    private long sourceAllocationId;

    @Column(name = "allocated_at", nullable = false, updatable = false)
    private Instant allocatedAt;

    @Column(name = "source_game_id", nullable = false, updatable = false)
    private long sourceGameId;

    @Column(name = "game_code", nullable = false, updatable = false, length = 100)
    private String gameCode;

    @Column(name = "request_id", nullable = false, updatable = false, length = 255)
    private String requestId;

    @Column(name = "persisted_at", nullable = false, updatable = false)
    private Instant persistedAt;

    protected AllocationAuditRecord() {
    }

    AllocationAuditRecord(UUID auditRecordId, AllocationCreated event, Instant persistedAt) {
        this.auditRecordId = Objects.requireNonNull(auditRecordId, "auditRecordId is required");
        Objects.requireNonNull(event, "event is required");
        this.sourceEventId = event.eventId();
        this.schemaVersion = event.schemaVersion();
        this.eventOccurredAt = event.occurredAt();
        this.sourceAllocationId = event.allocationId();
        this.allocatedAt = event.allocatedAt();
        this.sourceGameId = event.gameId();
        this.gameCode = event.gameCode();
        this.requestId = event.requestId();
        this.persistedAt = Objects.requireNonNull(persistedAt, "persistedAt is required");
    }

    public UUID getAuditRecordId() {
        return auditRecordId;
    }

    public UUID getSourceEventId() {
        return sourceEventId;
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public Instant getEventOccurredAt() {
        return eventOccurredAt;
    }

    public long getSourceAllocationId() {
        return sourceAllocationId;
    }

    public Instant getAllocatedAt() {
        return allocatedAt;
    }

    public long getSourceGameId() {
        return sourceGameId;
    }

    public String getGameCode() {
        return gameCode;
    }

    public String getRequestId() {
        return requestId;
    }

    public Instant getPersistedAt() {
        return persistedAt;
    }
}
