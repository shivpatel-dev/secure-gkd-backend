package com.shiv.securegkd.audit.persistence;

import com.shiv.securegkd.audit.event.AllocationCreated;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AllocationAuditPersistenceServiceTests {

    private static final Instant PERSISTED_AT = Instant.parse("2026-08-30T09:10:12Z");

    @Test
    void mapsTheEventToAServiceOwnedAuditRecordAndRetainsSourceIdentity() {
        AllocationAuditRecordWriter recordWriter = mock(AllocationAuditRecordWriter.class);
        when(recordWriter.insertIfSourceEventUnseen(any())).thenReturn(1);
        AllocationAuditPersistenceService service = new AllocationAuditPersistenceService(
                recordWriter,
                Clock.fixed(PERSISTED_AT, ZoneOffset.UTC)
        );

        AllocationAuditPersistenceOutcome outcome = service.persist(event());

        ArgumentCaptor<AllocationAuditRecord> recordCaptor =
                ArgumentCaptor.forClass(AllocationAuditRecord.class);
        verify(recordWriter).insertIfSourceEventUnseen(recordCaptor.capture());
        AllocationAuditRecord record = recordCaptor.getValue();
        assertThat(outcome).isEqualTo(AllocationAuditPersistenceOutcome.PERSISTED);
        assertThat(record.getAuditRecordId()).isNotNull().isNotEqualTo(event().eventId());
        assertThat(record.getSourceEventId()).isEqualTo(event().eventId());
        assertThat(record.getSchemaVersion()).isEqualTo(1);
        assertThat(record.getEventOccurredAt()).isEqualTo(event().occurredAt());
        assertThat(record.getSourceAllocationId()).isEqualTo(42L);
        assertThat(record.getAllocatedAt()).isEqualTo(event().allocatedAt());
        assertThat(record.getSourceGameId()).isEqualTo(7L);
        assertThat(record.getGameCode()).isEqualTo("DEMO-GAME");
        assertThat(record.getRequestId()).isEqualTo(event().requestId());
        assertThat(record.getPersistedAt()).isEqualTo(PERSISTED_AT);
    }

    @Test
    void reportsDatabaseDetectedDuplicateAsSuccessfulOutcome() {
        AllocationAuditRecordWriter recordWriter = mock(AllocationAuditRecordWriter.class);
        when(recordWriter.insertIfSourceEventUnseen(any())).thenReturn(0);
        AllocationAuditPersistenceService service = new AllocationAuditPersistenceService(
                recordWriter,
                Clock.systemUTC()
        );

        assertThat(service.persist(event())).isEqualTo(AllocationAuditPersistenceOutcome.DUPLICATE);
    }

    @Test
    void doesNotConvertRepositoryFailureIntoSuccess() {
        AllocationAuditRecordWriter recordWriter = mock(AllocationAuditRecordWriter.class);
        when(recordWriter.insertIfSourceEventUnseen(any()))
                .thenThrow(new IllegalStateException("write failed"));
        AllocationAuditPersistenceService service = new AllocationAuditPersistenceService(
                recordWriter,
                Clock.systemUTC()
        );

        assertThatThrownBy(() -> service.persist(event()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("write failed");
    }

    @Test
    void doesNotClassifyAnUnexpectedInsertResultAsDuplicate() {
        AllocationAuditRecordWriter recordWriter = mock(AllocationAuditRecordWriter.class);
        when(recordWriter.insertIfSourceEventUnseen(any())).thenReturn(2);
        AllocationAuditPersistenceService service = new AllocationAuditPersistenceService(
                recordWriter,
                Clock.systemUTC()
        );

        assertThatThrownBy(() -> service.persist(event()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Unexpected allocation audit insert result");
    }

    private AllocationCreated event() {
        return new AllocationCreated(
                UUID.fromString("018f47a2-5d91-7d37-a7f8-4d781f28b983"),
                1,
                Instant.parse("2026-08-30T09:10:11Z"),
                42L,
                Instant.parse("2026-08-30T09:10:10Z"),
                7L,
                "DEMO-GAME",
                "f49f5ba7-53ee-4c8b-95af-29e75831176a"
        );
    }
}
