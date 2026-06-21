package com.shiv.securegkd.game;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GameServiceTests {

    @Mock
    private GameRepository gameRepository;

    @InjectMocks
    private GameService gameService;

    @Test
    void createGameSavesThroughRepository() {
        Game savedGame = new Game("GTA5", "Grand Theft Auto V");
        when(gameRepository.save(any(Game.class))).thenReturn(savedGame);

        Game createdGame = gameService.createGame("GTA5", "Grand Theft Auto V");

        assertThat(createdGame).isSameAs(savedGame);

        ArgumentCaptor<Game> gameCaptor = ArgumentCaptor.forClass(Game.class);
        verify(gameRepository).save(gameCaptor.capture());

        assertThat(gameCaptor.getValue().getCode()).isEqualTo("GTA5");
        assertThat(gameCaptor.getValue().getTitle()).isEqualTo("Grand Theft Auto V");
    }

    @Test
    void findByCodeDelegatesToRepository() {
        Game game = new Game("GTA5", "Grand Theft Auto V");
        when(gameRepository.findByCode("GTA5")).thenReturn(Optional.of(game));

        Optional<Game> foundGame = gameService.findByCode("GTA5");

        assertThat(foundGame).containsSame(game);
        verify(gameRepository).findByCode("GTA5");
    }
}
