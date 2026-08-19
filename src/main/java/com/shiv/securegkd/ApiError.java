package com.shiv.securegkd;

import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;

public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
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
