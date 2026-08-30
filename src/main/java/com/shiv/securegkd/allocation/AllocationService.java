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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

@Service
public class AllocationService {

    private static final PageRequest FIRST_AVAILABLE_KEY = PageRequest.of(0, 1);

    private final GameRepository gameRepository;
    private final GameKeyRepository gameKeyRepository;
    private final AllocationRepository allocationRepository;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final AllocationOutboxRepository allocationOutboxRepository;
    private final ObjectMapper objectMapper;

    public AllocationService(
            GameRepository gameRepository,
            GameKeyRepository gameKeyRepository,
            AllocationRepository allocationRepository,
            IdempotencyRecordRepository idempotencyRecordRepository,
            AllocationOutboxRepository allocationOutboxRepository,
            ObjectMapper objectMapper
    ) {
        this.gameRepository = gameRepository;
        this.gameKeyRepository = gameKeyRepository;
        this.allocationRepository = allocationRepository;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.allocationOutboxRepository = allocationOutboxRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public AllocationResponse allocate(String gameCode, AllocationRequest request) {
        Objects.requireNonNull(request, "allocationRequest is required");

        return idempotencyRecordRepository.findByIdempotencyKey(request.idempotencyKey())
                .map(IdempotencyRecord::getAllocation)
                .map(this::toResponse)
                .orElseGet(() -> allocateNewGameKey(gameCode, request));
    }

    private AllocationResponse allocateNewGameKey(String gameCode, AllocationRequest request) {
        Game game = gameRepository.findByCode(gameCode)
                .orElseThrow(() -> new IllegalArgumentException("Game not found: " + gameCode));

        GameKey gameKey = gameKeyRepository.findAvailableByGame(game, FIRST_AVAILABLE_KEY)
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No available game keys for game: " + gameCode));

        Allocation savedAllocation = saveAllocation(gameCode, gameKey);
        idempotencyRecordRepository.save(new IdempotencyRecord(request.idempotencyKey(), savedAllocation));
        persistAllocationCreated(savedAllocation);

        return toResponse(savedAllocation);
    }

    private void persistAllocationCreated(Allocation allocation) {
        Game game = allocation.getGameKey().getGame();
        UUID eventId = UUID.randomUUID();
        Instant occurredAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String requestId = Objects.requireNonNull(
                RequestCorrelationFilter.currentRequestId(),
                "requestId is required"
        );
        AllocationCreated event = new AllocationCreated(
                eventId,
                AllocationCreated.SCHEMA_VERSION,
                occurredAt,
                allocation.getId(),
                allocation.getAllocatedAt(),
                game.getId(),
                game.getCode(),
                requestId
        );

        allocationOutboxRepository.save(new AllocationOutbox(
                event.eventId(),
                allocation,
                AllocationOutbox.ALLOCATION_CREATED_EVENT_TYPE,
                event.schemaVersion(),
                serialize(event),
                event.occurredAt()
        ));
    }

    private String serialize(AllocationCreated event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize AllocationCreated event", exception);
        }
    }

    private Allocation saveAllocation(String gameCode, GameKey gameKey) {
        try {
            return allocationRepository.saveAndFlush(new Allocation(gameKey));
        } catch (DataIntegrityViolationException exception) {
            throw new IllegalStateException("Selected game key is no longer available for game: " + gameCode, exception);
        }
    }

    private AllocationResponse toResponse(Allocation allocation) {
        GameKey gameKey = allocation.getGameKey();
        Game game = gameKey.getGame();
        return new AllocationResponse(
                game.getCode(),
                gameKey.getCode(),
                allocation.getAllocatedAt()
        );
    }
}
