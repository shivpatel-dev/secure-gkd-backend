package com.shiv.securegkd.allocation;

import java.time.Instant;

public record AllocationResponse(
        String gameCode,
        String keyCode,
        Instant allocatedAt
) {
}
