package com.shiv.securegkd.audit.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AllocationCreatedJsonTests {

    private static final UUID EVENT_ID = UUID.fromString("018f47a2-5d91-7d37-a7f8-4d781f28b983");

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .build();

    @Test
    void deserializesTheVersionOneContractAndIgnoresCompatibleUnknownFields() throws Exception {
        AllocationCreated event = objectMapper.readValue(validJson("""
                ,
                "compatibleFutureField": "ignored"
                """), AllocationCreated.class);

        assertThat(event).isEqualTo(event());
    }

    @Test
    void rejectsUnsupportedSchemaVersions() {
        assertThatThrownBy(() -> objectMapper.readValue(
                validJson("").replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"),
                AllocationCreated.class
        )).hasRootCauseInstanceOf(IllegalArgumentException.class)
                .rootCause()
                .hasMessageContaining("Unsupported AllocationCreated schemaVersion: 2");
    }

    @Test
    void consumerRepresentationContainsOnlyTheEstablishedNonSecretFields() throws Exception {
        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(event()));
        Set<String> fields = new HashSet<>();
        json.fieldNames().forEachRemaining(fields::add);

        assertThat(fields).containsExactlyInAnyOrder(
                "eventId",
                "schemaVersion",
                "occurredAt",
                "allocationId",
                "allocatedAt",
                "gameId",
                "gameCode",
                "requestId"
        );
        assertThat(fields).doesNotContain(
                "gameKey",
                "keyCode",
                "idempotencyKey",
                "authorization",
                "jwt",
                "credentials"
        );
    }

    private String validJson(String additionalFields) {
        return """
                {
                  "eventId": "018f47a2-5d91-7d37-a7f8-4d781f28b983",
                  "schemaVersion": 1,
                  "occurredAt": "2026-08-30T09:10:11Z",
                  "allocationId": 42,
                  "allocatedAt": "2026-08-30T09:10:10Z",
                  "gameId": 7,
                  "gameCode": "DEMO-GAME",
                  "requestId": "f49f5ba7-53ee-4c8b-95af-29e75831176a"
                  %s
                }
                """.formatted(additionalFields);
    }

    private AllocationCreated event() {
        return new AllocationCreated(
                EVENT_ID,
                AllocationCreated.SUPPORTED_SCHEMA_VERSION,
                Instant.parse("2026-08-30T09:10:11Z"),
                42L,
                Instant.parse("2026-08-30T09:10:10Z"),
                7L,
                "DEMO-GAME",
                "f49f5ba7-53ee-4c8b-95af-29e75831176a"
        );
    }
}
