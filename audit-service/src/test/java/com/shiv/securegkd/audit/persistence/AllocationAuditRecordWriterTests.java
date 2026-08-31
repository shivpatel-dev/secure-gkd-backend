package com.shiv.securegkd.audit.persistence;

import com.shiv.securegkd.audit.event.AllocationCreated;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AllocationAuditRecordWriterTests {

    @Test
    void atomicallyInsertsIntoTheSafelyQuotedConfiguredSchema() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.update(
                anyString(), any(), any(), anyInt(), any(), anyLong(), any(), anyLong(), any(), any(), any()
        )).thenReturn(1);
        AllocationAuditRecordWriter writer = new AllocationAuditRecordWriter(jdbcTemplate, "audit\"tenant");
        AllocationAuditRecord record = record();

        assertThat(writer.insertIfSourceEventUnseen(record)).isOne();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(
                sql.capture(),
                eq(record.getAuditRecordId()),
                eq(record.getSourceEventId()),
                eq(record.getSchemaVersion()),
                eq(Timestamp.from(record.getEventOccurredAt())),
                eq(record.getSourceAllocationId()),
                eq(Timestamp.from(record.getAllocatedAt())),
                eq(record.getSourceGameId()),
                eq(record.getGameCode()),
                eq(record.getRequestId()),
                eq(Timestamp.from(record.getPersistedAt()))
        );
        assertThat(sql.getValue())
                .contains("INSERT INTO \"audit\"\"tenant\".\"allocation_audit_record\"")
                .contains("ON CONFLICT (source_event_id) DO NOTHING");
    }

    @Test
    void rejectsBlankDatabaseSchema() {
        assertThatThrownBy(() -> new AllocationAuditRecordWriter(mock(JdbcTemplate.class), " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Database schema must not be blank");
    }

    private AllocationAuditRecord record() {
        AllocationCreated event = new AllocationCreated(
                UUID.fromString("018f47a2-5d91-7d37-a7f8-4d781f28b983"),
                1,
                Instant.parse("2026-08-30T09:10:11Z"),
                42L,
                Instant.parse("2026-08-30T09:10:10Z"),
                7L,
                "DEMO-GAME",
                "f49f5ba7-53ee-4c8b-95af-29e75831176a"
        );
        return new AllocationAuditRecord(
                UUID.fromString("328f47a2-5d91-7d37-a7f8-4d781f28b983"),
                event,
                Instant.parse("2026-08-30T09:10:12Z")
        );
    }
}
