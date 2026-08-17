package com.shiv.securegkd.authentication;

import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtPropertiesTests {

    @Test
    void acceptsAValidExternalizedKeyAndBoundedLifetime() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);

        assertThatCode(() -> new JwtProperties(key, Duration.ofMinutes(15)))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingMalformedOrInadequateSigningKeys() {
        assertThatThrownBy(() -> new JwtProperties(null, Duration.ofMinutes(15)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("JWT signing key must be configured");
        assertThatThrownBy(() -> new JwtProperties("not-valid-base64!", Duration.ofMinutes(15)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("JWT signing key must be valid Base64");
        assertThatThrownBy(() -> new JwtProperties(
                Base64.getEncoder().encodeToString(new byte[31]),
                Duration.ofMinutes(15)
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("JWT signing key must contain at least 32 bytes");
    }

    @Test
    void rejectsUnboundedOrSubsecondLifetimes() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);

        assertThatThrownBy(() -> new JwtProperties(key, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 second and 24 hours");
        assertThatThrownBy(() -> new JwtProperties(key, Duration.ofHours(25)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 second and 24 hours");
        assertThatThrownBy(() -> new JwtProperties(key, Duration.ofMillis(1500)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whole number of seconds");
    }
}
