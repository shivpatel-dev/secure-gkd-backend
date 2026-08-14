package com.shiv.securegkd.allocation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shiv.securegkd.game.Game;
import com.shiv.securegkd.game.GameRepository;
import com.shiv.securegkd.gamekey.GameKey;
import com.shiv.securegkd.gamekey.GameKeyRepository;
import com.shiv.securegkd.idempotency.IdempotencyRecordRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.coyote.AbstractProtocol;
import org.apache.coyote.ProtocolHandler;
import org.apache.tomcat.util.threads.ThreadPoolExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.sql.DataSource;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(AllocationRequestThreadIntegrationTests.RequestTraceConfiguration.class)
class AllocationRequestThreadIntegrationTests {

    private static final String GAME_CODE = "TRACE-GAME";
    private static final String GAME_KEY_CODE = "TRACE-KEY-001";
    private static final String IDEMPOTENCY_KEY = "request-thread-trace-51";
    private static final long ADVISORY_LOCK_KEY = 510051L;
    private static final long SYNCHRONIZATION_TIMEOUT_SECONDS = 10;
    private static final long DATABASE_BLOCK_TIMEOUT_SECONDS = 25;
    private static final long HTTP_TIMEOUT_SECONDS = 20;
    private static final int STACK_SNAPSHOT_DEPTH = 256;

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private GameRepository gameRepository;

    @MockitoSpyBean
    private GameKeyRepository gameKeyRepository;

    @Autowired
    private AllocationRepository allocationRepository;

    @Autowired
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @MockitoSpyBean
    private AllocationController allocationController;

    @MockitoSpyBean
    private AllocationService allocationService;

    @Autowired
    private WebServerApplicationContext serverApplicationContext;

    @Autowired
    private RequestTrace requestTrace;

    @PersistenceContext
    private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        deleteTestData();
        requestTrace.reset();

