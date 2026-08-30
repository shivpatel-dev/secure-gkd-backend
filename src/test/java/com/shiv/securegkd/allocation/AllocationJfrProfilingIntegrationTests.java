package com.shiv.securegkd.allocation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shiv.securegkd.security.AllocationTestSecurityConfiguration;
import com.shiv.securegkd.game.Game;
import com.shiv.securegkd.game.GameRepository;
import com.shiv.securegkd.gamekey.GameKey;
import com.shiv.securegkd.gamekey.GameKeyRepository;
import com.shiv.securegkd.idempotency.IdempotencyRecordRepository;
import com.shiv.securegkd.allocation.outbox.AllocationOutboxRepository;
import jdk.jfr.Configuration;
import jdk.jfr.EventType;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;
import jdk.jfr.RecordingState;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(AllocationTestSecurityConfiguration.class)
class AllocationJfrProfilingIntegrationTests {

    private static final Logger LOGGER = LoggerFactory.getLogger(AllocationJfrProfilingIntegrationTests.class);
    private static final String GAME_CODE = "JFR-PROFILE-GAME";
    private static final String GAME_KEY_PREFIX = "JFR-PROFILE-KEY-";
    private static final String IDEMPOTENCY_KEY_PREFIX = "jfr-profile-57-";
    private static final String RECORDING_NAME = "bounded-allocation-requests-issue-57";
    private static final String RECORDING_CONFIGURATION = "profile";
    private static final int REQUEST_COUNT = 4;
    private static final long MAX_RECORDING_SIZE_BYTES = 32L * 1024L * 1024L;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration RECORDING_MAX_DURATION = Duration.ofSeconds(75);
    private static final Duration SAMPLE_PERIOD = Duration.ofMillis(10);
    private static final Duration CPU_LOAD_PERIOD = Duration.ofMillis(20);
    private static final Duration BLOCKING_THRESHOLD = Duration.ofMillis(10);
    private static final Duration SOCKET_IO_THRESHOLD = Duration.ZERO;
    private static final Duration FILE_IO_THRESHOLD = Duration.ofMillis(10);

    private static final List<EventCategory> RELEVANT_EVENT_CATEGORIES = List.of(
            new EventCategory("sampled execution", Set.of(
                    "jdk.ExecutionSample",
                    "jdk.NativeMethodSample"
            )),
            new EventCategory("Java thread lifecycle", Set.of(
                    "jdk.ThreadStart",
                    "jdk.ThreadEnd",
                    "jdk.JavaThreadStatistics"
            )),
            new EventCategory("blocking or waiting", Set.of(
                    "jdk.ThreadPark",
                    "jdk.JavaMonitorEnter",
                    "jdk.JavaMonitorWait"
            )),
            new EventCategory("socket I/O", Set.of(
                    "jdk.SocketRead",
                    "jdk.SocketWrite"
            )),
            new EventCategory("file I/O", Set.of(
                    "jdk.FileRead",
                    "jdk.FileWrite",
                    "jdk.FileForce"
            )),
            new EventCategory("CPU load", Set.of(
                    "jdk.CPULoad",
                    "jdk.ThreadCPULoad"
            )),
            new EventCategory("sampled object allocation", Set.of(
                    "jdk.ObjectAllocationSample",
                    "jdk.ObjectAllocationInNewTLAB",
                    "jdk.ObjectAllocationOutsideTLAB"
            )),
            new EventCategory("garbage collection", Set.of(
                    "jdk.GarbageCollection"
            )),
            new EventCategory("garbage-collection pauses", Set.of(
                    "jdk.GCPhasePause",
                    "jdk.GCPhasePauseLevel1",
                    "jdk.GCPhasePauseLevel2",
                    "jdk.GCPhasePauseLevel3",
                    "jdk.GCPhasePauseLevel4"
            )),
            new EventCategory("JVM and operating-system metadata", Set.of(
                    "jdk.JVMInformation",
                    "jdk.OSInformation",
                    "jdk.CPUInformation",
                    "jdk.PhysicalMemory",
                    "jdk.VirtualizationInformation"
            ))
    );

