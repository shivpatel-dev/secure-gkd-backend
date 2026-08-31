package com.shiv.securegkd.audit.persistence;

import com.shiv.securegkd.audit.event.AllocationCreated;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AuditDatabaseIntegrationTests {

    private final UUID primarySourceEventId = UUID.randomUUID();
    private final Set<UUID> testSourceEventIds = new HashSet<>(Set.of(primarySourceEventId));

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
        repository.deleteAllInBatch(repository.findAllBySourceEventIdIn(testSourceEventIds));
    }

    @Test
    void flywayOwnsTheAuditPostgresqlSchemaAndHibernateUsesIt() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            assertThat(connection.getMetaData().getDatabaseMajorVersion()).isEqualTo(16);
        }

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("2");
        assertThat(flyway.info().applied())
                .anySatisfy(migration -> {
                    assertThat(migration.getVersion().getVersion()).isEqualTo("1");
                    assertThat(migration.getDescription()).isEqualTo("create allocation audit schema");
                })
                .anySatisfy(migration -> {
                    assertThat(migration.getVersion().getVersion()).isEqualTo("2");
                    assertThat(migration.getDescription()).isEqualTo("make source event id unique");
                });

        assertThat(jdbcTemplate.queryForObject("""
                select count(*)
                from information_schema.table_constraints
                where table_schema = ?
                  and table_name = 'allocation_audit_record'
                  and constraint_name = 'uk_allocation_audit_source_event_id'
                  and constraint_type = 'UNIQUE'
                """, Long.class, databaseSchema)).isOne();
        assertThat(jdbcTemplate.queryForList("""
                select column_name
                from information_schema.constraint_column_usage
                where table_schema = ?
                  and table_name = 'allocation_audit_record'
                  and constraint_name = 'uk_allocation_audit_source_event_id'
                """, String.class, databaseSchema)).containsExactly("source_event_id");
    }

    @Test
    void persistsOnlyAuditOwnedFieldsWithoutCrossDatabaseRelationships() {
        assertThat(persistenceService.persist(event()))
                .isEqualTo(AllocationAuditPersistenceOutcome.PERSISTED);

        AllocationAuditRecord reloaded = repository.findBySourceEventId(event().eventId()).orElseThrow();
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

    @Test
    void repeatedSourceEventIsSuccessfulAndLeavesTheOriginalAuditRecordUnchanged() {
        assertThat(persistenceService.persist(event()))
                .isEqualTo(AllocationAuditPersistenceOutcome.PERSISTED);
        AllocationAuditRecord original = repository.findBySourceEventId(event().eventId()).orElseThrow();

        assertThat(persistenceService.persist(event()))
                .isEqualTo(AllocationAuditPersistenceOutcome.DUPLICATE);

        assertThat(repository.findAllBySourceEventIdIn(Set.of(primarySourceEventId))).hasSize(1);
        AllocationAuditRecord retained = repository.findBySourceEventId(event().eventId()).orElseThrow();
        assertThat(retained.getAuditRecordId()).isEqualTo(original.getAuditRecordId());
        assertThat(retained.getSourceEventId()).isEqualTo(original.getSourceEventId());
        assertThat(retained.getSchemaVersion()).isEqualTo(original.getSchemaVersion());
        assertThat(retained.getEventOccurredAt()).isEqualTo(original.getEventOccurredAt());
        assertThat(retained.getSourceAllocationId()).isEqualTo(original.getSourceAllocationId());
        assertThat(retained.getAllocatedAt()).isEqualTo(original.getAllocatedAt());
        assertThat(retained.getSourceGameId()).isEqualTo(original.getSourceGameId());
        assertThat(retained.getGameCode()).isEqualTo(original.getGameCode());
        assertThat(retained.getRequestId()).isEqualTo(original.getRequestId());
        assertThat(retained.getPersistedAt()).isEqualTo(original.getPersistedAt());
    }

    @Test
    void aDistinctSourceEventStillCreatesItsOwnAuditRecord() {
        UUID distinctSourceEventId = newTestSourceEventId();
        AllocationCreated distinctEvent = event(
                distinctSourceEventId,
                43L,
                8L,
                "ANOTHER-GAME"
        );

        assertThat(persistenceService.persist(event()))
                .isEqualTo(AllocationAuditPersistenceOutcome.PERSISTED);
        assertThat(persistenceService.persist(distinctEvent))
                .isEqualTo(AllocationAuditPersistenceOutcome.PERSISTED);

        assertThat(repository.findAllBySourceEventIdIn(
                Set.of(primarySourceEventId, distinctSourceEventId)
        )).hasSize(2);
        assertThat(repository.findBySourceEventId(primarySourceEventId)).isPresent();
        assertThat(repository.findBySourceEventId(distinctSourceEventId)).isPresent();
    }

    @Test
    void databaseUniquenessProtectsSourceIdentityWhenAtomicInsertPathIsBypassed() {
        assertThat(persistenceService.persist(event()))
                .isEqualTo(AllocationAuditPersistenceOutcome.PERSISTED);
        AllocationAuditRecord bypassedRecord = new AllocationAuditRecord(
                UUID.randomUUID(),
                event(),
                Instant.parse("2026-08-30T09:10:13Z")
        );

        assertThatThrownBy(() -> repository.saveAndFlush(bypassedRecord))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(repository.findAllBySourceEventIdIn(Set.of(primarySourceEventId))).hasSize(1);
    }

    @Test
    void concurrentDeliveryCreatesOneAuditEffectAndOneDuplicateOutcome() throws Exception {
        UUID unrelatedSourceEventId = newTestSourceEventId();
        AllocationCreated unrelatedEvent = event(
                unrelatedSourceEventId,
                45L,
                10L,
                "UNRELATED-GAME"
        );
        assertThat(persistenceService.persist(unrelatedEvent))
                .isEqualTo(AllocationAuditPersistenceOutcome.PERSISTED);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<AllocationAuditPersistenceOutcome>> futures = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return persistenceService.persist(event());
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<AllocationAuditPersistenceOutcome> outcomes = List.of(
                    futures.get(0).get(10, TimeUnit.SECONDS),
                    futures.get(1).get(10, TimeUnit.SECONDS)
            );
            assertThat(outcomes)
                    .containsExactlyInAnyOrder(
                            AllocationAuditPersistenceOutcome.PERSISTED,
                            AllocationAuditPersistenceOutcome.DUPLICATE
                    );
            assertThat(repository.findAllBySourceEventIdIn(Set.of(primarySourceEventId))).hasSize(1);
            assertThat(repository.findBySourceEventId(unrelatedSourceEventId)).isPresent();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void unrelatedDatabaseConstraintFailureIsNotClassifiedAsDuplicate() {
        UUID invalidSourceEventId = newTestSourceEventId();
        AllocationCreated invalidForPersistence = event(
                invalidSourceEventId,
                44L,
                9L,
                "X".repeat(101)
        );

        assertThatThrownBy(() -> persistenceService.persist(invalidForPersistence))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(repository.findAllBySourceEventIdIn(Set.of(invalidSourceEventId))).isEmpty();
    }

    private AllocationCreated event() {
        return event(
                primarySourceEventId,
                42L,
                7L,
                "DEMO-GAME"
        );
    }

    private UUID newTestSourceEventId() {
        UUID sourceEventId = UUID.randomUUID();
        testSourceEventIds.add(sourceEventId);
        return sourceEventId;
    }

    private AllocationCreated event(UUID eventId, long allocationId, long gameId, String gameCode) {
        return new AllocationCreated(
                eventId,
                1,
                Instant.parse("2026-08-30T09:10:11Z"),
                allocationId,
                Instant.parse("2026-08-30T09:10:10Z"),
                gameId,
                gameCode,
                "f49f5ba7-53ee-4c8b-95af-29e75831176a"
        );
    }
}
