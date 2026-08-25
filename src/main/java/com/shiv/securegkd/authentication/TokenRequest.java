package com.shiv.securegkd.authentication;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Credentials for a pre-existing application identity.")
public record TokenRequest(
        @Schema(example = "YOUR_USERNAME")
        @NotBlank(message = "Username must not be blank")
        @Size(max = 100, message = "Username must be at most 100 characters")
        String username,

        @Schema(example = "YOUR_PASSWORD", format = "password")
        @NotBlank(message = "Password must not be blank")
        @Size(max = 1024, message = "Password must be at most 1024 characters")
        String password
) {
}
