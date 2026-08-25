package com.shiv.securegkd.allocation;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Idempotent game-key allocation request.")
public record AllocationRequest(
        @Schema(example = "replace-with-a-unique-request-id")
        @NotBlank(message = "idempotencyKey is required")
        @Size(max = 255, message = "idempotencyKey must be at most 255 characters")
        String idempotencyKey
) {
}
