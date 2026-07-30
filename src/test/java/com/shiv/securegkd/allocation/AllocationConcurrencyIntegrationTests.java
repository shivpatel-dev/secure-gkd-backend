package com.shiv.securegkd.allocation;

import com.shiv.securegkd.game.Game;
import com.shiv.securegkd.game.GameRepository;
import com.shiv.securegkd.gamekey.GameKey;
import com.shiv.securegkd.gamekey.GameKeyRepository;
import com.shiv.securegkd.idempotency.IdempotencyRecordRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AllocationConcurrencyIntegrationTests {

    private static final String GAME_CODE = "GTA5";
    private static final String GAME_KEY_CODE = "GTA5-KEY-001";
    private static final String FIRST_IDEMPOTENCY_KEY = "request-concurrent-1";
    private static final String SECOND_IDEMPOTENCY_KEY = "request-concurrent-2";
    private static final long BARRIER_TIMEOUT_SECONDS = 10;
    private static final long FUTURE_TIMEOUT_SECONDS = 20;
    private static final long EXECUTOR_TIMEOUT_SECONDS = 5;

    @Autowired
    private AllocationService allocationService;

    @Autowired
    private GameRepository gameRepository;

    @MockitoSpyBean
    private GameKeyRepository gameKeyRepository;

    @Autowired
    private AllocationRepository allocationRepository;

    @Autowired
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    private Long gameKeyId;

    @BeforeEach
    void setUp() {
        deleteTestData();

        Game game = gameRepository.saveAndFlush(new Game(GAME_CODE, "Grand Theft Auto V"));
        GameKey gameKey = gameKeyRepository.saveAndFlush(new GameKey(game, GAME_KEY_CODE));
        gameKeyId = gameKey.getId();
    }

    @AfterEach
    void tearDown() {
        deleteTestData();
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void concurrentAllocationsForOneGameKeyLeaveOneCompleteResult() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
        }

        CyclicBarrier bothSelectionsCompleted = new CyclicBarrier(2);
        Set<Long> selectionThreadIds = ConcurrentHashMap.newKeySet();
        Set<Long> selectedGameKeyIds = ConcurrentHashMap.newKeySet();
        Set<TransactionStatus> transactionStatuses = Collections.synchronizedSet(
                Collections.newSetFromMap(new IdentityHashMap<>())
        );
        GameKeyRepository repositoryDelegate = new JpaRepositoryFactory(entityManager)
                .getRepository(GameKeyRepository.class);

        doAnswer(invocation -> {
            Game game = invocation.getArgument(0);
            Pageable pageable = invocation.getArgument(1);
            List<GameKey> availableKeys = repositoryDelegate.findAvailableByGame(game, pageable);

            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            selectionThreadIds.add(Thread.currentThread().getId());
            transactionStatuses.add(TransactionAspectSupport.currentTransactionStatus());
            availableKeys.stream().map(GameKey::getId).forEach(selectedGameKeyIds::add);

            bothSelectionsCompleted.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return availableKeys;
        }).when(gameKeyRepository).findAvailableByGame(any(Game.class), any(Pageable.class));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        AttemptResult firstResult;
        AttemptResult secondResult;
        try {
            Future<AttemptResult> firstAttempt = executor.submit(
                    () -> allocate(FIRST_IDEMPOTENCY_KEY)
            );
            Future<AttemptResult> secondAttempt = executor.submit(
                    () -> allocate(SECOND_IDEMPOTENCY_KEY)
            );

            firstResult = firstAttempt.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            secondResult = secondAttempt.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(EXECUTOR_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .isTrue();
        }

        assertThat(bothSelectionsCompleted.getNumberWaiting()).isZero();
        assertThat(selectionThreadIds).hasSize(2);
        assertThat(transactionStatuses).hasSize(2);
        assertThat(selectedGameKeyIds).containsExactly(gameKeyId);

        List<AttemptResult> results = List.of(firstResult, secondResult);
        AttemptResult successfulAttempt = results.stream()
                .filter(AttemptResult::succeeded)
                .findFirst()
                .orElseThrow();
        AttemptResult failedAttempt = results.stream()
                .filter(result -> !result.succeeded())
                .findFirst()
                .orElseThrow();

        assertThat(results).filteredOn(AttemptResult::succeeded).hasSize(1);
        assertThat(results)
                .extracting(AttemptResult::idempotencyKey)
                .containsExactlyInAnyOrder(FIRST_IDEMPOTENCY_KEY, SECOND_IDEMPOTENCY_KEY);
        assertThat(successfulAttempt.response().gameCode()).isEqualTo(GAME_CODE);
        assertThat(successfulAttempt.response().keyCode()).isEqualTo(GAME_KEY_CODE);
        assertThat(successfulAttempt.response().allocatedAt()).isNotNull();
        assertThat(successfulAttempt.failure()).isNull();

        assertThat(failedAttempt.response()).isNull();
        assertThat(failedAttempt.failure())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Selected game key is no longer available for game: " + GAME_CODE)
                .hasCauseInstanceOf(DataIntegrityViolationException.class);
        assertThat(causeTypeNames(failedAttempt.failure())).containsSubsequence(
                IllegalStateException.class.getName(),
                DataIntegrityViolationException.class.getName(),
                "org.hibernate.exception.ConstraintViolationException",
                "org.postgresql.util.PSQLException"
        );

        SQLException databaseFailure = findCause(failedAttempt.failure(), SQLException.class);
        assertThat((Object) databaseFailure).isNotNull();
        assertThat(databaseFailure.getSQLState()).isEqualTo("23505");
        assertThat(databaseFailure.getMessage()).contains("game_key_id");

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from allocations where game_key_id = ?",
                Long.class,
                gameKeyId
        )).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from allocations",
                Long.class
        )).isEqualTo(1L);

        Long allocationId = jdbcTemplate.queryForObject(
                "select id from allocations where game_key_id = ?",
                Long.class,
                gameKeyId
        );
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from idempotency_records where allocation_id = ?",
                Long.class,
                allocationId
        )).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from idempotency_records",
                Long.class
        )).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "select idempotency_key from idempotency_records where allocation_id = ?",
                String.class,
                allocationId
        )).isEqualTo(successfulAttempt.idempotencyKey());
        assertThat(idempotencyRecordRepository.existsByIdempotencyKey(
                successfulAttempt.idempotencyKey()
        )).isTrue();
        assertThat(idempotencyRecordRepository.existsByIdempotencyKey(
                failedAttempt.idempotencyKey()
        )).isFalse();
    }

    private AttemptResult allocate(String idempotencyKey) {
        try {
            AllocationResponse response = allocationService.allocate(
                    GAME_CODE,
                    new AllocationRequest(idempotencyKey)
            );
            return new AttemptResult(idempotencyKey, response, null);
        } catch (Throwable failure) {
            return new AttemptResult(idempotencyKey, null, failure);
        }
    }

    private <T extends Throwable> T findCause(Throwable failure, Class<T> causeType) {
        Throwable current = failure;
        while (current != null) {
            if (causeType.isInstance(current)) {
                return causeType.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }

    private List<String> causeTypeNames(Throwable failure) {
        List<String> causeTypes = new ArrayList<>();
        Throwable current = failure;
        while (current != null) {
            causeTypes.add(current.getClass().getName());
            current = current.getCause();
        }
        return causeTypes;
    }

    private void deleteTestData() {
        idempotencyRecordRepository.deleteAllInBatch();
        allocationRepository.deleteAllInBatch();
        gameKeyRepository.deleteAllInBatch();
        gameRepository.deleteAllInBatch();
    }

    private record AttemptResult(
            String idempotencyKey,
            AllocationResponse response,
            Throwable failure
    ) {
        boolean succeeded() {
            return response != null && failure == null;
        }
    }
}
