package com.shiv.securegkd.health;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "Current application health.")
public record HealthResponse(
        @Schema(example = "UP", requiredMode = Schema.RequiredMode.REQUIRED)
        String status,

        @Schema(example = "secure-gkd-backend", requiredMode = Schema.RequiredMode.REQUIRED)
        String service,

        @Schema(
                example = "2026-01-02T03:04:05Z",
                format = "date-time",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        String timestamp
) {

    public static HealthResponse up() {
        return new HealthResponse("UP", "secure-gkd-backend", Instant.now().toString());
    }
}
