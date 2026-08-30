package com.shiv.securegkd.allocation.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public class AllocationOutboxPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(AllocationOutboxPublisher.class);

    private final AllocationOutboxRepository allocationOutboxRepository;
    private final AllocationOutboxPublicationStatusService publicationStatusService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final AllocationOutboxPublisherProperties properties;

    AllocationOutboxPublisher(
            AllocationOutboxRepository allocationOutboxRepository,
            AllocationOutboxPublicationStatusService publicationStatusService,
            KafkaTemplate<String, String> kafkaTemplate,
            AllocationOutboxPublisherProperties properties
    ) {
        this.allocationOutboxRepository = allocationOutboxRepository;
        this.publicationStatusService = publicationStatusService;
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${secure-gkd.kafka.publisher.poll-interval}",
            initialDelayString = "${secure-gkd.kafka.publisher.poll-interval}"
    )
    public void publishPending() {
        List<AllocationOutbox> pending = allocationOutboxRepository
                .findByPublishedAtIsNullOrderByOccurredAtAscEventIdAsc(
                        PageRequest.of(0, properties.getBatchSize())
                );

        for (AllocationOutbox outbox : pending) {
            if (!publish(outbox)) {
                LOGGER.info(
                        "allocation_outbox_batch_stopped eventId={} topic={} retry=pending",
                        outbox.getEventId(),
                        properties.getTopic()
                );
                return;
            }
        }
    }

    private boolean publish(AllocationOutbox outbox) {
        try {
            kafkaTemplate.send(
                    properties.getTopic(),
                    outbox.getEventId().toString(),
                    outbox.getPayload()
            ).get(
                    properties.getAcknowledgementTimeout().toMillis(),
                    TimeUnit.MILLISECONDS
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logPublicationFailure(outbox, exception);
            return false;
        } catch (ExecutionException | RuntimeException | java.util.concurrent.TimeoutException exception) {
            logPublicationFailure(outbox, exception);
            return false;
        }

        try {
            boolean timestampRecorded = publicationStatusService.markPublished(
                    outbox.getEventId(),
                    Instant.now().truncatedTo(ChronoUnit.MICROS)
            );
            LOGGER.info(
                    "allocation_outbox_publication_acknowledged eventId={} topic={} publicationStatus={}",
                    outbox.getEventId(),
                    properties.getTopic(),
                    timestampRecorded ? "recorded" : "already-recorded"
            );
            return true;
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "allocation_outbox_status_update_failed eventId={} topic={} failureType={} retry=pending",
                    outbox.getEventId(),
                    properties.getTopic(),
                    exception.getClass().getSimpleName()
            );
            return false;
        }
    }

    private void logPublicationFailure(AllocationOutbox outbox, Exception exception) {
        Throwable classified = exception instanceof ExecutionException && exception.getCause() != null
                ? exception.getCause()
                : exception;
        LOGGER.warn(
                "allocation_outbox_publication_failed eventId={} topic={} failureType={} retry=pending",
                outbox.getEventId(),
                properties.getTopic(),
                classified.getClass().getSimpleName()
        );
    }
}
