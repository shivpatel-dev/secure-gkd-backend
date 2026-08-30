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

    private final AllocationAuditRecordRepository repository;
    private final Clock clock;

    public AllocationAuditPersistenceService(AllocationAuditRecordRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public AllocationAuditRecord persist(AllocationCreated event) {
        AllocationAuditRecord record = new AllocationAuditRecord(
                UUID.randomUUID(),
                event,
                Instant.now(clock).truncatedTo(ChronoUnit.MICROS)
        );
        return repository.saveAndFlush(record);
    }
}
