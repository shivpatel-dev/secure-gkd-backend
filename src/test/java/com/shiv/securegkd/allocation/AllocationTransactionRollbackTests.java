package com.shiv.securegkd.allocation;

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
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AllocationTransactionRollbackTests {

    private static final String GAME_CODE = "GTA5";
    private static final String GAME_KEY_CODE = "GTA5-KEY-001";

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
    }

    @AfterEach
    void tearDown() {
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

        Game persistedGame = gameRepository.findById(gameId).orElseThrow();
        GameKey persistedGameKey = gameKeyRepository.findById(gameKeyId).orElseThrow();

        assertThat(allocationRepository.findAll()).isEmpty();
        assertThat(allocationRepository.findByGameKey(persistedGameKey)).isEmpty();
        assertThat(idempotencyRecordRepository.findAll()).isEmpty();
        assertThat(idempotencyRecordRepository.existsByIdempotencyKey(idempotencyKey)).isFalse();

        assertThat(persistedGame.getCode()).isEqualTo(GAME_CODE);
        assertThat(persistedGameKey.getCode()).isEqualTo(GAME_KEY_CODE);
        assertThat(gameKeyRepository.findAvailableByGame(persistedGame, PageRequest.of(0, 1)))
                .extracting(GameKey::getId)
                .containsExactly(gameKeyId);
    }

    private void deleteTestData() {
        idempotencyRecordRepository.deleteAllInBatch();
        allocationRepository.deleteAllInBatch();
        gameKeyRepository.deleteAllInBatch();
        gameRepository.deleteAllInBatch();
    }
}
