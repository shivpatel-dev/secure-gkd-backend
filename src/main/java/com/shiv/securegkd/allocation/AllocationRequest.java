package com.shiv.securegkd.allocation;

import jakarta.validation.constraints.NotBlank;

public record AllocationRequest(
        @NotBlank(message = "idempotencyKey is required")
        String idempotencyKey
) {
}
