package com.shiv.securegkd.allocation;

import com.shiv.securegkd.game.Game;
import com.shiv.securegkd.game.GameRepository;
import com.shiv.securegkd.gamekey.GameKey;
import com.shiv.securegkd.gamekey.GameKeyRepository;
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

    public AllocationService(
            GameRepository gameRepository,
            GameKeyRepository gameKeyRepository,
            AllocationRepository allocationRepository
    ) {
        this.gameRepository = gameRepository;
        this.gameKeyRepository = gameKeyRepository;
        this.allocationRepository = allocationRepository;
    }

    @Transactional
    public AllocationResponse allocate(String gameCode, AllocationRequest request) {
        Objects.requireNonNull(request, "allocationRequest is required");

        Game game = gameRepository.findByCode(gameCode)
                .orElseThrow(() -> new IllegalArgumentException("Game not found: " + gameCode));

        GameKey gameKey = gameKeyRepository.findAvailableByGame(game, FIRST_AVAILABLE_KEY)
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No available game keys for game: " + gameCode));

        Allocation savedAllocation = allocationRepository.save(new Allocation(gameKey));

        return new AllocationResponse(
                game.getCode(),
                gameKey.getCode(),
                savedAllocation.getAllocatedAt()
        );
    }
}
