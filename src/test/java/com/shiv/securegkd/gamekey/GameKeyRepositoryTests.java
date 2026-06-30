package com.shiv.securegkd.gamekey;

import com.shiv.securegkd.game.Game;
import com.shiv.securegkd.game.GameRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

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

    private void deleteAll() {
        gameKeyRepository.deleteAll();
        gameRepository.deleteAll();
    }
}
