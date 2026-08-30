package com.shiv.securegkd.allocation.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shiv.securegkd.RequestCorrelationFilter;
import com.shiv.securegkd.allocation.Allocation;
import com.shiv.securegkd.allocation.AllocationRepository;
import com.shiv.securegkd.allocation.AllocationRequest;
import com.shiv.securegkd.allocation.AllocationResponse;
import com.shiv.securegkd.allocation.AllocationService;
import com.shiv.securegkd.allocation.event.AllocationCreated;
import com.shiv.securegkd.game.Game;
import com.shiv.securegkd.game.GameRepository;
import com.shiv.securegkd.gamekey.GameKey;
import com.shiv.securegkd.gamekey.GameKeyRepository;
import com.shiv.securegkd.idempotency.IdempotencyRecordRepository;
import com.shiv.securegkd.security.AllocationTestSecurityConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@AutoConfigureMockMvc
@Import(AllocationTestSecurityConfiguration.class)
class AllocationOutboxPersistenceTests {

    private static final String GAME_CODE = "OUTBOX-GAME";
    private static final String SECRET_GAME_KEY_CODE = "SECRET-GAME-KEY-001";
    private static final String IDEMPOTENCY_KEY = "secret-idempotency-key";
    private static final String REQUEST_ID = "f49f5ba7-53ee-4c8b-95af-29e75831176a";

    @Autowired
    private AllocationService allocationService;

    @Autowired
    private GameRepository gameRepository;

    @Autowired
    private GameKeyRepository gameKeyRepository;

    @Autowired
    private AllocationRepository allocationRepository;

    @Autowired
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @Autowired
    private AllocationOutboxRepository allocationOutboxRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private MockMvc mockMvc;

    private Long gameId;

    @BeforeEach
    void setUp() {
        deleteTestData();
        Game game = gameRepository.saveAndFlush(new Game(GAME_CODE, "Outbox Test Game"));
        gameKeyRepository.saveAndFlush(new GameKey(game, SECRET_GAME_KEY_CODE));
        gameId = game.getId();
        MDC.put(RequestCorrelationFilter.MDC_KEY, REQUEST_ID);
    }

    @AfterEach
    void tearDown() {
        MDC.remove(RequestCorrelationFilter.MDC_KEY);
        deleteTestData();
    }

    @Test
    void newAllocationPersistsOneConsistentSecretFreeEventIntentAndReplayAddsNothing() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
        }

        MvcResult firstResult = performAllocation();
        AllocationResponse firstResponse = objectMapper.readValue(
                firstResult.getResponse().getContentAsByteArray(),
                AllocationResponse.class
        );
        String establishedRequestId = firstResult.getResponse().getHeader(
                RequestCorrelationFilter.HEADER_NAME
        );
        assertThat(establishedRequestId).isNotBlank();
        assertThat(establishedRequestId).isNotEqualTo("client-supplied-request-id");

        assertThat(allocationRepository.count()).isEqualTo(1L);
        assertThat(idempotencyRecordRepository.count()).isEqualTo(1L);
        assertThat(allocationOutboxRepository.count()).isEqualTo(1L);

        Allocation allocation = allocationRepository.findAll().get(0);
        AllocationOutbox outbox = allocationOutboxRepository.findAll().get(0);
        AllocationCreated event = objectMapper.readValue(outbox.getPayload(), AllocationCreated.class);

        assertThat(outbox.getEventType()).isEqualTo(AllocationOutbox.ALLOCATION_CREATED_EVENT_TYPE);
        assertThat(outbox.getEventId()).isEqualTo(event.eventId());
        assertThat(outbox.getSchemaVersion()).isEqualTo(AllocationCreated.SCHEMA_VERSION);
        assertThat(outbox.getSchemaVersion()).isEqualTo(event.schemaVersion());
        assertThat(outbox.getOccurredAt()).isEqualTo(event.occurredAt());
        assertThat(outbox.getPublishedAt()).isNull();
        assertThat(outbox.getAllocation().getId()).isEqualTo(allocation.getId());

        assertThat(event.allocationId()).isEqualTo(allocation.getId());
        assertThat(event.allocatedAt()).isEqualTo(allocation.getAllocatedAt());
        assertThat(event.allocatedAt()).isEqualTo(firstResponse.allocatedAt());
        assertThat(event.gameId()).isEqualTo(gameId);
        assertThat(event.gameCode()).isEqualTo(GAME_CODE);
        assertThat(event.requestId()).isEqualTo(establishedRequestId);
        assertThat(event.requestId()).isNotEqualTo(IDEMPOTENCY_KEY);
        assertThat(event.eventId().toString()).isNotEqualTo(IDEMPOTENCY_KEY);

        assertThat(outbox.getPayload())
                .doesNotContain(SECRET_GAME_KEY_CODE)
                .doesNotContain(IDEMPOTENCY_KEY)
                .doesNotContain("keyCode")
                .doesNotContain("gameKey")
                .doesNotContain("password")
                .doesNotContain("Bearer")
                .doesNotContain("jwt");

        UUID originalEventId = outbox.getEventId();
        MvcResult replayResult = performAllocation();
        AllocationResponse replayedResponse = objectMapper.readValue(
                replayResult.getResponse().getContentAsByteArray(),
                AllocationResponse.class
        );

        assertThat(replayedResponse).isEqualTo(firstResponse);
        assertThat(allocationRepository.count()).isEqualTo(1L);
        assertThat(idempotencyRecordRepository.count()).isEqualTo(1L);
        assertThat(allocationOutboxRepository.count()).isEqualTo(1L);
        assertThat(allocationOutboxRepository.findAll().get(0).getEventId()).isEqualTo(originalEventId);
        assertThat(objectMapper.readValue(
                allocationOutboxRepository.findAll().get(0).getPayload(),
                AllocationCreated.class
        ).requestId()).isEqualTo(establishedRequestId);
    }

    @Test
    void databaseRejectsSecondEventIntentForTheSameAllocation() {
        allocationService.allocate(GAME_CODE, new AllocationRequest(IDEMPOTENCY_KEY));
        Allocation allocation = allocationRepository.findAll().get(0);

        assertThatThrownBy(() -> allocationOutboxRepository.saveAndFlush(new AllocationOutbox(
                UUID.randomUUID(),
                allocation,
                AllocationOutbox.ALLOCATION_CREATED_EVENT_TYPE,
                AllocationCreated.SCHEMA_VERSION,
                "{}",
                Instant.now().truncatedTo(ChronoUnit.MICROS)
        ))).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(allocationOutboxRepository.count()).isEqualTo(1L);
    }

    private void deleteTestData() {
        allocationOutboxRepository.deleteAllInBatch();
        idempotencyRecordRepository.deleteAllInBatch();
        allocationRepository.deleteAllInBatch();
        gameKeyRepository.deleteAllInBatch();
        gameRepository.deleteAllInBatch();
    }

    private MvcResult performAllocation() throws Exception {
        return mockMvc.perform(post("/api/games/{gameCode}/allocations", GAME_CODE)
                        .header(RequestCorrelationFilter.HEADER_NAME, "client-supplied-request-id")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(new AllocationRequest(IDEMPOTENCY_KEY))))
                .andExpect(status().isCreated())
                .andReturn();
    }
}
