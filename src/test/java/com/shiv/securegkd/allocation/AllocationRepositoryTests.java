package com.shiv.securegkd.allocation;

import com.shiv.securegkd.game.Game;
import com.shiv.securegkd.game.GameRepository;
import com.shiv.securegkd.gamekey.GameKey;
import com.shiv.securegkd.gamekey.GameKeyRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AllocationRepositoryTests {

    @Autowired
    private GameRepository gameRepository;

    @Autowired
    private GameKeyRepository gameKeyRepository;

    @Autowired
    private AllocationRepository allocationRepository;

    @Test
    void savesAllocationForGameKey() {
        deleteAll();
        GameKey gameKey = saveGameKey();

        Allocation savedAllocation = allocationRepository.saveAndFlush(new Allocation(gameKey));

        assertThat(savedAllocation.getId()).isNotNull();
        assertThat(savedAllocation.getAllocatedAt()).isNotNull();
        assertThat(savedAllocation.getGameKey().getId()).isEqualTo(gameKey.getId());
    }

    @Test
    void findsAllocationByGameKey() {
        deleteAll();
        GameKey gameKey = saveGameKey();
        allocationRepository.saveAndFlush(new Allocation(gameKey));

        assertThat(allocationRepository.findByGameKey(gameKey))
                .isPresent()
                .get()
                .extracting(Allocation::getGameKey)
                .extracting(GameKey::getId)
                .isEqualTo(gameKey.getId());
        assertThat(allocationRepository.existsByGameKey(gameKey)).isTrue();
    }

    @Test
    void rejectsDuplicateAllocationForGameKey() {
        deleteAll();
        GameKey gameKey = saveGameKey();
        allocationRepository.saveAndFlush(new Allocation(gameKey));

        assertThatThrownBy(() ->
                allocationRepository.saveAndFlush(new Allocation(gameKey))
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    private GameKey saveGameKey() {
        Game game = gameRepository.saveAndFlush(new Game("GTA5", "Grand Theft Auto V"));
        return gameKeyRepository.saveAndFlush(new GameKey(game, "GTA5-KEY-001"));
    }

    private void deleteAll() {
        allocationRepository.deleteAll();
        gameKeyRepository.deleteAll();
        gameRepository.deleteAll();
    }
}
