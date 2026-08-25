package com.shiv.securegkd.authentication;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Issued Bearer token and its lifetime.")
public record TokenResponse(
        @Schema(example = "YOUR_BEARER_TOKEN", requiredMode = Schema.RequiredMode.REQUIRED)
        String accessToken,

        @Schema(example = "Bearer", requiredMode = Schema.RequiredMode.REQUIRED)
        String tokenType,

        @Schema(example = "900", requiredMode = Schema.RequiredMode.REQUIRED)
        long expiresInSeconds
) {
}
