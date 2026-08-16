package com.shiv.securegkd.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shiv.securegkd.allocation.AllocationController;
import com.shiv.securegkd.allocation.AllocationRequest;
import com.shiv.securegkd.allocation.AllocationResponse;
import com.shiv.securegkd.allocation.AllocationService;
import com.shiv.securegkd.game.Game;
import com.shiv.securegkd.game.GameController;
import com.shiv.securegkd.game.GameService;
import com.shiv.securegkd.health.HealthController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({HealthController.class, GameController.class, AllocationController.class})
@Import(SecurityConfiguration.class)
class SecurityConfigurationTests {

    private static final Instant CREATED_AT = Instant.parse("2026-01-02T03:04:05Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private GameService gameService;

    @MockitoBean
    private AllocationService allocationService;

    @Test
    void healthIsPubliclyAccessible() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void anonymousGameRequestIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/games/GTA5"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(gameService);
    }

    @Test
    void anonymousAllocationRequestIsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/games/GTA5/allocations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AllocationRequest("request-1"))))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(allocationService);
    }

    @Test
    void syntheticAuthenticatedPrincipalCanReachGameControllerBehavior() throws Exception {
        Game game = new Game("GTA5", "Grand Theft Auto V");
        ReflectionTestUtils.setField(game, "id", 1L);
        ReflectionTestUtils.setField(game, "createdAt", CREATED_AT);
        when(gameService.findByCode("GTA5")).thenReturn(Optional.of(game));

        mockMvc.perform(get("/api/games/GTA5").with(user("test-user")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.code").value("GTA5"))
                .andExpect(jsonPath("$.title").value("Grand Theft Auto V"))
                .andExpect(jsonPath("$.createdAt").value("2026-01-02T03:04:05Z"));
    }

    @Test
    void authenticatedAllocationRequestPreservesControllerBehavior() throws Exception {
        AllocationRequest request = new AllocationRequest("request-1");
        AllocationResponse response = new AllocationResponse("GTA5", "GTA5-KEY-001", CREATED_AT);
        when(allocationService.allocate("GTA5", request)).thenReturn(response);

        mockMvc.perform(post("/api/games/GTA5/allocations")
                        .with(user("test-user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.gameCode").value("GTA5"))
                .andExpect(jsonPath("$.keyCode").value("GTA5-KEY-001"))
                .andExpect(jsonPath("$.allocatedAt").value("2026-01-02T03:04:05Z"));
    }

    @Test
    void authenticatedRequestToUnclassifiedRouteIsForbidden() throws Exception {
        mockMvc.perform(get("/api/unclassified").with(user("test-user")))
                .andExpect(status().isForbidden());
    }
}
