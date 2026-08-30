package com.shiv.securegkd.allocation.event;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({
        "eventId",
        "schemaVersion",
        "occurredAt",
        "allocationId",
        "allocatedAt",
        "gameId",
        "gameCode",
        "requestId"
})
public record AllocationCreated(
        @JsonProperty(value = "eventId", required = true)
        UUID eventId,

        @JsonProperty(value = "schemaVersion", required = true)
        int schemaVersion,

        @JsonProperty(value = "occurredAt", required = true)
        @JsonFormat(shape = JsonFormat.Shape.STRING, timezone = "UTC")
        Instant occurredAt,

        @JsonProperty(value = "allocationId", required = true)
        long allocationId,

        @JsonProperty(value = "allocatedAt", required = true)
        @JsonFormat(shape = JsonFormat.Shape.STRING, timezone = "UTC")
        Instant allocatedAt,

        @JsonProperty(value = "gameId", required = true)
        long gameId,

        @JsonProperty(value = "gameCode", required = true)
        String gameCode,

        @JsonProperty(value = "requestId", required = true)
        String requestId
) {

    public static final int SCHEMA_VERSION = 1;

    public AllocationCreated {
        Objects.requireNonNull(eventId, "eventId is required");
        Objects.requireNonNull(occurredAt, "occurredAt is required");
        Objects.requireNonNull(allocatedAt, "allocatedAt is required");
        Objects.requireNonNull(gameCode, "gameCode is required");
        Objects.requireNonNull(requestId, "requestId is required");

        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("schemaVersion must be " + SCHEMA_VERSION);
        }
    }
}