        Game game = gameRepository.saveAndFlush(new Game(GAME_CODE, "Request Thread Trace Game"));
        gameKeyRepository.saveAndFlush(new GameKey(game, GAME_KEY_CODE));
    }

    @AfterEach
    void tearDown() {
        deleteTestData();
    }

    @Test
    @Timeout(value = 40, unit = TimeUnit.SECONDS)
    void realHttpAllocationOccupiesOneTomcatThreadWhilePostgresqlIsBlocked() throws Exception {
        ServerObservation server = observeServer();
        GameKeyRepository repositoryDelegate = new JpaRepositoryFactory(entityManager)
                .getRepository(GameKeyRepository.class);

        doAnswer(invocation -> {
            requestTrace.recordController();
            return invocation.callRealMethod();
        }).when(allocationController).allocate(anyString(), any(AllocationRequest.class));

        doAnswer(invocation -> {
            requestTrace.recordService();
            return invocation.callRealMethod();
        }).when(allocationService).allocate(anyString(), any(AllocationRequest.class));

        doAnswer(invocation -> {
            Game game = invocation.getArgument(0);
            Pageable pageable = invocation.getArgument(1);
            requestTrace.recordRepository();
            List<GameKey> availableKeys = repositoryDelegate.findAvailableByGame(game, pageable);
            blockOnPostgresqlAdvisoryLock();
            return availableKeys;
        }).when(gameKeyRepository).findAvailableByGame(any(Game.class), any(Pageable.class));

        CompletableFuture<HttpResponse<String>> responseFuture = null;
        try (Connection lockHolder = dataSource.getConnection()) {
            acquireAdvisoryLock(lockHolder);
            try {
                responseFuture = sendAllocationRequest();

                assertThat(requestTrace.databaseCallStarted.await(
                        SYNCHRONIZATION_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS
                )).isTrue();
                awaitPostgresqlAdvisoryLockWait(requestTrace.database.get().backendPid());

                assertThat(responseFuture.isDone()).isFalse();

                ThreadInfo blockedThread = ManagementFactory.getThreadMXBean().getThreadInfo(
                        requestTrace.database.get().thread().id(),
                        STACK_SNAPSHOT_DEPTH
                );
                assertBlockedRequestStack(blockedThread);
                requestTrace.blockedStack.set(blockedThread);
            } finally {
                releaseAdvisoryLock(lockHolder);
                awaitQuietly(responseFuture);
            }
        }

        HttpResponse<String> response = responseFuture.get(
                HTTP_TIMEOUT_SECONDS,
                TimeUnit.SECONDS
        );
        AllocationResponse allocationResponse = objectMapper.readValue(
                response.body(),
                AllocationResponse.class
        );

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(allocationResponse.gameCode()).isEqualTo(GAME_CODE);
        assertThat(allocationResponse.keyCode()).isEqualTo(GAME_KEY_CODE);
        assertThat(allocationResponse.allocatedAt()).isNotNull();

        assertSameRequestThreadAtEveryBoundary();
        assertThat(requestTrace.requestEntry.get().transactionActive()).isFalse();
        assertThat(requestTrace.controller.get().transactionActive()).isFalse();
        assertThat(requestTrace.service.get().transactionActive()).isTrue();
        assertThat(requestTrace.repository.get().transactionActive()).isTrue();
        assertThat(requestTrace.database.get().transactionActive()).isTrue();
        assertThat(requestTrace.service.get().transactionIdentity())
                .isEqualTo(requestTrace.repository.get().transactionIdentity())
                .isEqualTo(requestTrace.database.get().transactionIdentity());
        assertThat(requestTrace.database.get().databaseProduct()).isEqualTo("PostgreSQL");
        assertThat(requestTrace.database.get().databaseVersion()).isNotBlank();

        assertThat(allocationRepository.count()).isEqualTo(1L);
        assertThat(idempotencyRecordRepository.count()).isEqualTo(1L);
        assertThat(idempotencyRecordRepository.existsByIdempotencyKey(IDEMPOTENCY_KEY)).isTrue();

        printFocusedObservation(server);
    }

    private ServerObservation observeServer() {
        assertThat(serverApplicationContext.getWebServer()).isInstanceOf(TomcatWebServer.class);
        TomcatWebServer webServer = (TomcatWebServer) serverApplicationContext.getWebServer();
        ProtocolHandler protocolHandler = webServer.getTomcat()
                .getConnector()
                .getProtocolHandler();
        assertThat(protocolHandler).isInstanceOf(AbstractProtocol.class);

        AbstractProtocol<?> protocol = (AbstractProtocol<?>) protocolHandler;
        assertThat(protocol.getExecutor()).isInstanceOf(ThreadPoolExecutor.class);
        assertThat(protocol.getMaxThreads()).isPositive();
        assertThat(protocol.getAcceptCount()).isPositive();
        assertThat(protocol.getMaxConnections()).isPositive();

        return new ServerObservation(
                webServer.getClass().getName(),
                protocol.getClass().getName(),
                protocol.getExecutor().getClass().getName(),
                protocol.getMaxThreads(),
                protocol.getAcceptCount(),
                protocol.getMaxConnections()
        );
    }

    private CompletableFuture<HttpResponse<String>> sendAllocationRequest() throws Exception {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(SYNCHRONIZATION_TIMEOUT_SECONDS))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/games/" + GAME_CODE + "/allocations"))
                .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(
                        new AllocationRequest(IDEMPOTENCY_KEY)
                )))
                .build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    }

    private void blockOnPostgresqlAdvisoryLock() {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            int backendPid;
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("select pg_backend_pid()")) {
                assertThat(resultSet.next()).isTrue();
                backendPid = resultSet.getInt(1);
            }

            requestTrace.recordDatabase(
                    backendPid,
                    connection.getMetaData().getDatabaseProductName(),
                    connection.getMetaData().getDatabaseProductVersion(),
                    System.identityHashCode(connection)
            );
            requestTrace.databaseCallStarted.countDown();

            try (PreparedStatement statement = connection.prepareStatement(
                    "select pg_advisory_xact_lock(?)"
            )) {
                statement.setLong(1, ADVISORY_LOCK_KEY);
                statement.setQueryTimeout(Math.toIntExact(DATABASE_BLOCK_TIMEOUT_SECONDS));
                statement.execute();
            }
            return null;
        });
    }

    private void acquireAdvisoryLock(Connection connection) throws SQLException {
        connection.setAutoCommit(true);
        try (PreparedStatement statement = connection.prepareStatement(
                "select pg_advisory_lock(?)"
        )) {
            statement.setLong(1, ADVISORY_LOCK_KEY);
            statement.setQueryTimeout(Math.toIntExact(SYNCHRONIZATION_TIMEOUT_SECONDS));
            statement.execute();
        }
    }

    private void releaseAdvisoryLock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select pg_advisory_unlock(?)"
        )) {
            statement.setLong(1, ADVISORY_LOCK_KEY);
            statement.setQueryTimeout(Math.toIntExact(SYNCHRONIZATION_TIMEOUT_SECONDS));
            statement.execute();
        }
    }

    private void awaitPostgresqlAdvisoryLockWait(int backendPid) throws Exception {
        awaitCondition(() -> Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                """
                        select exists (
                            select 1
                            from pg_stat_activity
                            where pid = ?
                              and wait_event_type = 'Lock'
                              and wait_event = 'advisory'
                        )
                        """,
                Boolean.class,
                backendPid
        )));
    }

    private void awaitCondition(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(
                SYNCHRONIZATION_TIMEOUT_SECONDS
        );
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private void assertBlockedRequestStack(ThreadInfo threadInfo) {
        assertThat(threadInfo).isNotNull();
        List<String> classNames = Arrays.stream(threadInfo.getStackTrace())
                .map(StackTraceElement::getClassName)
                .toList();

        assertThat(classNames).anyMatch(name -> name.startsWith("org.postgresql."));
        assertThat(classNames).anyMatch(name -> name.startsWith("com.zaxxer.hikari."));
        assertThat(classNames).anyMatch(name -> name.startsWith("org.springframework.jdbc."));
        assertThat(classNames).anyMatch(name -> name.startsWith("java.net.")
                || name.startsWith("sun.nio.ch."));
        assertThat(classNames).contains(AllocationService.class.getName());
        assertThat(classNames).contains(AllocationController.class.getName());
        assertThat(classNames).anyMatch(name -> name.startsWith("org.apache.catalina."));
    }

    private void assertSameRequestThreadAtEveryBoundary() {
        ThreadIdentity request = requestTrace.requestEntry.get().thread();
        assertThat(request).isNotNull();
        assertThat(request.name()).startsWith("http-nio-");
        assertThat(requestTrace.controller.get().thread()).isEqualTo(request);
        assertThat(requestTrace.service.get().thread()).isEqualTo(request);
        assertThat(requestTrace.repository.get().thread()).isEqualTo(request);
        assertThat(requestTrace.database.get().thread()).isEqualTo(request);
    }

    private void printFocusedObservation(ServerObservation server) {
        ThreadInfo blockedStack = requestTrace.blockedStack.get();
        List<String> focusedFrames = Arrays.stream(blockedStack.getStackTrace())
                .map(StackTraceElement::getClassName)
                .filter(name -> name.startsWith("com.shiv.securegkd.")
                        || name.startsWith("org.springframework.jdbc.")
                        || name.startsWith("org.postgresql.")
                        || name.startsWith("com.zaxxer.hikari.")
                        || name.startsWith("java.net.")
                        || name.startsWith("sun.nio.ch.")
                        || name.startsWith("org.apache.catalina."))
                .distinct()
                .toList();

        System.out.printf(
                "REQUEST_THREAD_OBSERVATION server=%s protocol=%s executor=%s "
                        + "maxThreads=%d acceptCount=%d maxConnections=%d%n",
                server.webServerClass(),
                server.protocolClass(),
                server.executorClass(),
                server.maxThreads(),
                server.acceptCount(),
                server.maxConnections()
        );
        System.out.printf(
                "REQUEST_THREAD_OBSERVATION threadName=%s threadId=%d threadIdentity=%d "
                        + "state=%s transaction=%s jdbcConnectionIdentity=%d "
                        + "postgresqlBackendPid=%d database=%s version=%s%n",
                requestTrace.database.get().thread().name(),
                requestTrace.database.get().thread().id(),
                requestTrace.database.get().thread().identityHash(),
                blockedStack.getThreadState(),
                requestTrace.database.get().transactionName(),
                requestTrace.database.get().jdbcConnectionIdentity(),
                requestTrace.database.get().backendPid(),
                requestTrace.database.get().databaseProduct(),
                requestTrace.database.get().databaseVersion()
        );
        System.out.printf(
                "REQUEST_THREAD_OBSERVATION transactionActive requestEntry=%s controller=%s "
                        + "service=%s repository=%s database=%s transactionIdentity=%d%n",
                requestTrace.requestEntry.get().transactionActive(),
                requestTrace.controller.get().transactionActive(),
                requestTrace.service.get().transactionActive(),
                requestTrace.repository.get().transactionActive(),
                requestTrace.database.get().transactionActive(),
                requestTrace.database.get().transactionIdentity()
        );
        System.out.println("REQUEST_THREAD_OBSERVATION focusedFrames=" + focusedFrames);
    }

    private void awaitQuietly(CompletableFuture<HttpResponse<String>> responseFuture) {
        if (responseFuture == null || responseFuture.isDone()) {
            return;
        }
        try {
            responseFuture.get(HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            responseFuture.cancel(true);
        }
    }

    private void deleteTestData() {
        idempotencyRecordRepository.deleteAllInBatch();
        allocationRepository.deleteAllInBatch();
        gameKeyRepository.deleteAllInBatch();
        gameRepository.deleteAllInBatch();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RequestTraceConfiguration {

        @Bean
        RequestTrace requestTrace() {
            return new RequestTrace();
        }

        @Bean
        FilterRegistrationBean<OncePerRequestFilter> requestEntryTraceFilter(
                RequestTrace requestTrace
        ) {
            OncePerRequestFilter filter = new OncePerRequestFilter() {
                @Override
                protected void doFilterInternal(
                        HttpServletRequest request,
                        HttpServletResponse response,
                        FilterChain filterChain
                ) throws ServletException, IOException {
                    if ("POST".equals(request.getMethod())
                            && request.getRequestURI().endsWith("/allocations")) {
                        requestTrace.recordRequestEntry();
                    }
                    filterChain.doFilter(request, response);
                }
            };
            FilterRegistrationBean<OncePerRequestFilter> registration =
                    new FilterRegistrationBean<>(filter);
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
            return registration;
        }
    }

    static final class RequestTrace {
        private final AtomicReference<BoundaryObservation> requestEntry = new AtomicReference<>();
        private final AtomicReference<BoundaryObservation> controller = new AtomicReference<>();
        private final AtomicReference<BoundaryObservation> service = new AtomicReference<>();
        private final AtomicReference<BoundaryObservation> repository = new AtomicReference<>();
        private final AtomicReference<DatabaseObservation> database = new AtomicReference<>();
        private final AtomicReference<ThreadInfo> blockedStack = new AtomicReference<>();
        private CountDownLatch databaseCallStarted = new CountDownLatch(1);

        void reset() {
            requestEntry.set(null);
            controller.set(null);
            service.set(null);
            repository.set(null);
            database.set(null);
            blockedStack.set(null);
            databaseCallStarted = new CountDownLatch(1);
        }

        void recordRequestEntry() {
            requestEntry.set(BoundaryObservation.current());
        }

        void recordController() {
            controller.set(BoundaryObservation.current());
        }

        void recordService() {
            service.set(BoundaryObservation.current());
        }

        void recordRepository() {
            repository.set(BoundaryObservation.current());
        }

        void recordDatabase(
                int backendPid,
                String databaseProduct,
                String databaseVersion,
                int jdbcConnectionIdentity
        ) {
            BoundaryObservation boundary = BoundaryObservation.current();
            database.set(new DatabaseObservation(
                    boundary.thread(),
                    boundary.transactionActive(),
                    boundary.transactionName(),
                    boundary.transactionIdentity(),
                    backendPid,
                    databaseProduct,
                    databaseVersion,
                    jdbcConnectionIdentity
            ));
        }
    }

    private record ThreadIdentity(long id, String name, int identityHash) {
        static ThreadIdentity current() {
            Thread thread = Thread.currentThread();
            return new ThreadIdentity(
                    thread.getId(),
                    thread.getName(),
                    System.identityHashCode(thread)
            );
        }
    }

    private record BoundaryObservation(
            ThreadIdentity thread,
            boolean transactionActive,
            String transactionName,
            int transactionIdentity
    ) {
        static BoundaryObservation current() {
            boolean transactionActive = TransactionSynchronizationManager
                    .isActualTransactionActive();
            int transactionIdentity = transactionActive
                    ? System.identityHashCode(TransactionAspectSupport.currentTransactionStatus())
                    : 0;
            return new BoundaryObservation(
                    ThreadIdentity.current(),
                    transactionActive,
                    TransactionSynchronizationManager.getCurrentTransactionName(),
                    transactionIdentity
            );
        }
    }

    private record DatabaseObservation(
            ThreadIdentity thread,
            boolean transactionActive,
            String transactionName,
            int transactionIdentity,
            int backendPid,
            String databaseProduct,
            String databaseVersion,
            int jdbcConnectionIdentity
    ) {
    }

    private record ServerObservation(
            String webServerClass,
            String protocolClass,
            String executorClass,
            int maxThreads,
            int acceptCount,
            int maxConnections
    ) {
    }
}
