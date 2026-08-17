package com.shiv.securegkd.authentication;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TokenRequest(
        @NotBlank(message = "Username must not be blank")
        @Size(max = 100, message = "Username must be at most 100 characters")
        String username,

        @NotBlank(message = "Password must not be blank")
        @Size(max = 1024, message = "Password must be at most 1024 characters")
        String password
) {
}
