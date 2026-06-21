package com.shiv.securegkd.game;

import java.time.Instant;

public record GameResponse(
        Long id,
        String code,
        String title,
        Instant createdAt
) {

    public static GameResponse from(Game game) {
        return new GameResponse(
                game.getId(),
                game.getCode(),
                game.getTitle(),
                game.getCreatedAt()
        );
    }
}