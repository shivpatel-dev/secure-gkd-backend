package com.shiv.securegkd.authentication;

public record TokenResponse(
        String accessToken,
        String tokenType,
        long expiresInSeconds
) {
}
