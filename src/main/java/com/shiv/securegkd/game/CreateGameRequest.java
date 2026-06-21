package com.shiv.securegkd.game;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateGameRequest(
        @NotBlank(message = "Game code must not be blank")
        @Size(max = 50, message = "Game code must be at most 50 characters")
        String code,

        @NotBlank(message = "Game title must not be blank")
        @Size(max = 255, message = "Game title must be at most 255 characters")
        String title
) {
}