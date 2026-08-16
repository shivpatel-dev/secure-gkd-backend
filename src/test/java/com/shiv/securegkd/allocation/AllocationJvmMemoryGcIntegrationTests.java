package com.shiv.securegkd.allocation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shiv.securegkd.security.AllocationTestSecurityConfiguration;
import com.shiv.securegkd.game.Game;
import com.shiv.securegkd.game.GameRepository;
import com.shiv.securegkd.gamekey.GameKey;
import com.shiv.securegkd.gamekey.GameKeyRepository;
import com.shiv.securegkd.idempotency.IdempotencyRecordRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(AllocationTestSecurityConfiguration.class)
class AllocationJvmMemoryGcIntegrationTests {

    private static final Logger LOGGER = LoggerFactory.getLogger(AllocationJvmMemoryGcIntegrationTests.class);
    private static final String GAME_CODE = "JVM-MEMORY-GAME";
    private static final String GAME_KEY_PREFIX = "JVM-MEMORY-KEY-";
    private static final String IDEMPOTENCY_KEY_PREFIX = "jvm-memory-55-";
    private static final int REQUEST_COUNT = 10;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

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

    @Autowired
    private GameKeyRepository gameKeyRepository;

    @Autowired
    private AllocationRepository allocationRepository;

    @Autowired
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @BeforeEach
    void setUp() {
        deleteTestData();

        Game game = gameRepository.saveAndFlush(new Game(GAME_CODE, "JVM Memory Observation Game"));
        List<GameKey> gameKeys = IntStream.rangeClosed(1, REQUEST_COUNT)
                .mapToObj(index -> new GameKey(game, GAME_KEY_PREFIX + "%03d".formatted(index)))
                .toList();
        gameKeyRepository.saveAllAndFlush(gameKeys);
    }

