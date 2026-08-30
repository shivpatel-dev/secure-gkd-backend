package com.shiv.securegkd.allocation.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AllocationCreatedJsonTests {

    private static final UUID EVENT_ID = UUID.fromString("018f47a2-5d91-7d37-a7f8-4d781f28b983");
    private static final Instant OCCURRED_AT = Instant.parse("2026-08-30T09:10:11Z");
    private static final Instant ALLOCATED_AT = Instant.parse("2026-08-30T09:10:10Z");
    private static final long ALLOCATION_ID = 42L;
    private static final long GAME_ID = 7L;
    private static final String GAME_CODE = "DEMO-GAME";
    private static final String REQUEST_ID = "f49f5ba7-53ee-4c8b-95af-29e75831176a";

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .build();

    @Test
    void serializesTheExactVersionOnePublicContract() throws Exception {
        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(event()));
        Set<String> propertyNames = new HashSet<>();
        json.fieldNames().forEachRemaining(propertyNames::add);

        assertThat(propertyNames).containsExactlyInAnyOrder(
                "eventId",
                "schemaVersion",
                "occurredAt",
                "allocationId",
                "allocatedAt",
                "gameId",
                "gameCode",
                "requestId"
        );
        assertThat(json.get("eventId").textValue()).isEqualTo(EVENT_ID.toString());
        assertThat(json.get("schemaVersion").intValue()).isEqualTo(AllocationCreated.SCHEMA_VERSION);
        assertThat(json.get("occurredAt").textValue()).isEqualTo("2026-08-30T09:10:11Z");
        assertThat(json.get("allocationId").longValue()).isEqualTo(ALLOCATION_ID);
        assertThat(json.get("allocatedAt").textValue()).isEqualTo("2026-08-30T09:10:10Z");
        assertThat(json.get("gameId").longValue()).isEqualTo(GAME_ID);
        assertThat(json.get("gameCode").textValue()).isEqualTo(GAME_CODE);
        assertThat(json.get("requestId").textValue()).isEqualTo(REQUEST_ID);
        assertThat(propertyNames).doesNotContain(
                "keyCode",
                "gameKey",
                "allocation",
                "game",
                "idempotencyKey",
                "username",
                "authorization",
                "jwt"
        );
    }

    @Test
    void deserializesVersionOneAndIgnoresCompatibleAdditiveFields() throws Exception {
        String json = """
                {
                  "eventId": "018f47a2-5d91-7d37-a7f8-4d781f28b983",
                  "schemaVersion": 1,
                  "occurredAt": "2026-08-30T09:10:11Z",
                  "allocationId": 42,
                  "allocatedAt": "2026-08-30T09:10:10Z",
                  "gameId": 7,
                  "gameCode": "DEMO-GAME",
                  "requestId": "f49f5ba7-53ee-4c8b-95af-29e75831176a",
                  "compatibleFutureField": "ignored"
                }
                """;

        assertThat(objectMapper.readValue(json, AllocationCreated.class)).isEqualTo(event());
    }

    private AllocationCreated event() {
        return new AllocationCreated(
                EVENT_ID,
                AllocationCreated.SCHEMA_VERSION,
                OCCURRED_AT,
                ALLOCATION_ID,
                ALLOCATED_AT,
                GAME_ID,
                GAME_CODE,
                REQUEST_ID
        );
    }
}
