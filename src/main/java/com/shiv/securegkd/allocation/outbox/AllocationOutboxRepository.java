package com.shiv.securegkd.allocation.outbox;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AllocationOutboxRepository extends JpaRepository<AllocationOutbox, UUID> {

    List<AllocationOutbox> findByPublishedAtIsNullOrderByOccurredAtAscEventIdAsc(Pageable pageable);

    @Modifying
    @Query("""
            update AllocationOutbox outbox
            set outbox.publishedAt = :publishedAt
            where outbox.eventId = :eventId
              and outbox.publishedAt is null
            """)
    int markPublishedIfPending(
            @Param("eventId") UUID eventId,
            @Param("publishedAt") Instant publishedAt
    );
}
