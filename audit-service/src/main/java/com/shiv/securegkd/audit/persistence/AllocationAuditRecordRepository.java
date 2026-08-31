package com.shiv.securegkd.audit.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AllocationAuditRecordRepository extends JpaRepository<AllocationAuditRecord, UUID> {

    Optional<AllocationAuditRecord> findBySourceEventId(UUID sourceEventId);

    List<AllocationAuditRecord> findAllBySourceEventIdIn(Collection<UUID> sourceEventIds);
}
