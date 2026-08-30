package com.shiv.securegkd.allocation;

import com.shiv.securegkd.RequestCorrelationFilter;
import com.shiv.securegkd.allocation.outbox.AllocationOutbox;
import com.shiv.securegkd.allocation.outbox.AllocationOutboxRepository;
import com.shiv.securegkd.game.Game;
import com.shiv.securegkd.game.GameRepository;
import com.shiv.securegkd.gamekey.GameKey;
import com.shiv.securegkd.gamekey.GameKeyRepository;
import com.shiv.securegkd.idempotency.IdempotencyRecord;
import com.shiv.securegkd.idempotency.IdempotencyRecordRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import javax.sql.DataSource;
import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AllocationTransactionRollbackTests {

    private static final String GAME_CODE = "GTA5";
    private static final String GAME_KEY_CODE = "GTA5-KEY-001";
    private static final String REQUEST_ID = "f49f5ba7-53ee-4c8b-95af-29e75831176a";

    @Autowired
    private AllocationService allocationService;

    @Autowired
    private GameRepository gameRepository;

    @Autowired
    private GameKeyRepository gameKeyRepository;

    @MockitoSpyBean
    private AllocationRepository allocationRepository;

    @MockitoSpyBean
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @MockitoSpyBean
    private AllocationOutboxRepository allocationOutboxRepository;

    @Autowired
    private DataSource dataSource;

    private Long gameId;
    private Long gameKeyId;

    @BeforeEach
    void setUp() {
        deleteTestData();

        Game game = gameRepository.saveAndFlush(new Game(GAME_CODE, "Grand Theft Auto V"));
        GameKey gameKey = gameKeyRepository.saveAndFlush(new GameKey(game, GAME_KEY_CODE));
        gameId = game.getId();
        gameKeyId = gameKey.getId();
        MDC.put(RequestCorrelationFilter.MDC_KEY, REQUEST_ID);
    }

    @AfterEach
    void tearDown() {
        MDC.remove(RequestCorrelationFilter.MDC_KEY);
        deleteTestData();
    }

    @Test
    void rollsBackFlushedAllocationWhenIdempotencyPersistenceFails() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
        }

        String idempotencyKey = "request-rollback";
        IllegalStateException forcedFailure = new IllegalStateException(
                "forced failure after allocation flush"
        );
        doThrow(forcedFailure)
                .when(idempotencyRecordRepository)
                .save(any(IdempotencyRecord.class));

        assertThatThrownBy(() ->
                allocationService.allocate(GAME_CODE, new AllocationRequest(idempotencyKey))
        ).isSameAs(forcedFailure);

        ArgumentCaptor<Allocation> allocationCaptor = ArgumentCaptor.forClass(Allocation.class);
        verify(allocationRepository).saveAndFlush(allocationCaptor.capture());
        assertThat(allocationCaptor.getValue().getId()).isNotNull();
        assertThat(allocationCaptor.getValue().getAllocatedAt()).isNotNull();

        ArgumentCaptor<IdempotencyRecord> idempotencyRecordCaptor =
                ArgumentCaptor.forClass(IdempotencyRecord.class);
        verify(idempotencyRecordRepository).save(idempotencyRecordCaptor.capture());
        assertThat(idempotencyRecordCaptor.getValue().getAllocation())
                .isSameAs(allocationCaptor.getValue());
        verify(allocationOutboxRepository, never()).save(any(AllocationOutbox.class));

        Game persistedGame = gameRepository.findById(gameId).orElseThrow();
        GameKey persistedGameKey = gameKeyRepository.findById(gameKeyId).orElseThrow();

        assertThat(allocationRepository.findAll()).isEmpty();
        assertThat(allocationRepository.findByGameKey(persistedGameKey)).isEmpty();
        assertThat(idempotencyRecordRepository.findAll()).isEmpty();
        assertThat(idempotencyRecordRepository.existsByIdempotencyKey(idempotencyKey)).isFalse();
        assertThat(allocationOutboxRepository.findAll()).isEmpty();

        assertThat(persistedGame.getCode()).isEqualTo(GAME_CODE);
        assertThat(persistedGameKey.getCode()).isEqualTo(GAME_KEY_CODE);
        assertThat(gameKeyRepository.findAvailableByGame(persistedGame, PageRequest.of(0, 1)))
                .extracting(GameKey::getId)
                .containsExactly(gameKeyId);
    }

    @Test
    void rollsBackAllocationAndIdempotencyRecordWhenOutboxPersistenceFails() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
        }

        String idempotencyKey = "request-outbox-rollback";
        IllegalStateException forcedFailure = new IllegalStateException(
                "forced outbox persistence failure"
        );
        doThrow(forcedFailure)
                .when(allocationOutboxRepository)
                .save(any(AllocationOutbox.class));

        assertThatThrownBy(() ->
                allocationService.allocate(GAME_CODE, new AllocationRequest(idempotencyKey))
        ).isSameAs(forcedFailure);

        ArgumentCaptor<Allocation> allocationCaptor = ArgumentCaptor.forClass(Allocation.class);
        verify(allocationRepository).saveAndFlush(allocationCaptor.capture());
        assertThat(allocationCaptor.getValue().getId()).isNotNull();
        assertThat(allocationCaptor.getValue().getAllocatedAt()).isNotNull();

        ArgumentCaptor<IdempotencyRecord> idempotencyRecordCaptor =
                ArgumentCaptor.forClass(IdempotencyRecord.class);
        verify(idempotencyRecordRepository).save(idempotencyRecordCaptor.capture());
        assertThat(idempotencyRecordCaptor.getValue().getAllocation())
                .isSameAs(allocationCaptor.getValue());
        verify(allocationOutboxRepository).save(any(AllocationOutbox.class));

        Game persistedGame = gameRepository.findById(gameId).orElseThrow();
        GameKey persistedGameKey = gameKeyRepository.findById(gameKeyId).orElseThrow();

        assertThat(allocationRepository.findAll()).isEmpty();
        assertThat(allocationRepository.findByGameKey(persistedGameKey)).isEmpty();
        assertThat(idempotencyRecordRepository.findAll()).isEmpty();
        assertThat(idempotencyRecordRepository.existsByIdempotencyKey(idempotencyKey)).isFalse();
        assertThat(allocationOutboxRepository.findAll()).isEmpty();

        assertThat(persistedGame.getCode()).isEqualTo(GAME_CODE);
        assertThat(persistedGameKey.getCode()).isEqualTo(GAME_KEY_CODE);
        assertThat(gameKeyRepository.findAvailableByGame(persistedGame, PageRequest.of(0, 1)))
                .extracting(GameKey::getId)
                .containsExactly(gameKeyId);
    }

    private void deleteTestData() {
        allocationOutboxRepository.deleteAllInBatch();
        idempotencyRecordRepository.deleteAllInBatch();
        allocationRepository.deleteAllInBatch();
        gameKeyRepository.deleteAllInBatch();
        gameRepository.deleteAllInBatch();
    }
}
