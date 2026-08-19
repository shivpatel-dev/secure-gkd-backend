package com.shiv.securegkd.authentication;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shiv.securegkd.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthenticationControllerTests {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private TokenService tokenService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        tokenService = mock(TokenService.class);

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders.standaloneSetup(new AuthenticationController(tokenService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setValidator(validator)
                .build();
    }

    @Test
    void wrongPasswordAndUnknownUsernameReturnIndistinguishableUnauthorizedResponses() throws Exception {
        TokenRequest wrongPassword = new TokenRequest("known-user", "wrong-password");
        TokenRequest unknownUsername = new TokenRequest("unknown-user", "password");
        when(tokenService.issue(wrongPassword))
                .thenThrow(new BadCredentialsException("WRONG_PASSWORD_INTERNAL_MARKER"));
        when(tokenService.issue(unknownUsername))
                .thenThrow(new BadCredentialsException("UNKNOWN_USERNAME_INTERNAL_MARKER"));

        MvcResult wrongPasswordResult = performUnauthorizedRequest(wrongPassword);
        MvcResult unknownUsernameResult = performUnauthorizedRequest(unknownUsername);

        ObjectNode wrongPasswordBody = normalizedErrorBody(wrongPasswordResult);
        ObjectNode unknownUsernameBody = normalizedErrorBody(unknownUsernameResult);
        assertThat(unknownUsernameBody).isEqualTo(wrongPasswordBody);
        assertThat(wrongPasswordResult.getResponse().getContentAsString())
                .doesNotContain("WRONG_PASSWORD_INTERNAL_MARKER")
                .doesNotContain("known-user")
                .doesNotContain("wrong-password");
        assertThat(unknownUsernameResult.getResponse().getContentAsString())
                .doesNotContain("UNKNOWN_USERNAME_INTERNAL_MARKER")
                .doesNotContain("unknown-user")
                .doesNotContain("password");
    }

    @Test
    void authenticationServiceFailureReturnsSafeGenericInternalError() throws Exception {
        TokenRequest request = new TokenRequest("known-user", "password");
        AuthenticationServiceException failure = new AuthenticationServiceException(
                "TEST_ONLY_INTERNAL_DETAIL",
                new IllegalStateException("TEST_ONLY_CAUSE_DETAIL")
        );
        when(tokenService.issue(request)).thenThrow(failure);

        MvcResult result = mockMvc.perform(post("/api/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.error").value("Internal Server Error"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.path").value("/api/auth/token"))
                .andExpect(jsonPath("$.fieldErrors").isEmpty())
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("TEST_ONLY_INTERNAL_DETAIL")
                .doesNotContain("TEST_ONLY_CAUSE_DETAIL")
                .doesNotContain(AuthenticationServiceException.class.getName())
                .doesNotContain(IllegalStateException.class.getName());
    }

    private MvcResult performUnauthorizedRequest(TokenRequest request) throws Exception {
        return mockMvc.perform(post("/api/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("Authentication required"))
                .andExpect(jsonPath("$.path").value("/api/auth/token"))
                .andExpect(jsonPath("$.fieldErrors").isEmpty())
                .andReturn();
    }

    private ObjectNode normalizedErrorBody(MvcResult result) throws Exception {
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(body.required("timestamp").textValue()).isNotBlank();
        ObjectNode normalized = body.deepCopy();
        normalized.remove("timestamp");
        return normalized;
    }
}