    @AfterEach
    void tearDown() {
        deleteTestData();
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void observesJvmMemoryAndGcAcrossBoundedRealHttpAllocations() throws Exception {
        String databaseProduct;
        String databaseVersion;
        try (var connection = dataSource.getConnection()) {
            var metadata = connection.getMetaData();
            databaseProduct = metadata.getDatabaseProductName();
            databaseVersion = metadata.getDatabaseProductVersion();
        }

        assertThat(databaseProduct).isEqualTo("PostgreSQL");
        assertThat(databaseVersion).isNotBlank();

        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        List<MemoryPoolMXBean> memoryPoolBeans = ManagementFactory.getMemoryPoolMXBeans();
        List<GarbageCollectorMXBean> garbageCollectorBeans = ManagementFactory.getGarbageCollectorMXBeans();

        assertThat(memoryBean).isNotNull();
        assertThat(memoryPoolBeans).isNotEmpty();
        assertThat(garbageCollectorBeans).isNotEmpty();

        LOGGER.info(
                "JVM memory observation environment: Java {}, database {} {}",
                System.getProperty("java.version"),
                databaseProduct,
                databaseVersion
        );

        MemorySnapshot beforeRequests = captureSnapshot(
                "fixtures-ready-before-requests",
                memoryBean,
                memoryPoolBeans,
                garbageCollectorBeans
        );

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        List<AllocationResponse> retainedResponses = new ArrayList<>(REQUEST_COUNT);

        for (int index = 1; index <= REQUEST_COUNT; index++) {
            String idempotencyKey = IDEMPOTENCY_KEY_PREFIX + "%02d".formatted(index);
            String requestJson = objectMapper.writeValueAsString(new AllocationRequest(idempotencyKey));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/api/games/" + GAME_CODE + "/allocations"))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(201);
            AllocationResponse allocationResponse = objectMapper.readValue(
                    response.body(),
                    AllocationResponse.class
            );
            assertThat(allocationResponse.gameCode()).isEqualTo(GAME_CODE);
            assertThat(allocationResponse.keyCode()).startsWith(GAME_KEY_PREFIX);
            assertThat(allocationResponse.allocatedAt()).isNotNull();
            retainedResponses.add(allocationResponse);
        }

        assertThat(retainedResponses).hasSize(REQUEST_COUNT);
        assertThat(retainedResponses)
                .extracting(AllocationResponse::keyCode)
                .containsExactlyInAnyOrderElementsOf(expectedGameKeyCodes());

        MemorySnapshot whileResponsesRetained = captureSnapshot(
                "requests-complete-responses-retained",
                memoryBean,
                memoryPoolBeans,
                garbageCollectorBeans
        );

        assertThat(allocationRepository.count()).isEqualTo(REQUEST_COUNT);
        assertThat(idempotencyRecordRepository.count()).isEqualTo(REQUEST_COUNT);
        assertThat(jdbcTemplate.queryForObject("""
                select count(*)
                from allocations allocation
                join game_keys game_key on game_key.id = allocation.game_key_id
                join games game on game.id = game_key.game_id
                where game.code = ?
                """, Long.class, GAME_CODE)).isEqualTo(REQUEST_COUNT);
        assertThat(jdbcTemplate.queryForObject("""
                select count(*)
                from idempotency_records idempotency_record
                join allocations allocation on allocation.id = idempotency_record.allocation_id
                join game_keys game_key on game_key.id = allocation.game_key_id
                join games game on game.id = game_key.game_id
                where game.code = ?
                and idempotency_record.idempotency_key like ?
                """, Long.class, GAME_CODE, IDEMPOTENCY_KEY_PREFIX + "%"))
                .isEqualTo(REQUEST_COUNT);
        for (int index = 1; index <= REQUEST_COUNT; index++) {
            assertThat(idempotencyRecordRepository.existsByIdempotencyKey(
                    IDEMPOTENCY_KEY_PREFIX + "%02d".formatted(index)
            )).isTrue();
        }

        retainedResponses.clear();
        assertThat(retainedResponses).isEmpty();

        MemorySnapshot afterResponsesCleared = captureSnapshot(
                "retained-response-collection-cleared",
                memoryBean,
                memoryPoolBeans,
                garbageCollectorBeans
        );

        assertThat(List.of(beforeRequests, whileResponsesRetained, afterResponsesCleared))
                .extracting(MemorySnapshot::checkpoint)
                .containsExactly(
                        "fixtures-ready-before-requests",
                        "requests-complete-responses-retained",
                        "retained-response-collection-cleared"
                );
    }

    private MemorySnapshot captureSnapshot(
            String checkpoint,
            MemoryMXBean memoryBean,
            List<MemoryPoolMXBean> memoryPoolBeans,
            List<GarbageCollectorMXBean> garbageCollectorBeans
    ) {
        UsageObservation heap = UsageObservation.from(memoryBean.getHeapMemoryUsage());
        UsageObservation nonHeap = UsageObservation.from(memoryBean.getNonHeapMemoryUsage());
        List<PoolObservation> pools = memoryPoolBeans.stream()
                .map(bean -> {
                    MemoryUsage usage = bean.getUsage();
                    return new PoolObservation(
                            bean.getName(),
                            bean.getType(),
                            bean.isValid(),
                            usage == null ? null : UsageObservation.from(usage)
                    );
                })
                .toList();
        List<CollectorObservation> collectors = garbageCollectorBeans.stream()
                .map(bean -> new CollectorObservation(
                        bean.getName(),
                        List.of(bean.getMemoryPoolNames()),
                        bean.getCollectionCount(),
                        bean.getCollectionTime()
                ))
                .toList();

        assertValidUsage(heap);
        assertValidUsage(nonHeap);
        assertThat(pools).isNotEmpty().allSatisfy(pool -> {
            assertThat(pool.name()).isNotBlank();
            assertThat(pool.type()).isIn(MemoryType.HEAP, MemoryType.NON_HEAP);
            if (pool.usage() != null) {
                assertValidUsage(pool.usage());
            }
        });
        assertThat(collectors).isNotEmpty().allSatisfy(collector -> {
            assertThat(collector.name()).isNotBlank();
            assertThat(collector.memoryPoolNames()).doesNotContainNull();
            assertThat(collector.collectionCount()).isGreaterThanOrEqualTo(-1L);
            assertThat(collector.collectionTimeMillis()).isGreaterThanOrEqualTo(-1L);
        });

        MemorySnapshot snapshot = new MemorySnapshot(checkpoint, heap, nonHeap, pools, collectors);
        LOGGER.info("JVM memory checkpoint: {}", snapshot.summary());
        return snapshot;
    }

    private void assertValidUsage(UsageObservation usage) {
        assertThat(usage.used()).isGreaterThanOrEqualTo(0L);
        assertThat(usage.committed()).isGreaterThanOrEqualTo(usage.used());
        assertThat(usage.maximum()).satisfies(maximum -> {
            if (maximum != -1L) {
                assertThat(maximum).isGreaterThanOrEqualTo(usage.committed());
            }
        });
    }

    private Set<String> expectedGameKeyCodes() {
        return IntStream.rangeClosed(1, REQUEST_COUNT)
                .mapToObj(index -> GAME_KEY_PREFIX + "%03d".formatted(index))
                .collect(Collectors.toSet());
    }

    private void deleteTestData() {
        idempotencyRecordRepository.deleteAllInBatch();
        allocationRepository.deleteAllInBatch();
        gameKeyRepository.deleteAllInBatch();
        gameRepository.deleteAllInBatch();
    }

    private record MemorySnapshot(
            String checkpoint,
            UsageObservation heap,
            UsageObservation nonHeap,
            List<PoolObservation> pools,
            List<CollectorObservation> collectors
    ) {
        String summary() {
            String poolSummary = pools.stream()
                    .map(PoolObservation::summary)
                    .collect(Collectors.joining(", "));
            String collectorSummary = collectors.stream()
                    .map(CollectorObservation::summary)
                    .collect(Collectors.joining(", "));
            return "%s; heap[%s]; non-heap[%s]; pools={%s}; collectors={%s}".formatted(
                    checkpoint,
                    heap.summary(),
                    nonHeap.summary(),
                    poolSummary,
                    collectorSummary
            );
        }
    }

    private record PoolObservation(
            String name,
            MemoryType type,
            boolean valid,
            UsageObservation usage
    ) {
        String summary() {
            return "%s[type=%s, valid=%s, %s]".formatted(
                    name,
                    type,
                    valid,
                    usage == null ? "usage=unavailable" : usage.summary()
            );
        }
    }

    private record CollectorObservation(
            String name,
            List<String> memoryPoolNames,
            long collectionCount,
            long collectionTimeMillis
    ) {
        String summary() {
            return "%s[count=%s, time-ms=%s]".formatted(
                    name,
                    definedValue(collectionCount),
                    definedValue(collectionTimeMillis)
            );
        }
    }

    private record UsageObservation(long used, long committed, long maximum) {
        static UsageObservation from(MemoryUsage usage) {
            return new UsageObservation(usage.getUsed(), usage.getCommitted(), usage.getMax());
        }

        String summary() {
            return "used=%s, committed=%s, max=%s".formatted(
                    definedValue(used),
                    definedValue(committed),
                    definedValue(maximum)
            );
        }
    }

    private static String definedValue(long value) {
        return value < 0L ? "undefined" : Long.toString(value);
    }
}
