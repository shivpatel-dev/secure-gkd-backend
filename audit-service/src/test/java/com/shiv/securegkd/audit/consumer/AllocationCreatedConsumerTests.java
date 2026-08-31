package com.shiv.securegkd.audit.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.shiv.securegkd.audit.event.AllocationCreated;
import com.shiv.securegkd.audit.persistence.AllocationAuditPersistenceOutcome;
import com.shiv.securegkd.audit.persistence.AllocationAuditPersistenceService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class AllocationCreatedConsumerTests {

    private static final String EVENT_ID = "018f47a2-5d91-7d37-a7f8-4d781f28b983";

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .build();
    private AllocationAuditPersistenceService persistenceService;
    private AllocationCreatedConsumer consumer;

    @BeforeEach
    void setUp() {
        persistenceService = mock(AllocationAuditPersistenceService.class);
        when(persistenceService.persist(any(AllocationCreated.class)))
                .thenReturn(AllocationAuditPersistenceOutcome.PERSISTED);
        consumer = new AllocationCreatedConsumer(objectMapper, persistenceService);
    }

    @Test
    void persistsAValidEventAfterEnforcingTransportIdentity(CapturedOutput output) {
        consumer.consume(record(EVENT_ID, validJson()));

        verify(persistenceService).persist(any(AllocationCreated.class));
        assertThat(output).contains("allocation_audit_persisted")
                .doesNotContain("allocation_audit_duplicate_ignored");
    }

    @Test
    void treatsADuplicateAsSuccessfulProcessingWithoutLoggingPayloadData(CapturedOutput output) {
        when(persistenceService.persist(any(AllocationCreated.class)))
                .thenReturn(AllocationAuditPersistenceOutcome.DUPLICATE);

        consumer.consume(record(EVENT_ID, validJson()));

        verify(persistenceService).persist(any(AllocationCreated.class));
        assertThat(output).contains("allocation_audit_duplicate_ignored")
                .contains("eventId=" + EVENT_ID)
                .doesNotContain("DEMO-GAME")
                .doesNotContain("f49f5ba7-53ee-4c8b-95af-29e75831176a")
                .doesNotContain("gameKey")
                .doesNotContain("idempotencyKey");
    }

    @Test
    void rejectsMalformedJsonWithoutPersistence() {
        assertThatThrownBy(() -> consumer.consume(record(EVENT_ID, "{not-json")))
                .isInstanceOf(AllocationAuditProcessingException.class)
                .hasMessage("Allocation audit event processing failed");

        verify(persistenceService, never()).persist(any());
    }

    @Test
    void rejectsUnsupportedSchemaVersionWithoutPersistence() {
        String unsupported = validJson().replace("\"schemaVersion\": 1", "\"schemaVersion\": 2");

        assertThatThrownBy(() -> consumer.consume(record(EVENT_ID, unsupported)))
                .isInstanceOf(AllocationAuditProcessingException.class);

        verify(persistenceService, never()).persist(any());
    }

    @Test
    void rejectsKafkaKeyAndPayloadIdentityMismatchWithoutPersistence() {
        assertThatThrownBy(() -> consumer.consume(record(
                UUID.fromString("128f47a2-5d91-7d37-a7f8-4d781f28b983").toString(),
                validJson()
        ))).isInstanceOf(AllocationAuditProcessingException.class);

        verify(persistenceService, never()).persist(any());
    }

    @Test
    void surfacesAuditPersistenceFailure(CapturedOutput output) {
        doThrow(new IllegalStateException("database unavailable"))
                .when(persistenceService)
                .persist(any(AllocationCreated.class));

        assertThatThrownBy(() -> consumer.consume(record(EVENT_ID, validJson())))
                .isInstanceOf(AllocationAuditProcessingException.class);

        assertThat(output).contains("allocation_audit_processing_failed")
                .contains("failureType=IllegalStateException")
                .doesNotContain("database unavailable")
                .doesNotContain("DEMO-GAME");
    }

    private ConsumerRecord<String, String> record(String key, String value) {
        return new ConsumerRecord<>("secure-gkd.allocation-created", 0, 12L, key, value);
    }

    private String validJson() {
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
                }
                """;
    }
}
