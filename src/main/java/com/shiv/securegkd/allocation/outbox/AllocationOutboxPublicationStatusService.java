package com.shiv.securegkd.allocation.outbox;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class AllocationOutboxPublicationStatusService {

    private final AllocationOutboxRepository allocationOutboxRepository;

    public AllocationOutboxPublicationStatusService(
            AllocationOutboxRepository allocationOutboxRepository
    ) {
        this.allocationOutboxRepository = allocationOutboxRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markPublished(UUID eventId, Instant publishedAt) {
        return allocationOutboxRepository.markPublishedIfPending(eventId, publishedAt) == 1;
    }
}
