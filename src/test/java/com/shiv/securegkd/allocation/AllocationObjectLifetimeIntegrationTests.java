package com.shiv.securegkd.allocation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shiv.securegkd.security.AllocationTestSecurityConfiguration;
import com.shiv.securegkd.game.Game;
import com.shiv.securegkd.game.GameRepository;
import com.shiv.securegkd.gamekey.GameKey;
import com.shiv.securegkd.gamekey.GameKeyRepository;
import com.shiv.securegkd.idempotency.IdempotencyRecord;
import com.shiv.securegkd.idempotency.IdempotencyRecordRepository;
import com.shiv.securegkd.allocation.outbox.AllocationOutboxRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(AllocationTestSecurityConfiguration.class)
class AllocationObjectLifetimeIntegrationTests {

    private static final String GAME_CODE = "OBJECT-LIFETIME-GAME";
    private static final String GAME_KEY_CODE = "OBJECT-LIFETIME-KEY-001";
    private static final String IDEMPOTENCY_KEY = "object-lifetime-53";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(15);

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @PersistenceContext
    private EntityManager entityManager;

    @MockitoSpyBean
    private AllocationController allocationController;

    @MockitoSpyBean
    private AllocationService allocationService;

    @MockitoSpyBean
    private GameRepository gameRepository;

    @MockitoSpyBean
    private GameKeyRepository gameKeyRepository;

    @MockitoSpyBean
    private AllocationRepository allocationRepository;

    @MockitoSpyBean
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @Autowired
    private AllocationOutboxRepository allocationOutboxRepository;

    private final Trace trace = new Trace();

    @BeforeEach
    void setUp() throws Exception {
        deleteTestData();
        trace.reset();

        try (var connection = dataSource.getConnection()) {
            var metadata = connection.getMetaData();
            trace.databaseProduct.set(metadata.getDatabaseProductName());
            trace.databaseVersion.set(metadata.getDatabaseProductVersion());
        }

        Game game = gameRepository.saveAndFlush(new Game(GAME_CODE, "Object Lifetime Game"));
        gameKeyRepository.saveAndFlush(new GameKey(game, GAME_KEY_CODE));

        instrumentReferenceFlow();
    }

