package com.shiv.securegkd.authentication;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthenticationControllerTests {

    @Test
    void wrongPasswordAndUnknownUsernameReturnIndistinguishableUnauthorizedResponses() {
        TokenService tokenService = mock(TokenService.class);
        TokenRequest wrongPassword = new TokenRequest("known-user", "wrong-password");
        TokenRequest unknownUsername = new TokenRequest("unknown-user", "password");
        when(tokenService.issue(wrongPassword)).thenThrow(new BadCredentialsException("Bad credentials"));
        when(tokenService.issue(unknownUsername)).thenThrow(new BadCredentialsException("Bad credentials"));
        AuthenticationController controller = new AuthenticationController(tokenService);

        ResponseEntity<TokenResponse> wrongPasswordResponse = controller.token(wrongPassword);
        ResponseEntity<TokenResponse> unknownUsernameResponse = controller.token(unknownUsername);

        assertThat(wrongPasswordResponse.getStatusCode().value()).isEqualTo(401);
        assertThat(unknownUsernameResponse).isEqualTo(wrongPasswordResponse);
        assertThat(wrongPasswordResponse.getBody()).isNull();
    }

    @Test
    void authenticationServiceFailuresPropagateInsteadOfBecomingUnauthorized() {
        assertSystemFailurePropagates(
                new AuthenticationServiceException("Authentication backend unavailable")
        );
        assertSystemFailurePropagates(
                new InternalAuthenticationServiceException("Authentication repository failed")
        );
    }

    private void assertSystemFailurePropagates(AuthenticationServiceException failure) {
        TokenService tokenService = mock(TokenService.class);
        TokenRequest request = new TokenRequest("known-user", "password");
        when(tokenService.issue(request)).thenThrow(failure);
        AuthenticationController controller = new AuthenticationController(tokenService);

        assertThatThrownBy(() -> controller.token(request)).isSameAs(failure);
    }
}
