package com.shiv.securegkd;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;

@Schema(description = "Safe public error response for covered application failures.")
public record ApiError(
        @Schema(
                description = "Time when the error response was created.",
                example = "2026-01-02T03:04:05Z",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        Instant timestamp,
        @Schema(
                description = "HTTP status code for this response.",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        int status,
        @Schema(
                description = "Standard HTTP status phrase.",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        String error,
        @Schema(
                description = "Safe public description of the failure category.",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        String message,
        @Schema(
                description = "Request path where the failure occurred.",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        String path,
        @Schema(
                description = "Bean Validation messages keyed by field, or an empty object.",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        Map<String, String> fieldErrors
) {

    public static ApiError of(HttpStatus status, String message, String path) {
        return of(status, message, path, Map.of());
    }

    public static ApiError of(
            HttpStatus status,
            String message,
            String path,
            Map<String, String> fieldErrors
    ) {
        return new ApiError(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                message,
                path,
                fieldErrors
        );
    }
}
