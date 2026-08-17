package com.shiv.securegkd.authentication;

import java.time.Duration;
import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("secure-gkd.security.jwt")
public record JwtProperties(
        String signingKeyBase64,
        Duration accessTokenLifetime
) {
    private static final int MINIMUM_KEY_BYTES = 32;
    private static final Duration MAXIMUM_ACCESS_TOKEN_LIFETIME = Duration.ofHours(24);

    public JwtProperties {
        if (signingKeyBase64 == null || signingKeyBase64.isBlank()) {
            throw new IllegalArgumentException("JWT signing key must be configured");
        }

        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(signingKeyBase64);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("JWT signing key must be valid Base64", exception);
        }
        if (keyBytes.length < MINIMUM_KEY_BYTES) {
            throw new IllegalArgumentException("JWT signing key must contain at least 32 bytes");
        }

        if (accessTokenLifetime == null
                || accessTokenLifetime.compareTo(Duration.ofSeconds(1)) < 0
                || accessTokenLifetime.compareTo(MAXIMUM_ACCESS_TOKEN_LIFETIME) > 0
                || accessTokenLifetime.getNano() != 0) {
            throw new IllegalArgumentException(
                    "JWT access-token lifetime must be a whole number of seconds between 1 second and 24 hours"
            );
        }
    }
}
