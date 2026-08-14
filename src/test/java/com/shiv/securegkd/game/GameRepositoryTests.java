package com.shiv.securegkd.game;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class GameRepositoryTests {

    @Autowired
    private GameRepository gameRepository;

    @Test
    void savesAndFindsGameByCode() {
        gameRepository.deleteAll();

        Game savedGame = gameRepository.saveAndFlush(new Game("GTA5", "Grand Theft Auto V"));

        assertThat(savedGame.getId()).isNotNull();
        assertThat(savedGame.getCreatedAt()).isNotNull();

        assertThat(gameRepository.findByCode("GTA5"))
                .isPresent()
                .get()
                .extracting(Game::getTitle)
                .isEqualTo("Grand Theft Auto V");
    }

    @Test
    void rejectsDuplicateGameCode() {
        gameRepository.deleteAll();

        gameRepository.saveAndFlush(new Game("GTA5", "Grand Theft Auto V"));

        assertThatThrownBy(() ->
                gameRepository.saveAndFlush(new Game("GTA5", "Duplicate Game"))
        ).isInstanceOf(DataIntegrityViolationException.class);
    }
}
