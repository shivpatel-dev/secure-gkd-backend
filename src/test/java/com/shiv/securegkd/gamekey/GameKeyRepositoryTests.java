package com.shiv.securegkd.gamekey;

import com.shiv.securegkd.allocation.Allocation;
import com.shiv.securegkd.allocation.AllocationRepository;
import com.shiv.securegkd.game.Game;
import com.shiv.securegkd.game.GameRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class GameKeyRepositoryTests {

    @Autowired
    private GameRepository gameRepository;

    @Autowired
    private GameKeyRepository gameKeyRepository;

    @Autowired
    private AllocationRepository allocationRepository;

    @Test
    void savesGameKeyForGame() {
        deleteAll();
        Game game = gameRepository.saveAndFlush(new Game("GTA5", "Grand Theft Auto V"));

        GameKey savedGameKey = gameKeyRepository.saveAndFlush(new GameKey(game, "GTA5-KEY-001"));

        assertThat(savedGameKey.getId()).isNotNull();
        assertThat(savedGameKey.getCreatedAt()).isNotNull();
        assertThat(savedGameKey.getGame().getId()).isEqualTo(game.getId());
        assertThat(savedGameKey.getCode()).isEqualTo("GTA5-KEY-001");
    }

    @Test
    void findsGameKeyByCode() {
        deleteAll();
        Game game = gameRepository.saveAndFlush(new Game("GTA5", "Grand Theft Auto V"));
        gameKeyRepository.saveAndFlush(new GameKey(game, "GTA5-KEY-001"));

        assertThat(gameKeyRepository.findByCode("GTA5-KEY-001"))
                .isPresent()
                .get()
                .extracting(GameKey::getCode)
                .isEqualTo("GTA5-KEY-001");
    }

    @Test
    void rejectsDuplicateGameKeyCode() {
        deleteAll();
        Game game = gameRepository.saveAndFlush(new Game("GTA5", "Grand Theft Auto V"));
        gameKeyRepository.saveAndFlush(new GameKey(game, "GTA5-KEY-001"));

        assertThatThrownBy(() ->
                gameKeyRepository.saveAndFlush(new GameKey(game, "GTA5-KEY-001"))
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void findAvailableByGameExcludesAllocatedGameKeys() {
        deleteAll();
        Game game = gameRepository.saveAndFlush(new Game("GTA5", "Grand Theft Auto V"));
        GameKey allocatedGameKey = gameKeyRepository.saveAndFlush(new GameKey(game, "GTA5-KEY-001"));
        GameKey availableGameKey = gameKeyRepository.saveAndFlush(new GameKey(game, "GTA5-KEY-002"));
        allocationRepository.saveAndFlush(new Allocation(allocatedGameKey));

        List<GameKey> availableGameKeys = gameKeyRepository.findAvailableByGame(game, PageRequest.of(0, 10));

        assertThat(availableGameKeys)
                .extracting(GameKey::getCode)
                .containsExactly("GTA5-KEY-002");
        assertThat(availableGameKeys.get(0).getId()).isEqualTo(availableGameKey.getId());
    }

    @Test
    void findAvailableByGameReturnsFirstAvailableGameKeyWhenLimitedToOne() {
        deleteAll();
        Game game = gameRepository.saveAndFlush(new Game("GTA5", "Grand Theft Auto V"));
        GameKey firstAvailableGameKey = gameKeyRepository.saveAndFlush(new GameKey(game, "GTA5-KEY-001"));
        gameKeyRepository.saveAndFlush(new GameKey(game, "GTA5-KEY-002"));

        List<GameKey> availableGameKeys = gameKeyRepository.findAvailableByGame(game, PageRequest.of(0, 1));

        assertThat(availableGameKeys)
                .extracting(GameKey::getCode)
                .containsExactly("GTA5-KEY-001");
        assertThat(availableGameKeys.get(0).getId()).isEqualTo(firstAvailableGameKey.getId());
    }

    private void deleteAll() {
        allocationRepository.deleteAll();
        gameKeyRepository.deleteAll();
        gameRepository.deleteAll();
    }
}
