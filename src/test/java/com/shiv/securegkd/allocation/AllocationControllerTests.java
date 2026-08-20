package com.shiv.securegkd.allocation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.shiv.securegkd.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.time.Instant;

import static org.hamcrest.Matchers.notNullValue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AllocationControllerTests {

    private static final Instant ALLOCATED_AT = Instant.parse("2026-01-02T03:04:05Z");

    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private AllocationService allocationService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        allocationService = mock(AllocationService.class);

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders.standaloneSetup(new AllocationController(allocationService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setValidator(validator)
                .build();
    }

    @Test
    void validPostAllocationsReturnsCreatedAndAllocationResponseData() throws Exception {
        AllocationRequest request = new AllocationRequest("request-1");
        AllocationResponse response = new AllocationResponse("GTA5", "GTA5-KEY-001", ALLOCATED_AT);
        when(allocationService.allocate("GTA5", request)).thenReturn(response);

        mockMvc.perform(post("/api/games/GTA5/allocations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.gameCode").value("GTA5"))
                .andExpect(jsonPath("$.keyCode").value("GTA5-KEY-001"))
                .andExpect(jsonPath("$.allocatedAt").value("2026-01-02T03:04:05Z"));
    }

    @Test
    void idempotencyKeyAtPersistedBoundaryIsAcceptedWithoutTransformation() throws Exception {
        String idempotencyKey = "K".repeat(255);
        AllocationRequest request = new AllocationRequest(idempotencyKey);
        AllocationResponse response = new AllocationResponse("GTA5", "GTA5-KEY-001", ALLOCATED_AT);
        when(allocationService.allocate("GTA5", request)).thenReturn(response);

        mockMvc.perform(post("/api/games/GTA5/allocations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        verify(allocationService).allocate("GTA5", new AllocationRequest(idempotencyKey));
    }

    @Test
    void idempotencyKeyOverPersistedBoundaryReturnsStructuredBadRequest() throws Exception {
        mockMvc.perform(post("/api/games/GTA5/allocations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new AllocationRequest("K".repeat(256))
                        )))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.timestamp", notNullValue()))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.path").value("/api/games/GTA5/allocations"))
                .andExpect(jsonPath("$.fieldErrors.idempotencyKey")
                        .value("idempotencyKey must be at most 255 characters"));

        verifyNoInteractions(allocationService);
    }

    @Test
    void blankIdempotencyKeyReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/games/GTA5/allocations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AllocationRequest(""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.timestamp", notNullValue()))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.path").value("/api/games/GTA5/allocations"))
                .andExpect(jsonPath("$.fieldErrors.idempotencyKey").value("idempotencyKey is required"));
    }

    @Test
    void validationFailureDoesNotCallAllocationService() throws Exception {
        mockMvc.perform(post("/api/games/GTA5/allocations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AllocationRequest(""))))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(allocationService);
    }

    @Test
    void unexpectedServiceFailureReturnsSafeGenericInternalError() throws Exception {
        AllocationRequest request = new AllocationRequest("request-1");
        when(allocationService.allocate("GTA5", request)).thenThrow(new IllegalStateException(
                "TEST_ONLY_INTERNAL_DETAIL",
                new IllegalArgumentException("TEST_ONLY_CAUSE_DETAIL")
        ));

        String responseBody = mockMvc.perform(post("/api/games/GTA5/allocations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.timestamp", notNullValue()))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.error").value("Internal Server Error"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.path").value("/api/games/GTA5/allocations"))
                .andExpect(jsonPath("$.fieldErrors").isEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(responseBody)
                .doesNotContain("TEST_ONLY_INTERNAL_DETAIL")
                .doesNotContain("TEST_ONLY_CAUSE_DETAIL")
                .doesNotContain(IllegalStateException.class.getName())
                .doesNotContain(IllegalArgumentException.class.getName());
    }
}