    private static final Map<String, Predicate<String>> FRAME_AREAS = Map.ofEntries(
            Map.entry("project", name -> name.startsWith("com.shiv.securegkd")),
            Map.entry("Spring MVC/transactions", name -> name.startsWith("org.springframework.web")
                    || name.startsWith("org.springframework.transaction")),
            Map.entry("Spring Data JPA", name -> name.startsWith("org.springframework.data")),
            Map.entry("Hibernate", name -> name.startsWith("org.hibernate")
                    && !name.startsWith("org.hibernate.validator")),
            Map.entry("HikariCP", name -> name.startsWith("com.zaxxer.hikari")),
            Map.entry("PostgreSQL JDBC", name -> name.startsWith("org.postgresql")),
            Map.entry("embedded Tomcat", name -> name.startsWith("org.apache.catalina")
                    || name.startsWith("org.apache.coyote")
                    || name.startsWith("org.apache.tomcat")),
            Map.entry("Java HTTP client", name -> name.startsWith("java.net.http")
                    || name.startsWith("jdk.internal.net.http"))
    );

    @LocalServerPort
    private int port;

    @TempDir
    private Path temporaryDirectory;

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

    @Autowired
    private AllocationOutboxRepository allocationOutboxRepository;

    @BeforeEach
    void setUp() {
        deleteTestData();

        Game game = gameRepository.saveAndFlush(new Game(GAME_CODE, "JFR Profiling Observation Game"));
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
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void profilesBoundedRealHttpAllocationRequests() throws Exception {
        DatabaseObservation database = observeDatabase();
        assertThat(database.product()).isEqualTo("PostgreSQL");
        assertThat(database.version()).isNotBlank();
        assertThat(FlightRecorder.isAvailable()).isTrue();

        Configuration configuration = Configuration.getConfiguration(RECORDING_CONFIGURATION);
        assertThat(configuration.getName()).isEqualTo(RECORDING_CONFIGURATION);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        initializeServlet(client);
        List<HttpRequest> requests = buildRequests();
        Path recordingPath = temporaryDirectory.resolve("bounded-allocation-requests.jfr");

        try (Recording recording = new Recording(configuration)) {
            configureRecording(recording);
            Map<String, String> relevantSettings = relevantSettings(recording.getSettings());
            assertThat(relevantSettings).isNotEmpty();

            recording.start();
            assertThat(recording.getState()).isEqualTo(RecordingState.RUNNING);

            SequenceObservation sequence;
            try {
                sequence = executeRequests(client, requests);
            } finally {
                if (recording.getState() == RecordingState.RUNNING) {
                    recording.stop();
                }
            }

            assertThat(recording.getState()).isEqualTo(RecordingState.STOPPED);
            Instant recordingStart = recording.getStartTime();
            Instant recordingStop = recording.getStopTime();
            assertThat(recordingStart).isNotNull();
            assertThat(recordingStop).isAfter(recordingStart);
            Duration recordingDuration = Duration.between(recordingStart, recordingStop);
            assertThat(recordingDuration).isPositive();

            recording.dump(recordingPath);
            assertThat(recordingPath).isRegularFile();
            long recordingSize = Files.size(recordingPath);
            assertThat(recordingSize).isPositive();

            ParsedRecording parsed = parseRecording(recordingPath);
            assertThat(parsed.eventTypes()).isNotEmpty().allSatisfy(eventType -> {
                assertThat(eventType.getName()).isNotBlank();
                assertThat(eventType.getFields()).isNotNull();
            });
            assertThat(parsed.events()).isNotEmpty();
            assertThat(parsed.eventCounts()).isNotEmpty();

            assertFunctionalOutcome(sequence);
            assertDatabaseOutcome();
            logObservations(
                    database,
                    recording,
                    configuration,
                    relevantSettings,
                    recordingDuration,
                    recordingSize,
                    sequence,
                    parsed
            );
        } finally {
            Files.deleteIfExists(recordingPath);
        }

        assertThat(recordingPath).doesNotExist();
    }

    private DatabaseObservation observeDatabase() throws Exception {
        try (var connection = dataSource.getConnection()) {
            var metadata = connection.getMetaData();
            return new DatabaseObservation(
                    metadata.getDatabaseProductName(),
                    metadata.getDatabaseProductVersion()
            );
        }
    }

    private List<HttpRequest> buildRequests() throws Exception {
        List<HttpRequest> requests = new ArrayList<>(REQUEST_COUNT);
        for (int index = 1; index <= REQUEST_COUNT; index++) {
            String idempotencyKey = IDEMPOTENCY_KEY_PREFIX + "%02d".formatted(index);
            String requestJson = objectMapper.writeValueAsString(new AllocationRequest(idempotencyKey));
            requests.add(HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/api/games/" + GAME_CODE + "/allocations"))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                    .build());
        }
        return requests;
    }

    private void initializeServlet(HttpClient client) throws Exception {
        HttpRequest healthRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/health"))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        HttpResponse<Void> healthResponse = client.send(healthRequest, HttpResponse.BodyHandlers.discarding());
        assertThat(healthResponse.statusCode()).isEqualTo(200);
    }

    private void configureRecording(Recording recording) {
        recording.setName(RECORDING_NAME);
        recording.setToDisk(true);
        recording.setDuration(RECORDING_MAX_DURATION);
        recording.setMaxSize(MAX_RECORDING_SIZE_BYTES);

        recording.enable("jdk.ExecutionSample").withPeriod(SAMPLE_PERIOD).withStackTrace();
        recording.enable("jdk.NativeMethodSample").withPeriod(SAMPLE_PERIOD).withStackTrace();
        recording.enable("jdk.ThreadPark").withThreshold(BLOCKING_THRESHOLD).withStackTrace();
        recording.enable("jdk.JavaMonitorEnter").withThreshold(BLOCKING_THRESHOLD).withStackTrace();
        recording.enable("jdk.JavaMonitorWait").withThreshold(BLOCKING_THRESHOLD).withStackTrace();
        recording.enable("jdk.SocketRead").withThreshold(SOCKET_IO_THRESHOLD).withStackTrace();
        recording.enable("jdk.SocketWrite").withThreshold(SOCKET_IO_THRESHOLD).withStackTrace();
        recording.enable("jdk.FileRead").withThreshold(FILE_IO_THRESHOLD).withStackTrace();
        recording.enable("jdk.FileWrite").withThreshold(FILE_IO_THRESHOLD).withStackTrace();
        recording.enable("jdk.CPULoad").withPeriod(CPU_LOAD_PERIOD);
        recording.enable("jdk.ObjectAllocationSample").with("throttle", "100/s").withStackTrace();
    }

    private SequenceObservation executeRequests(HttpClient client, List<HttpRequest> requests) throws Exception {
        List<RequestObservation> observations = new ArrayList<>(REQUEST_COUNT);
        long sequenceStart = System.nanoTime();

        for (int index = 0; index < requests.size(); index++) {
            long requestStart = System.nanoTime();
            HttpResponse<String> response = client.send(
                    requests.get(index),
                    HttpResponse.BodyHandlers.ofString()
            );
            long requestElapsedNanos = System.nanoTime() - requestStart;
            observations.add(new RequestObservation(
                    index + 1,
                    IDEMPOTENCY_KEY_PREFIX + "%02d".formatted(index + 1),
                    response.statusCode(),
                    response.body(),
                    requestElapsedNanos
            ));
        }

        return new SequenceObservation(observations, System.nanoTime() - sequenceStart);
    }

    private ParsedRecording parseRecording(Path recordingPath) throws IOException {
        List<EventType> eventTypes;
        List<RecordedEvent> events = new ArrayList<>();
        try (RecordingFile recordingFile = new RecordingFile(recordingPath)) {
            eventTypes = recordingFile.readEventTypes();
            while (recordingFile.hasMoreEvents()) {
                events.add(recordingFile.readEvent());
            }
        }

        Map<String, Long> eventCounts = events.stream()
                .collect(Collectors.groupingBy(
                        event -> event.getEventType().getName(),
                        TreeMap::new,
                        Collectors.counting()
                ));
        return new ParsedRecording(eventTypes, events, eventCounts);
    }

    private void assertFunctionalOutcome(SequenceObservation sequence) throws Exception {
        assertThat(sequence.requests()).hasSize(REQUEST_COUNT);
        assertThat(sequence.elapsedNanos()).isPositive();

        List<AllocationResponse> allocationResponses = new ArrayList<>(REQUEST_COUNT);
        for (RequestObservation request : sequence.requests()) {
            assertThat(request.statusCode()).isEqualTo(201);
            assertThat(request.elapsedNanos()).isPositive();
            AllocationResponse response = objectMapper.readValue(request.responseBody(), AllocationResponse.class);
            assertThat(response.gameCode()).isEqualTo(GAME_CODE);
            assertThat(response.keyCode()).startsWith(GAME_KEY_PREFIX);
            assertThat(response.allocatedAt()).isNotNull();
            allocationResponses.add(response);
        }

        assertThat(allocationResponses)
                .extracting(AllocationResponse::keyCode)
                .containsExactlyInAnyOrderElementsOf(expectedGameKeyCodes());
    }

    private void assertDatabaseOutcome() {
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
    }

    private void logObservations(
            DatabaseObservation database,
            Recording recording,
            Configuration configuration,
            Map<String, String> relevantSettings,
            Duration recordingDuration,
            long recordingSize,
            SequenceObservation sequence,
            ParsedRecording parsed
    ) {
        LOGGER.info(
                "JFR environment: Java {} {}, JVM {}, process {}, database {} {}",
                System.getProperty("java.vendor"),
                System.getProperty("java.version"),
                System.getProperty("java.vm.name"),
                ProcessHandle.current().pid(),
                database.product(),
                database.version()
        );
        LOGGER.info(
                "JFR recording: id={}, name={}, configuration={} ({}), max-duration={}, max-size-bytes={}, "
                        + "actual-duration-ns={}, temporary-size-bytes={}",
                recording.getId(),
                recording.getName(),
                configuration.getName(),
                configuration.getLabel(),
                RECORDING_MAX_DURATION,
                MAX_RECORDING_SIZE_BYTES,
                recordingDuration.toNanos(),
                recordingSize
        );
        LOGGER.info("JFR relevant settings: {}", relevantSettings);
        sequence.requests().forEach(request -> LOGGER.info(
                "Client-observed request {} elapsed-ms={} status={} idempotency-key={}",
                request.index(),
                milliseconds(request.elapsedNanos()),
                request.statusCode(),
                request.idempotencyKey()
        ));
        LOGGER.info(
                "Client-observed bounded sequence: requests={}, elapsed-ms={}",
                sequence.requests().size(),
                milliseconds(sequence.elapsedNanos())
        );
        LOGGER.info("JFR observed event counts: {}", parsed.eventCounts());

        Map<String, Long> categoryCounts = new LinkedHashMap<>();
        for (EventCategory category : RELEVANT_EVENT_CATEGORIES) {
            long count = category.eventNames().stream()
                    .mapToLong(eventName -> parsed.eventCounts().getOrDefault(eventName, 0L))
                    .sum();
            categoryCounts.put(category.label(), count);
        }
        LOGGER.info("JFR relevant category counts (zero means not observed): {}", categoryCounts);
        LOGGER.info("JFR useful observed frames: {}", usefulFrameCounts(parsed.events()));
        LOGGER.info("JFR event-thread roles: {}", summarizeThreadRoles(parsed.events()));
    }

    private Map<String, String> relevantSettings(Map<String, String> settings) {
        Set<String> eventNames = RELEVANT_EVENT_CATEGORIES.stream()
                .flatMap(category -> category.eventNames().stream())
                .collect(Collectors.toSet());
        return settings.entrySet().stream()
                .filter(entry -> eventNames.stream().anyMatch(
                        eventName -> entry.getKey().startsWith(eventName + "#")
                ))
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (left, right) -> right,
                        TreeMap::new
                ));
    }

