package com.shiv.securegkd.game;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/games")
public class GameController {

    private final GameService gameService;

    public GameController(GameService gameService) {
        this.gameService = gameService;
    }

    @PostMapping
    public ResponseEntity<Game> createGame(@RequestBody CreateGameRequest request) {
        Game createdGame = gameService.createGame(request.code(), request.title());
        return ResponseEntity.status(HttpStatus.CREATED).body(createdGame);
    }

    @GetMapping("/{code}")
    public ResponseEntity<Game> getGameByCode(@PathVariable String code) {
        return gameService.findByCode(code)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    private record CreateGameRequest(String code, String title) {
    }
}