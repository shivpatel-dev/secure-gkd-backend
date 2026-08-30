package com.shiv.securegkd.allocation.outbox;

import com.shiv.securegkd.allocation.Allocation;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "allocation_outbox")
public class AllocationOutbox {

    public static final String ALLOCATION_CREATED_EVENT_TYPE = "AllocationCreated";

    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "allocation_id", nullable = false, unique = true, updatable = false)
    private Allocation allocation;

    @Column(name = "event_type", nullable = false, updatable = false, length = 100)
    private String eventType;

    @Column(name = "schema_version", nullable = false, updatable = false)
    private int schemaVersion;

    @Column(nullable = false, updatable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected AllocationOutbox() {
    }

    public AllocationOutbox(
            UUID eventId,
            Allocation allocation,
            String eventType,
            int schemaVersion,
            String payload,
            Instant occurredAt
    ) {
        this.eventId = Objects.requireNonNull(eventId, "eventId is required");
        this.allocation = Objects.requireNonNull(allocation, "allocation is required");
        this.eventType = Objects.requireNonNull(eventType, "eventType is required");
        this.schemaVersion = schemaVersion;
        this.payload = Objects.requireNonNull(payload, "payload is required");
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt is required");
    }

    public UUID getEventId() {
        return eventId;
    }

    public Allocation getAllocation() {
        return allocation;
    }

    public String getEventType() {
        return eventType;
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }
}