    private Map<String, List<String>> usefulFrameCounts(List<RecordedEvent> events) {
        Set<String> stackBearingEventNames = RELEVANT_EVENT_CATEGORIES.stream()
                .filter(category -> category.label().equals("sampled execution")
                        || category.label().equals("blocking or waiting")
                        || category.label().equals("socket I/O")
                        || category.label().equals("file I/O"))
                .flatMap(category -> category.eventNames().stream())
                .collect(Collectors.toSet());

        Map<String, Map<String, Long>> frameCountsByArea = new TreeMap<>();
        events.stream()
                .filter(event -> stackBearingEventNames.contains(event.getEventType().getName()))
                .forEach(event -> frames(event).forEach(frame -> {
                    String className = frame.getMethod().getType().getName();
                    FRAME_AREAS.forEach((area, predicate) -> {
                        if (predicate.test(className)) {
                            String frameName = className + "." + frame.getMethod().getName();
                            String areaKey = event.getEventType().getName() + " | " + area;
                            frameCountsByArea.computeIfAbsent(areaKey, ignored -> new TreeMap<>())
                                    .merge(frameName, 1L, Long::sum);
                        }
                    });
                }));
        Map<String, List<String>> summaries = new TreeMap<>();
        frameCountsByArea.forEach((area, frameCounts) -> summaries.put(
                area,
                frameCounts.entrySet().stream()
                        .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                                .thenComparing(Map.Entry.comparingByKey()))
                        .limit(3)
                        .map(entry -> entry.getKey() + " (" + entry.getValue() + ")")
                        .toList()
        ));
        return summaries;
    }

    private Map<ThreadRole, ThreadRoleSummary> summarizeThreadRoles(List<RecordedEvent> events) {
        Map<ThreadRole, Long> counts = new EnumMap<>(ThreadRole.class);
        Map<ThreadRole, Set<String>> names = new EnumMap<>(ThreadRole.class);
        for (RecordedEvent event : events) {
            RecordedThread thread = event.getThread();
            if (thread == null) {
                continue;
            }
            String threadName = thread.getJavaName() == null ? thread.getOSName() : thread.getJavaName();
            ThreadRole role = classifyThread(threadName, frames(event));
            counts.merge(role, 1L, Long::sum);
            names.computeIfAbsent(role, ignored -> new LinkedHashSet<>()).add(threadName);
        }

        Map<ThreadRole, ThreadRoleSummary> summaries = new EnumMap<>(ThreadRole.class);
        for (ThreadRole role : ThreadRole.values()) {
            summaries.put(role, new ThreadRoleSummary(
                    counts.getOrDefault(role, 0L),
                    names.getOrDefault(role, Set.of())
            ));
        }
        return summaries;
    }

    private ThreadRole classifyThread(String threadName, List<RecordedFrame> frames) {
        if (threadName != null && threadName.matches("http-nio-.*-exec-\\d+")) {
            return ThreadRole.SERVLET_REQUEST;
        }
        if (hasFrame(frames, name -> name.equals("com.shiv.securegkd.allocation.AllocationController")
                || name.equals("org.springframework.web.servlet.DispatcherServlet"))) {
            return ThreadRole.SERVLET_REQUEST;
        }
        if (threadName != null && (threadName.equals("main") || threadName.startsWith("HttpClient-"))) {
            return ThreadRole.TEST_CLIENT;
        }
        if (hasFrame(frames, name -> name.startsWith("java.net.http")
                || name.startsWith("jdk.internal.net.http")
                || name.equals(AllocationJfrProfilingIntegrationTests.class.getName()))) {
            return ThreadRole.TEST_CLIENT;
        }
        if (isJvmServiceThread(threadName)) {
            return ThreadRole.JVM_SERVICE;
        }
        return ThreadRole.UNRELATED_BACKGROUND;
    }

    private boolean hasFrame(List<RecordedFrame> frames, Predicate<String> predicate) {
        return frames.stream()
                .map(frame -> frame.getMethod().getType().getName())
                .anyMatch(predicate);
    }

    private boolean isJvmServiceThread(String threadName) {
        if (threadName == null) {
            return false;
        }
        return threadName.equals("Reference Handler")
                || threadName.equals("Finalizer")
                || threadName.equals("Signal Dispatcher")
                || threadName.equals("Notification Thread")
                || threadName.equals("Common-Cleaner")
                || threadName.equals("Sweeper thread")
                || threadName.equals("VM Thread")
                || threadName.startsWith("C1 CompilerThread")
                || threadName.startsWith("C2 CompilerThread")
                || threadName.startsWith("JFR")
                || threadName.startsWith("GC Thread")
                || threadName.startsWith("G1 ");
    }

    private List<RecordedFrame> frames(RecordedEvent event) {
        RecordedStackTrace stackTrace = event.getStackTrace();
        return stackTrace == null ? List.of() : stackTrace.getFrames();
    }

    private Set<String> expectedGameKeyCodes() {
        return IntStream.rangeClosed(1, REQUEST_COUNT)
                .mapToObj(index -> GAME_KEY_PREFIX + "%03d".formatted(index))
                .collect(Collectors.toSet());
    }

    private void deleteTestData() {
        allocationOutboxRepository.deleteAllInBatch();
        idempotencyRecordRepository.deleteAllInBatch();
        allocationRepository.deleteAllInBatch();
        gameKeyRepository.deleteAllInBatch();
        gameRepository.deleteAllInBatch();
    }

    private static String milliseconds(long nanoseconds) {
        return "%.3f".formatted(nanoseconds / 1_000_000.0);
    }

    private record DatabaseObservation(String product, String version) {
    }

    private record EventCategory(String label, Set<String> eventNames) {
    }

    private record RequestObservation(
            int index,
            String idempotencyKey,
            int statusCode,
            String responseBody,
            long elapsedNanos
    ) {
    }

    private record SequenceObservation(List<RequestObservation> requests, long elapsedNanos) {
    }

    private record ParsedRecording(
            List<EventType> eventTypes,
            List<RecordedEvent> events,
            Map<String, Long> eventCounts
    ) {
    }

    private record ThreadRoleSummary(long eventCount, Set<String> observedThreadNames) {
    }

    private enum ThreadRole {
        TEST_CLIENT,
        SERVLET_REQUEST,
        JVM_SERVICE,
        UNRELATED_BACKGROUND
    }
}
