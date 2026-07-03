package com.shiv.securegkd.allocation;

import com.shiv.securegkd.game.Game;
import com.shiv.securegkd.game.GameRepository;
import com.shiv.securegkd.gamekey.GameKey;
import com.shiv.securegkd.gamekey.GameKeyRepository;
import com.shiv.securegkd.idempotency.IdempotencyRecord;
import com.shiv.securegkd.idempotency.IdempotencyRecordRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
public class AllocationService {

    private static final PageRequest FIRST_AVAILABLE_KEY = PageRequest.of(0, 1);

    private final GameRepository gameRepository;
    private final GameKeyRepository gameKeyRepository;
    private final AllocationRepository allocationRepository;
    private final IdempotencyRecordRepository idempotencyRecordRepository;

    public AllocationService(
            GameRepository gameRepository,
            GameKeyRepository gameKeyRepository,
            AllocationRepository allocationRepository,
            IdempotencyRecordRepository idempotencyRecordRepository
    ) {
        this.gameRepository = gameRepository;
        this.gameKeyRepository = gameKeyRepository;
        this.allocationRepository = allocationRepository;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
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

        Allocation savedAllocation = allocationRepository.save(new Allocation(gameKey));
        idempotencyRecordRepository.save(new IdempotencyRecord(request.idempotencyKey(), savedAllocation));

        return toResponse(savedAllocation);
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
