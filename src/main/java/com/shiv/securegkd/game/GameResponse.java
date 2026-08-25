package com.shiv.securegkd.game;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "Persisted Game.")
public record GameResponse(
        @Schema(example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
        Long id,

        @Schema(example = "DEMO-GAME", requiredMode = Schema.RequiredMode.REQUIRED)
        String code,

        @Schema(example = "Demonstration Game", requiredMode = Schema.RequiredMode.REQUIRED)
        String title,

        @Schema(
                example = "2026-01-02T03:04:05Z",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
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
