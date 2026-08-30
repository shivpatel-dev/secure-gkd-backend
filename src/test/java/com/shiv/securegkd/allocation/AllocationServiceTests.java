package com.shiv.securegkd.allocation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shiv.securegkd.RequestCorrelationFilter;
import com.shiv.securegkd.allocation.event.AllocationCreated;
import com.shiv.securegkd.allocation.outbox.AllocationOutbox;
import com.shiv.securegkd.allocation.outbox.AllocationOutboxRepository;
import com.shiv.securegkd.game.Game;
import com.shiv.securegkd.game.GameRepository;
import com.shiv.securegkd.gamekey.GameKey;
import com.shiv.securegkd.gamekey.GameKeyRepository;
import com.shiv.securegkd.idempotency.IdempotencyRecord;
import com.shiv.securegkd.idempotency.IdempotencyRecordRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AllocationServiceTests {

    private static final String REQUEST_ID = "f49f5ba7-53ee-4c8b-95af-29e75831176a";
    private static final String SERIALIZED_EVENT = "{\"schemaVersion\":1}";

    @Mock
    private GameRepository gameRepository;

    @Mock
    private GameKeyRepository gameKeyRepository;

    @Mock
    private AllocationRepository allocationRepository;

    @Mock
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @Mock
    private AllocationOutboxRepository allocationOutboxRepository;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private AllocationService allocationService;

    @BeforeEach
    void setUpRequestCorrelation() {
        MDC.put(RequestCorrelationFilter.MDC_KEY, REQUEST_ID);
    }

    @AfterEach
    void clearRequestCorrelation() {
        MDC.remove(RequestCorrelationFilter.MDC_KEY);
    }

    @Test
    void allocateSavesAllocationIdempotencyRecordAndOutboxIntentForNewKey() throws Exception {
        Game game = new Game("GTA5", "Grand Theft Auto V");
        GameKey gameKey = new GameKey(game, "GTA5-KEY-001");
        ReflectionTestUtils.setField(game, "id", 7L);

        when(idempotencyRecordRepository.findByIdempotencyKey("request-1")).thenReturn(Optional.empty());
        when(gameRepository.findByCode("GTA5")).thenReturn(Optional.of(game));
        when(gameKeyRepository.findAvailableByGame(eq(game), any(Pageable.class))).thenReturn(List.of(gameKey));
        when(allocationRepository.saveAndFlush(any(Allocation.class))).thenAnswer(invocation -> {
            Allocation allocation = invocation.getArgument(0);
            allocation.prePersist();
            ReflectionTestUtils.setField(allocation, "id", 42L);
            return allocation;
        });
        when(objectMapper.writeValueAsString(any(AllocationCreated.class))).thenReturn(SERIALIZED_EVENT);

        AllocationResponse response = allocationService.allocate("GTA5", new AllocationRequest("request-1"));

        assertThat(response.gameCode()).isEqualTo("GTA5");
        assertThat(response.keyCode()).isEqualTo("GTA5-KEY-001");
        assertThat(response.allocatedAt()).isNotNull();

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(gameKeyRepository).findAvailableByGame(eq(game), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(1);

        ArgumentCaptor<Allocation> allocationCaptor = ArgumentCaptor.forClass(Allocation.class);
        verify(allocationRepository).saveAndFlush(allocationCaptor.capture());
        assertThat(allocationCaptor.getValue().getGameKey()).isSameAs(gameKey);

        ArgumentCaptor<IdempotencyRecord> idempotencyRecordCaptor = ArgumentCaptor.forClass(IdempotencyRecord.class);
        verify(idempotencyRecordRepository).save(idempotencyRecordCaptor.capture());
        assertThat(idempotencyRecordCaptor.getValue().getIdempotencyKey()).isEqualTo("request-1");
        assertThat(idempotencyRecordCaptor.getValue().getAllocation()).isSameAs(allocationCaptor.getValue());

        ArgumentCaptor<AllocationCreated> eventCaptor = ArgumentCaptor.forClass(AllocationCreated.class);
        verify(objectMapper).writeValueAsString(eventCaptor.capture());
        AllocationCreated event = eventCaptor.getValue();
        assertThat(event.schemaVersion()).isEqualTo(AllocationCreated.SCHEMA_VERSION);
        assertThat(event.allocationId()).isEqualTo(42L);
        assertThat(event.allocatedAt()).isEqualTo(allocationCaptor.getValue().getAllocatedAt());
        assertThat(event.gameId()).isEqualTo(7L);
        assertThat(event.gameCode()).isEqualTo("GTA5");
        assertThat(event.requestId()).isEqualTo(REQUEST_ID);

        ArgumentCaptor<AllocationOutbox> outboxCaptor = ArgumentCaptor.forClass(AllocationOutbox.class);
        verify(allocationOutboxRepository).save(outboxCaptor.capture());
        AllocationOutbox outbox = outboxCaptor.getValue();
        assertThat(outbox.getEventId()).isEqualTo(event.eventId());
        assertThat(outbox.getAllocation()).isSameAs(allocationCaptor.getValue());
        assertThat(outbox.getEventType()).isEqualTo(AllocationOutbox.ALLOCATION_CREATED_EVENT_TYPE);
        assertThat(outbox.getSchemaVersion()).isEqualTo(event.schemaVersion());
        assertThat(outbox.getPayload()).isEqualTo(SERIALIZED_EVENT);
        assertThat(outbox.getOccurredAt()).isEqualTo(event.occurredAt());
        assertThat(outbox.getPublishedAt()).isNull();
    }

    @Test
    void allocateReturnsOriginalAllocationForExistingIdempotencyKey() {
        Game game = new Game("GTA5", "Grand Theft Auto V");
        GameKey gameKey = new GameKey(game, "GTA5-KEY-001");
        Allocation allocation = new Allocation(gameKey);
        allocation.prePersist();
        IdempotencyRecord idempotencyRecord = new IdempotencyRecord("request-1", allocation);

        when(idempotencyRecordRepository.findByIdempotencyKey("request-1"))
                .thenReturn(Optional.of(idempotencyRecord));

        AllocationResponse response = allocationService.allocate("GTA5", new AllocationRequest("request-1"));

        assertThat(response.gameCode()).isEqualTo("GTA5");
        assertThat(response.keyCode()).isEqualTo("GTA5-KEY-001");
        assertThat(response.allocatedAt()).isEqualTo(allocation.getAllocatedAt());

        verifyNoInteractions(
                gameRepository,
                gameKeyRepository,
                allocationRepository,
                allocationOutboxRepository,
                objectMapper
        );
        verify(idempotencyRecordRepository, never()).save(any(IdempotencyRecord.class));
    }

    @Test
    void allocateFailsWhenGameDoesNotExist() {
        when(idempotencyRecordRepository.findByIdempotencyKey("request-1")).thenReturn(Optional.empty());
        when(gameRepository.findByCode("UNKNOWN")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> allocationService.allocate("UNKNOWN", new AllocationRequest("request-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Game not found: UNKNOWN");

        verifyNoInteractions(gameKeyRepository, allocationRepository);
        verify(idempotencyRecordRepository, never()).save(any(IdempotencyRecord.class));
        verifyNoInteractions(allocationOutboxRepository, objectMapper);
    }

    @Test
    void allocateFailsWhenNoGameKeyIsAvailable() {
        Game game = new Game("GTA5", "Grand Theft Auto V");

        when(idempotencyRecordRepository.findByIdempotencyKey("request-1")).thenReturn(Optional.empty());
        when(gameRepository.findByCode("GTA5")).thenReturn(Optional.of(game));
        when(gameKeyRepository.findAvailableByGame(eq(game), any(Pageable.class))).thenReturn(List.of());

        assertThatThrownBy(() -> allocationService.allocate("GTA5", new AllocationRequest("request-1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No available game keys for game: GTA5");

        verify(allocationRepository, never()).saveAndFlush(any(Allocation.class));
        verify(idempotencyRecordRepository, never()).save(any(IdempotencyRecord.class));
        verifyNoInteractions(allocationOutboxRepository, objectMapper);
    }

    @Test
    void allocateFailsWhenSelectedGameKeyIsAlreadyAllocatedByConcurrentRequest() {
        Game game = new Game("GTA5", "Grand Theft Auto V");
        GameKey gameKey = new GameKey(game, "GTA5-KEY-001");

        when(idempotencyRecordRepository.findByIdempotencyKey("request-1")).thenReturn(Optional.empty());
        when(gameRepository.findByCode("GTA5")).thenReturn(Optional.of(game));
        when(gameKeyRepository.findAvailableByGame(eq(game), any(Pageable.class))).thenReturn(List.of(gameKey));
        when(allocationRepository.saveAndFlush(any(Allocation.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate game_key_id"));

        assertThatThrownBy(() -> allocationService.allocate("GTA5", new AllocationRequest("request-1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Selected game key is no longer available for game: GTA5")
                .hasCauseInstanceOf(DataIntegrityViolationException.class);

        verify(idempotencyRecordRepository, never()).save(any(IdempotencyRecord.class));
        verifyNoInteractions(allocationOutboxRepository, objectMapper);
    }

    @Test
    void allocateFailsRatherThanIgnoringEventSerializationFailure() throws Exception {
        Game game = new Game("GTA5", "Grand Theft Auto V");
        GameKey gameKey = new GameKey(game, "GTA5-KEY-001");
        ReflectionTestUtils.setField(game, "id", 7L);
        JsonProcessingException forcedFailure = new JsonProcessingException("forced serialization failure") {
        };

        when(idempotencyRecordRepository.findByIdempotencyKey("request-1")).thenReturn(Optional.empty());
        when(gameRepository.findByCode("GTA5")).thenReturn(Optional.of(game));
        when(gameKeyRepository.findAvailableByGame(eq(game), any(Pageable.class))).thenReturn(List.of(gameKey));
        when(allocationRepository.saveAndFlush(any(Allocation.class))).thenAnswer(invocation -> {
            Allocation allocation = invocation.getArgument(0);
            allocation.prePersist();
            ReflectionTestUtils.setField(allocation, "id", 42L);
            return allocation;
        });
        when(objectMapper.writeValueAsString(any(AllocationCreated.class))).thenThrow(forcedFailure);

        assertThatThrownBy(() -> allocationService.allocate("GTA5", new AllocationRequest("request-1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Failed to serialize AllocationCreated event")
                .hasCause(forcedFailure);

        verify(idempotencyRecordRepository).save(any(IdempotencyRecord.class));
        verify(allocationOutboxRepository, never()).save(any(AllocationOutbox.class));
    }
}
