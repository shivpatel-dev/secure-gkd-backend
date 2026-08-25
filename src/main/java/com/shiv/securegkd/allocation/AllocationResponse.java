package com.shiv.securegkd.allocation;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "Allocated game-key result.")
public record AllocationResponse(
        @Schema(example = "DEMO-GAME", requiredMode = Schema.RequiredMode.REQUIRED)
        String gameCode,

        @Schema(
                example = "SYNTHETIC-KEY-NOT-VALID",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        String keyCode,

        @Schema(
                example = "2026-01-02T03:04:05Z",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        Instant allocatedAt
) {
}
