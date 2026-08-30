package com.shiv.securegkd.audit.persistence;

import com.shiv.securegkd.audit.event.AllocationCreated;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.beans.factory.annotation.Value;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AuditDatabaseIntegrationTests {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AllocationAuditPersistenceService persistenceService;

    @Autowired
    private AllocationAuditRecordRepository repository;

    @Value("${AUDIT_DATABASE_SCHEMA:public}")
    private String databaseSchema;

    @AfterEach
    void cleanAuditRecords() {
        repository.deleteAllInBatch();
    }

    @Test
    void flywayOwnsTheAuditPostgresqlSchemaAndHibernateUsesIt() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            assertThat(connection.getMetaData().getDatabaseMajorVersion()).isEqualTo(16);
        }

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("1");
        assertThat(flyway.info().applied())
                .anySatisfy(migration -> {
                    assertThat(migration.getVersion().getVersion()).isEqualTo("1");
                    assertThat(migration.getDescription()).isEqualTo("create allocation audit schema");
                });
    }

    @Test
    void persistsOnlyAuditOwnedFieldsWithoutCrossDatabaseRelationships() {
        AllocationAuditRecord saved = persistenceService.persist(event());

        AllocationAuditRecord reloaded = repository.findById(saved.getAuditRecordId()).orElseThrow();
        assertThat(reloaded.getSourceEventId()).isEqualTo(event().eventId());
        assertThat(reloaded.getSourceAllocationId()).isEqualTo(42L);
        assertThat(reloaded.getSourceGameId()).isEqualTo(7L);
        assertThat(reloaded.getGameCode()).isEqualTo("DEMO-GAME");
        assertThat(reloaded.getRequestId()).isEqualTo(event().requestId());
        assertThat(reloaded.getPersistedAt()).isNotNull();

        List<String> columns = jdbcTemplate.queryForList("""
                select column_name
                from information_schema.columns
                where table_schema = ?
                  and table_name = 'allocation_audit_record'
                order by ordinal_position
                """, String.class, databaseSchema);
        assertThat(columns).containsExactly(
                "audit_record_id",
                "source_event_id",
                "schema_version",
                "event_occurred_at",
                "source_allocation_id",
                "allocated_at",
                "source_game_id",
                "game_code",
                "request_id",
                "persisted_at"
        );
        assertThat(jdbcTemplate.queryForObject("""
                select count(*)
                from information_schema.table_constraints
                where table_schema = ?
                  and table_name = 'allocation_audit_record'
                  and constraint_type = 'FOREIGN KEY'
                """, Long.class, databaseSchema)).isZero();
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
