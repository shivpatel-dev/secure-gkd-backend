package com.shiv.securegkd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shiv.securegkd.allocation.AllocationController;
import com.shiv.securegkd.allocation.AllocationRequest;
import com.shiv.securegkd.allocation.AllocationResponse;
import com.shiv.securegkd.allocation.AllocationService;
import com.shiv.securegkd.authentication.AuthenticationController;
import com.shiv.securegkd.authentication.AuthenticationIdentityRepository;
import com.shiv.securegkd.authentication.TokenService;
import com.shiv.securegkd.game.GameController;
import com.shiv.securegkd.game.GameService;
import com.shiv.securegkd.security.SecurityConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({
        AuthenticationController.class,
        GameController.class,
        AllocationController.class
})
@Import({SecurityConfiguration.class, RequestCorrelationFilter.class})
class JsonRequestBodySizeTests {

    private static final String OVERSIZED_MARKER = "TEST_ONLY_OVERSIZED_BODY_MARKER";
    private static final Instant ALLOCATED_AT = Instant.parse("2026-01-02T03:04:05Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private TokenService tokenService;

    @MockitoBean
    private GameService gameService;

    @MockitoBean
    private AllocationService allocationService;

    @MockitoBean
    private AuthenticationIdentityRepository authenticationIdentityRepository;

    @Test
    void jsonBodyAtByteLimitContinuesThroughAuthorizedAllocationHandling() throws Exception {
        AllocationRequest request = new AllocationRequest("boundary-request");
        AllocationResponse response = new AllocationResponse("GTA5", "GTA5-KEY-001", ALLOCATED_AT);
        when(allocationService.allocate("GTA5", request)).thenReturn(response);

        byte[] body = paddedToLength(
                objectMapper.writeValueAsBytes(request),
                JsonRequestBodySizeFilter.MAX_JSON_BODY_BYTES
        );

        mockMvc.perform(post("/api/games/GTA5/allocations")
                        .with(user("test-user").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.gameCode").value("GTA5"))
                .andExpect(jsonPath("$.keyCode").value("GTA5-KEY-001"));

        verify(allocationService).allocate("GTA5", request);
    }

    @Test
    void oversizedJsonReturnsSafeStructuredPayloadTooLargeFromActualBodyBytes() throws Exception {
        byte[] body = oversizedTokenBody();

        MvcResult result = mockMvc.perform(post("/api/auth/token")
                        .header(HttpHeaders.CONTENT_LENGTH, "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string(RequestCorrelationFilter.HEADER_NAME, notNullValue()))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.status").value(413))
                .andExpect(jsonPath("$.error").value("Payload Too Large"))
                .andExpect(jsonPath("$.message").value("Request body too large"))
                .andExpect(jsonPath("$.path").value("/api/auth/token"))
                .andExpect(jsonPath("$.fieldErrors").isEmpty())
                .andReturn();

        assertThat(body.length).isGreaterThan(JsonRequestBodySizeFilter.MAX_JSON_BODY_BYTES);
        assertThat(result.getResponse().getHeader(RequestCorrelationFilter.HEADER_NAME)).isNotBlank();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(OVERSIZED_MARKER);
        verifyNoInteractions(tokenService);
    }

    @Test
    void bodyLimitCoversGameAndAllocationOperationsBeforeBusinessLogic() throws Exception {
        byte[] oversizedBody = oversizedJsonObject();

        mockMvc.perform(post("/api/games")
                        .with(user("test-admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oversizedBody))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.path").value("/api/games"));

        mockMvc.perform(post("/api/games/GTA5/allocations")
                        .with(user("test-user").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oversizedBody))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.path").value("/api/games/GTA5/allocations"));

        verifyNoInteractions(gameService, allocationService);
    }

    @Test
    void authenticationAndAuthorizationPrecedeBodySizeHandlingOnProtectedRoutes() throws Exception {
        byte[] oversizedBody = oversizedJsonObject();

        mockMvc.perform(post("/api/games/GTA5/allocations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oversizedBody))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/games/GTA5/allocations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer malformed-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oversizedBody))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/games")
                        .with(user("test-user").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oversizedBody))
                .andExpect(status().isForbidden());

        verifyNoInteractions(gameService, allocationService);
    }

    @Test
    void malformedJsonWithinLimitRetainsNormalMvcBadRequestBehavior() throws Exception {
        mockMvc.perform(post("/api/games")
                        .with(user("test-admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"GTA5\",\"title\":"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(gameService);
    }

    private byte[] oversizedTokenBody() {
        String prefix = "{\"username\":\"user\",\"password\":\"" + OVERSIZED_MARKER;
        String suffix = "\"}";
        return (prefix
                + "X".repeat(JsonRequestBodySizeFilter.MAX_JSON_BODY_BYTES)
                + suffix).getBytes(StandardCharsets.UTF_8);
    }

    private byte[] oversizedJsonObject() {
        String body = "{\"marker\":\""
                + OVERSIZED_MARKER
                + "X".repeat(JsonRequestBodySizeFilter.MAX_JSON_BODY_BYTES)
                + "\"}";
        return body.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] paddedToLength(byte[] json, int length) {
        byte[] padded = Arrays.copyOf(json, length);
        Arrays.fill(padded, json.length, padded.length, (byte) ' ');
        return padded;
    }
}
