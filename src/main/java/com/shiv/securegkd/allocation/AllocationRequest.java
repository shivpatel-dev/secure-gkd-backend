package com.shiv.securegkd.allocation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AllocationRequest(
        @NotBlank(message = "idempotencyKey is required")
        @Size(max = 255, message = "idempotencyKey must be at most 255 characters")
        String idempotencyKey
) {
}
