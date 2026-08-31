package com.shiv.securegkd.audit.persistence;

import com.shiv.securegkd.audit.event.AllocationCreated;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
public class AllocationAuditPersistenceService {

    private final AllocationAuditRecordWriter recordWriter;
    private final Clock clock;

    public AllocationAuditPersistenceService(AllocationAuditRecordWriter recordWriter, Clock clock) {
        this.recordWriter = recordWriter;
        this.clock = clock;
    }

    @Transactional
    public AllocationAuditPersistenceOutcome persist(AllocationCreated event) {
        AllocationAuditRecord record = new AllocationAuditRecord(
                UUID.randomUUID(),
                event,
                Instant.now(clock).truncatedTo(ChronoUnit.MICROS)
        );
        int insertedRows = recordWriter.insertIfSourceEventUnseen(record);
        if (insertedRows == 1) {
            return AllocationAuditPersistenceOutcome.PERSISTED;
        }
        if (insertedRows == 0) {
            return AllocationAuditPersistenceOutcome.DUPLICATE;
        }
        throw new IllegalStateException("Unexpected allocation audit insert result");
    }
}
