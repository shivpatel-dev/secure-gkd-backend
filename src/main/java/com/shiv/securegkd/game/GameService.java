package com.shiv.securegkd.game;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GameService {

    private final GameRepository gameRepository;

    public GameService(GameRepository gameRepository) {
        this.gameRepository = gameRepository;
    }

    @Transactional
    public Game createGame(String code, String title) {
        Game game = new Game(code, title);
        return gameRepository.save(game);
    }

    @Transactional(readOnly = true)
    public Optional<Game> findByCode(String code) {
        return gameRepository.findByCode(code);
    }
}