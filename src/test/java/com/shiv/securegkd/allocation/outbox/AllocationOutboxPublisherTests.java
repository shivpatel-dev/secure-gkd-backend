package com.shiv.securegkd.allocation.outbox;

import com.shiv.securegkd.allocation.Allocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AllocationOutboxPublisherTests {

    private static final String TOPIC = "secure-gkd.allocation-created";
    private static final String SECRET_GAME_KEY = "SECRET-GAME-KEY-MUST-NOT-BE-PUBLISHED";

    private AllocationOutboxRepository allocationOutboxRepository;
    private AllocationOutboxPublicationStatusService publicationStatusService;
    private KafkaTemplate<String, String> kafkaTemplate;
    private AllocationOutboxPublisherProperties properties;
    private AllocationOutboxPublisher publisher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        allocationOutboxRepository = mock(AllocationOutboxRepository.class);
        publicationStatusService = mock(AllocationOutboxPublicationStatusService.class);
        kafkaTemplate = mock(KafkaTemplate.class);
        properties = new AllocationOutboxPublisherProperties();
        properties.setTopic(TOPIC);
        properties.setBatchSize(2);
        properties.setAcknowledgementTimeout(Duration.ofMillis(25));
        publisher = new AllocationOutboxPublisher(
                allocationOutboxRepository,
                publicationStatusService,
                kafkaTemplate,
                properties
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishesExactStoredPayloadWithStableEventKeyAndMarksOnlyAfterAcknowledgement() throws Exception {
        AllocationOutbox outbox = outbox(1, "{\"eventId\":\"stored\",\"gameCode\":\"SAFE\"}");
        CompletableFuture<SendResult<String, String>> acknowledgement = mock(CompletableFuture.class);
        SendResult<String, String> sendResult = mock(SendResult.class);
        when(allocationOutboxRepository.findByPublishedAtIsNullOrderByOccurredAtAscEventIdAsc(any()))
                .thenReturn(List.of(outbox));
        when(kafkaTemplate.send(TOPIC, outbox.getEventId().toString(), outbox.getPayload()))
                .thenReturn(acknowledgement);
        when(acknowledgement.get(anyLong(), any(java.util.concurrent.TimeUnit.class)))
                .thenAnswer(invocation -> {
                    verifyNoInteractions(publicationStatusService);
                    return sendResult;
                });
        when(publicationStatusService.markPublished(eq(outbox.getEventId()), any(Instant.class)))
                .thenReturn(true);

        publisher.publishPending();

        InOrder publicationOrder = inOrder(kafkaTemplate, publicationStatusService);
        publicationOrder.verify(kafkaTemplate).send(
                TOPIC,
                outbox.getEventId().toString(),
                outbox.getPayload()
        );
        publicationOrder.verify(publicationStatusService).markPublished(
                eq(outbox.getEventId()),
                any(Instant.class)
        );
        assertThat(outbox.getPayload()).doesNotContain(SECRET_GAME_KEY);
    }

    @Test
    void requestsOnlyTheConfiguredBoundedBatch() {
        when(allocationOutboxRepository.findByPublishedAtIsNullOrderByOccurredAtAscEventIdAsc(any()))
                .thenReturn(List.of());
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);

        publisher.publishPending();

        verify(allocationOutboxRepository)
                .findByPublishedAtIsNullOrderByOccurredAtAscEventIdAsc(pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isZero();
        assertThat(pageable.getValue().getPageSize()).isEqualTo(2);
    }

    @Test
    void failedSendLeavesCurrentAndLaterRowsPendingAndStopsTheBatch() {
        AllocationOutbox first = outbox(1, "first-payload");
        AllocationOutbox second = outbox(2, "second-payload");
        when(allocationOutboxRepository.findByPublishedAtIsNullOrderByOccurredAtAscEventIdAsc(any()))
                .thenReturn(List.of(first, second));
        when(kafkaTemplate.send(TOPIC, first.getEventId().toString(), first.getPayload()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("synthetic failure")));

        publisher.publishPending();

        verify(kafkaTemplate, never()).send(TOPIC, second.getEventId().toString(), second.getPayload());
        verifyNoInteractions(publicationStatusService);
    }

    @Test
    void timedOutEventIsRetriedLaterWithTheSameKeyAndPayload() {
        AllocationOutbox outbox = outbox(1, "stable-payload");
        CompletableFuture<SendResult<String, String>> timedOut = new CompletableFuture<>();
        CompletableFuture<SendResult<String, String>> acknowledged = CompletableFuture.completedFuture(
                mock(SendResult.class)
        );
        when(allocationOutboxRepository.findByPublishedAtIsNullOrderByOccurredAtAscEventIdAsc(any()))
                .thenReturn(List.of(outbox));
        when(kafkaTemplate.send(TOPIC, outbox.getEventId().toString(), outbox.getPayload()))
                .thenReturn(timedOut, acknowledged);
        when(publicationStatusService.markPublished(eq(outbox.getEventId()), any(Instant.class)))
                .thenReturn(true);

        publisher.publishPending();
        publisher.publishPending();

        verify(kafkaTemplate, times(2)).send(
                TOPIC,
                outbox.getEventId().toString(),
                outbox.getPayload()
        );
        verify(publicationStatusService).markPublished(eq(outbox.getEventId()), any(Instant.class));
    }

    @Test
    void statusUpdateFailureLeavesAcknowledgedEventEligibleForDuplicatePublication() {
        AllocationOutbox outbox = outbox(1, "stable-payload");
        when(allocationOutboxRepository.findByPublishedAtIsNullOrderByOccurredAtAscEventIdAsc(any()))
                .thenReturn(List.of(outbox));
        when(kafkaTemplate.send(TOPIC, outbox.getEventId().toString(), outbox.getPayload()))
                .thenReturn(
                        CompletableFuture.completedFuture(mock(SendResult.class)),
                        CompletableFuture.completedFuture(mock(SendResult.class))
                );
        when(publicationStatusService.markPublished(eq(outbox.getEventId()), any(Instant.class)))
                .thenThrow(new IllegalStateException("synthetic database failure"))
                .thenReturn(true);

        publisher.publishPending();
        publisher.publishPending();

        verify(kafkaTemplate, times(2)).send(
                TOPIC,
                outbox.getEventId().toString(),
                outbox.getPayload()
        );
        verify(publicationStatusService, times(2)).markPublished(
                eq(outbox.getEventId()),
                any(Instant.class)
        );
    }

    private AllocationOutbox outbox(int suffix, String payload) {
        return new AllocationOutbox(
                UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix)),
                mock(Allocation.class),
                AllocationOutbox.ALLOCATION_CREATED_EVENT_TYPE,
                1,
                payload,
                Instant.parse("2026-01-02T03:04:05Z").plusSeconds(suffix)
        );
    }
}
