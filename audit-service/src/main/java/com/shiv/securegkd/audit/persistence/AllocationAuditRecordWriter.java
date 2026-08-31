package com.shiv.securegkd.audit.persistence;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;

@Repository
class AllocationAuditRecordWriter {

    private final JdbcTemplate jdbcTemplate;
    private final String insertSql;

    AllocationAuditRecordWriter(
            JdbcTemplate jdbcTemplate,
            @Value("${AUDIT_DATABASE_SCHEMA:public}") String databaseSchema
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.insertSql = """
                INSERT INTO %s.%s (
                    audit_record_id,
                    source_event_id,
                    schema_version,
                    event_occurred_at,
                    source_allocation_id,
                    allocated_at,
                    source_game_id,
                    game_code,
                    request_id,
                    persisted_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (source_event_id) DO NOTHING
                """.formatted(
                quoteIdentifier(databaseSchema),
                quoteIdentifier("allocation_audit_record")
        );
    }

    int insertIfSourceEventUnseen(AllocationAuditRecord record) {
        return jdbcTemplate.update(
                insertSql,
                record.getAuditRecordId(),
                record.getSourceEventId(),
                record.getSchemaVersion(),
                Timestamp.from(record.getEventOccurredAt()),
                record.getSourceAllocationId(),
                Timestamp.from(record.getAllocatedAt()),
                record.getSourceGameId(),
                record.getGameCode(),
                record.getRequestId(),
                Timestamp.from(record.getPersistedAt())
        );
    }

    private String quoteIdentifier(String identifier) {
        if (identifier.isBlank()) {
            throw new IllegalArgumentException("Database schema must not be blank");
        }
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }
}
