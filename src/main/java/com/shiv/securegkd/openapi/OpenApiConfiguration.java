package com.shiv.securegkd.openapi;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@OpenAPIDefinition(
        info = @Info(
                title = "Secure Game Key Distribution API",
                version = "0.0.1-SNAPSHOT",
                description = "HTTP API for authentication, Games, and game-key allocation."
        )
)
@SecurityScheme(
        name = OpenApiConfiguration.BEARER_AUTH_SCHEME,
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT",
        description = "JWT returned by POST /api/auth/token for a pre-existing identity."
)
public class OpenApiConfiguration {

    public static final String BEARER_AUTH_SCHEME = "bearerAuth";
}
