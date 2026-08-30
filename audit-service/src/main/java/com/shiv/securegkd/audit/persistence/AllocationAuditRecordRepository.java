package com.shiv.securegkd.audit.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AllocationAuditRecordRepository extends JpaRepository<AllocationAuditRecord, UUID> {
}