    @AfterEach
    void tearDown() {
        deleteTestData();
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void tracesReferencesAndRequestPersistenceContextThroughRealHttpAllocation() throws Exception {
        String rawRequestJson = "{\"idempotencyKey\":\"" + IDEMPOTENCY_KEY + "\"}";
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/games/" + GAME_CODE + "/allocations"))
                .timeout(HTTP_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(rawRequestJson))
                .build();

        HttpResponse<String> httpResponse = client.send(
                httpRequest,
                HttpResponse.BodyHandlers.ofString()
        );
        AllocationResponse clientResponse = objectMapper.readValue(
                httpResponse.body(),
                AllocationResponse.class
        );

        assertThat(httpResponse.statusCode()).isEqualTo(201);
        assertThat(clientResponse.gameCode()).isEqualTo(GAME_CODE);
        assertThat(clientResponse.keyCode()).isEqualTo(GAME_KEY_CODE);
        assertThat(clientResponse.allocatedAt()).isNotNull();

        assertThat(trace.databaseProduct.get()).isEqualTo("PostgreSQL");
        assertThat(trace.databaseVersion.get()).isNotBlank();

        assertThat(trace.controllerRequest.get()).isSameAs(trace.serviceRequest.get());
        assertThat(trace.availableKeyLookupGame.get()).isSameAs(trace.loadedGame.get());
        assertThat(trace.selectedGameKey.get().getGame()).isSameAs(trace.loadedGame.get());
        assertThat(trace.allocationBeforeSave.get().getGameKey()).isSameAs(trace.selectedGameKey.get());
        assertThat(trace.allocationAfterSave.get()).isSameAs(trace.allocationBeforeSave.get());
        assertThat(trace.idempotencyRecord.get().getAllocation()).isSameAs(trace.allocationAfterSave.get());
        assertThat(trace.serviceResponse.get()).isSameAs(trace.controllerResponse.get());
        assertThat(clientResponse).isNotSameAs(trace.serviceResponse.get());

        assertThat(trace.transactionActiveAtService.get()).isTrue();
        assertThat(trace.transactionActiveAtGameLookup.get()).isTrue();
        assertThat(trace.transactionActiveAtAllocationSave.get()).isTrue();
        assertThat(trace.transactionActiveAtIdempotencySave.get()).isTrue();
        assertThat(trace.requestEntityManager.get()).isNotNull();
        assertThat(trace.requestSession.get()).isNotNull();
        assertThat(trace.entityManagerAtGameLookup.get()).isSameAs(trace.requestEntityManager.get());
        assertThat(trace.entityManagerAtAllocationSave.get()).isSameAs(trace.requestEntityManager.get());
        assertThat(trace.entityManagerAtIdempotencySave.get()).isSameAs(trace.requestEntityManager.get());

        assertThat(trace.gameManaged.get()).isTrue();
        assertThat(trace.gameManagedAtAvailableKeyLookup.get()).isTrue();
        assertThat(trace.gameKeyManaged.get()).isTrue();
        assertThat(trace.allocationManagedBeforeSave.get()).isFalse();
        assertThat(trace.allocationManagedAfterSave.get()).isTrue();
        assertThat(trace.idempotencyManagedBeforeSave.get()).isFalse();
        assertThat(trace.idempotencyManagedAfterSave.get()).isTrue();

        assertThat(trace.requestEntityManager.get().isOpen()).isFalse();
        assertThat(trace.requestSession.get().isOpen()).isFalse();

        assertThat(allocationRepository.count()).isEqualTo(1L);
        assertThat(idempotencyRecordRepository.count()).isEqualTo(1L);
        IdempotencyRecord persistedRecord = idempotencyRecordRepository
                .findByIdempotencyKey(IDEMPOTENCY_KEY)
                .orElseThrow();
        assertThat(persistedRecord.getAllocation().getId())
                .isEqualTo(trace.allocationAfterSave.get().getId());
    }

    private void instrumentReferenceFlow() {
        JpaRepositoryFactory repositoryFactory = new JpaRepositoryFactory(entityManager);
        GameRepository gameRepositoryDelegate = repositoryFactory.getRepository(GameRepository.class);
        GameKeyRepository gameKeyRepositoryDelegate = repositoryFactory.getRepository(GameKeyRepository.class);
        AllocationRepository allocationRepositoryDelegate =
                repositoryFactory.getRepository(AllocationRepository.class);
        IdempotencyRecordRepository idempotencyRecordRepositoryDelegate =
                repositoryFactory.getRepository(IdempotencyRecordRepository.class);

        doAnswer(invocation -> {
            trace.controllerRequest.set(invocation.getArgument(1));
            @SuppressWarnings("unchecked")
            ResponseEntity<AllocationResponse> response =
                    (ResponseEntity<AllocationResponse>) invocation.callRealMethod();
            trace.controllerResponse.set(response.getBody());
            return response;
        }).when(allocationController).allocate(anyString(), any(AllocationRequest.class));

        doAnswer(invocation -> {
            trace.serviceRequest.set(invocation.getArgument(1));
            trace.transactionActiveAtService.set(TransactionSynchronizationManager.isActualTransactionActive());
            trace.captureRequestPersistenceContext(entityManagerFactory);
            AllocationResponse response = (AllocationResponse) invocation.callRealMethod();
            trace.serviceResponse.set(response);
            return response;
        }).when(allocationService).allocate(anyString(), any(AllocationRequest.class));

        doAnswer(invocation -> {
            trace.transactionActiveAtGameLookup.set(TransactionSynchronizationManager.isActualTransactionActive());
            EntityManager entityManager = trace.transactionalEntityManager(entityManagerFactory);
            trace.entityManagerAtGameLookup.set(entityManager);
            var loaded = gameRepositoryDelegate.findByCode(invocation.getArgument(0));
            loaded.ifPresent(game -> {
                trace.loadedGame.set(game);
                trace.gameManaged.set(entityManager.contains(game));
            });
            return loaded;
        }).when(gameRepository).findByCode(anyString());

        doAnswer(invocation -> {
            Game game = invocation.getArgument(0);
            trace.availableKeyLookupGame.set(game);
            EntityManager entityManager = trace.transactionalEntityManager(entityManagerFactory);
            trace.gameManagedAtAvailableKeyLookup.set(entityManager.contains(game));
            var keys = gameKeyRepositoryDelegate.findAvailableByGame(
                    game,
                    invocation.getArgument(1)
            );
            GameKey selected = keys.get(0);
            trace.selectedGameKey.set(selected);
            trace.gameKeyManaged.set(entityManager.contains(selected));
            return keys;
        }).when(gameKeyRepository).findAvailableByGame(any(Game.class), any(Pageable.class));

        doAnswer(invocation -> {
            Allocation allocation = invocation.getArgument(0);
            trace.allocationBeforeSave.set(allocation);
            trace.transactionActiveAtAllocationSave.set(TransactionSynchronizationManager.isActualTransactionActive());
            EntityManager entityManager = trace.transactionalEntityManager(entityManagerFactory);
            trace.entityManagerAtAllocationSave.set(entityManager);
            trace.allocationManagedBeforeSave.set(entityManager.contains(allocation));
            Allocation saved = allocationRepositoryDelegate.saveAndFlush(allocation);
            trace.allocationAfterSave.set(saved);
            trace.allocationManagedAfterSave.set(entityManager.contains(saved));
            return saved;
        }).when(allocationRepository).saveAndFlush(any(Allocation.class));

        doAnswer(invocation -> {
            IdempotencyRecord record = invocation.getArgument(0);
            trace.idempotencyRecord.set(record);
            trace.transactionActiveAtIdempotencySave.set(TransactionSynchronizationManager.isActualTransactionActive());
            EntityManager entityManager = trace.transactionalEntityManager(entityManagerFactory);
            trace.entityManagerAtIdempotencySave.set(entityManager);
            trace.idempotencyManagedBeforeSave.set(entityManager.contains(record));
            IdempotencyRecord saved = idempotencyRecordRepositoryDelegate.save(record);
            trace.idempotencyManagedAfterSave.set(entityManager.contains(saved));
            return saved;
        }).when(idempotencyRecordRepository).save(any(IdempotencyRecord.class));
    }

    private void deleteTestData() {
        allocationOutboxRepository.deleteAllInBatch();
        idempotencyRecordRepository.deleteAllInBatch();
        allocationRepository.deleteAllInBatch();
        gameKeyRepository.deleteAllInBatch();
        gameRepository.deleteAllInBatch();
    }

    private static final class Trace {
        private final AtomicReference<String> databaseProduct = new AtomicReference<>();
        private final AtomicReference<String> databaseVersion = new AtomicReference<>();
        private final AtomicReference<AllocationRequest> controllerRequest = new AtomicReference<>();
        private final AtomicReference<AllocationRequest> serviceRequest = new AtomicReference<>();
        private final AtomicReference<Game> loadedGame = new AtomicReference<>();
        private final AtomicReference<Game> availableKeyLookupGame = new AtomicReference<>();
        private final AtomicReference<GameKey> selectedGameKey = new AtomicReference<>();
        private final AtomicReference<Allocation> allocationBeforeSave = new AtomicReference<>();
        private final AtomicReference<Allocation> allocationAfterSave = new AtomicReference<>();
        private final AtomicReference<IdempotencyRecord> idempotencyRecord = new AtomicReference<>();
        private final AtomicReference<AllocationResponse> serviceResponse = new AtomicReference<>();
        private final AtomicReference<AllocationResponse> controllerResponse = new AtomicReference<>();
        private final AtomicReference<Boolean> transactionActiveAtService = new AtomicReference<>();
        private final AtomicReference<Boolean> transactionActiveAtGameLookup = new AtomicReference<>();
        private final AtomicReference<Boolean> transactionActiveAtAllocationSave = new AtomicReference<>();
        private final AtomicReference<Boolean> transactionActiveAtIdempotencySave = new AtomicReference<>();
        private final AtomicReference<EntityManager> requestEntityManager = new AtomicReference<>();
        private final AtomicReference<Session> requestSession = new AtomicReference<>();
        private final AtomicReference<EntityManager> entityManagerAtGameLookup = new AtomicReference<>();
        private final AtomicReference<EntityManager> entityManagerAtAllocationSave = new AtomicReference<>();
        private final AtomicReference<EntityManager> entityManagerAtIdempotencySave = new AtomicReference<>();
        private final AtomicReference<Boolean> gameManaged = new AtomicReference<>();
        private final AtomicReference<Boolean> gameManagedAtAvailableKeyLookup = new AtomicReference<>();
        private final AtomicReference<Boolean> gameKeyManaged = new AtomicReference<>();
        private final AtomicReference<Boolean> allocationManagedBeforeSave = new AtomicReference<>();
        private final AtomicReference<Boolean> allocationManagedAfterSave = new AtomicReference<>();
        private final AtomicReference<Boolean> idempotencyManagedBeforeSave = new AtomicReference<>();
        private final AtomicReference<Boolean> idempotencyManagedAfterSave = new AtomicReference<>();

        private void captureRequestPersistenceContext(EntityManagerFactory factory) {
            EntityManager entityManager = transactionalEntityManager(factory);
            requestEntityManager.set(entityManager);
            requestSession.set(entityManager.unwrap(Session.class));
        }

        private EntityManager transactionalEntityManager(EntityManagerFactory factory) {
            EntityManager entityManager = EntityManagerFactoryUtils.getTransactionalEntityManager(factory);
            assertThat(entityManager).isNotNull();
            return entityManager;
        }

        private void reset() {
            databaseProduct.set(null);
            databaseVersion.set(null);
            controllerRequest.set(null);
            serviceRequest.set(null);
            loadedGame.set(null);
            availableKeyLookupGame.set(null);
            selectedGameKey.set(null);
            allocationBeforeSave.set(null);
            allocationAfterSave.set(null);
            idempotencyRecord.set(null);
            serviceResponse.set(null);
            controllerResponse.set(null);
            transactionActiveAtService.set(null);
            transactionActiveAtGameLookup.set(null);
            transactionActiveAtAllocationSave.set(null);
            transactionActiveAtIdempotencySave.set(null);
            requestEntityManager.set(null);
            requestSession.set(null);
            entityManagerAtGameLookup.set(null);
            entityManagerAtAllocationSave.set(null);
            entityManagerAtIdempotencySave.set(null);
            gameManaged.set(null);
            gameManagedAtAvailableKeyLookup.set(null);
            gameKeyManaged.set(null);
            allocationManagedBeforeSave.set(null);
            allocationManagedAfterSave.set(null);
            idempotencyManagedBeforeSave.set(null);
            idempotencyManagedAfterSave.set(null);
        }
    }
}
