package com.shiv.securegkd.game;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Game to create.")
public record CreateGameRequest(
        @Schema(example = "DEMO-GAME")
        @NotBlank(message = "Game code must not be blank")
        @Size(max = 50, message = "Game code must be at most 50 characters")
        String code,

        @Schema(example = "Demonstration Game")
        @NotBlank(message = "Game title must not be blank")
        @Size(max = 255, message = "Game title must be at most 255 characters")
        String title
) {
}
