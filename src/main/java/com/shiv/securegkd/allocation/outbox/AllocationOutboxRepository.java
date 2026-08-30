package com.shiv.securegkd.allocation.outbox;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AllocationOutboxRepository extends JpaRepository<AllocationOutbox, UUID> {
}
