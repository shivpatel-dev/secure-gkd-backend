package com.shiv.securegkd.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.KafkaHeaders;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Testcontainers
class AllocationAuditFlowIT {

    private static final String SOURCE_TOPIC = "secure-gkd.allocation-created";
    private static final String DLT_TOPIC = "secure-gkd.allocation-created.dlt";
    private static final String GAME_CODE = "INTEGRATION-GAME";
    private static final String SECRET_GAME_KEY = "SYNTHETIC-SECRET-GAME-KEY";
    private static final String IDEMPOTENCY_KEY = "integration-allocation-idempotency-key";
    private static final String TEST_USERNAME = "integration-user";
    private static final String TEST_PASSWORD = "integration-password";
    private static final String JWT_SIGNING_KEY_BASE64 =
            "aW50ZWdyYXRpb24tdGVzdC1vbmx5LXNpZ25pbmcta2V5LTEyMzQ1Njc4OTA=";
    private static final Duration EVENTUAL_TIMEOUT = Duration.ofSeconds(60);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Container
    private static final PostgreSQLContainer ALLOCATION_DATABASE =
            new PostgreSQLContainer("postgres:16")
                    .withDatabaseName("secure_gkd_integration")
                    .withUsername("allocation_integration_user")
                    .withPassword("allocation_integration_password")
                    .withStartupTimeout(Duration.ofMinutes(2));

    @Container
    private static final PostgreSQLContainer AUDIT_DATABASE =
            new PostgreSQLContainer("postgres:16")
                    .withDatabaseName("secure_gkd_audit_integration")
                    .withUsername("audit_integration_user")
                    .withPassword("audit_integration_password")
                    .withStartupTimeout(Duration.ofMinutes(2));

    @Container
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1")
            .withStartupTimeout(Duration.ofMinutes(3));

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private static ServiceProcess allocationService;
    private static ServiceProcess auditService;
    private static URI allocationBaseUri;
    private static Path repositoryRoot;

    @BeforeAll
    static void startAllocationBoundary() throws Exception {
        repositoryRoot = Path.of("..").toAbsolutePath().normalize();
        Path allocationJar = artifactPath(
                "allocation.service.jar",
                repositoryRoot.resolve("target/secure-gkd-backend-0.0.1-SNAPSHOT.jar")
        );
        Path auditJar = artifactPath(
                "audit.service.jar",
                repositoryRoot.resolve(
                        "audit-service/target/secure-gkd-allocation-audit-0.0.1-SNAPSHOT.jar"
                )
        );
        assertThat(allocationJar).as("packaged allocation-service artifact").isRegularFile();
        assertThat(auditJar).as("packaged audit-service artifact").isRegularFile();

        createTopics();
        int allocationPort = availablePort();
        allocationBaseUri = URI.create("http://127.0.0.1:" + allocationPort);
        allocationService = ServiceProcess.start(
                "allocation-service",
                allocationJar,
                repositoryRoot,
                allocationEnvironment(allocationPort)
        );
        awaitAllocationHealth();
        seedAllocationDatabase();
    }

    @AfterAll
    static void stopApplications() {
        if (auditService != null) {
            auditService.close();
        }
        if (allocationService != null) {
            allocationService.close();
        }
    }

    @Test
    void exercisesAllocationAuditFlowThroughRealKafkaAndOwnedPostgresDatabases() throws Exception {
        TopicPartition sourcePartition = new TopicPartition(SOURCE_TOPIC, 0);
        TopicPartition deadLetterPartition = new TopicPartition(DLT_TOPIC, 0);

        try (KafkaConsumer<String, String> observer = observer(sourcePartition, deadLetterPartition);
             KafkaProducer<String, String> producer = producer()) {
            String bearerToken = requestBearerToken();

            HttpResponse<String> firstAllocation = allocate(bearerToken, IDEMPOTENCY_KEY);
            assertThat(firstAllocation.statusCode()).isEqualTo(201);
            JsonNode firstResponse = OBJECT_MAPPER.readTree(firstAllocation.body());
            assertThat(firstResponse.path("gameCode").asText()).isEqualTo(GAME_CODE);
            assertThat(firstResponse.path("keyCode").asText()).isEqualTo(SECRET_GAME_KEY);
            assertThat(firstResponse.path("allocatedAt").asText()).isNotBlank();

            OutboxSnapshot outbox = onlyOutboxRow();
            assertThat(outbox.schemaVersion()).isEqualTo(1);
            assertThat(outbox.eventType()).isEqualTo("AllocationCreated");
            assertThat(outbox.allocationId()).isPositive();
            assertThat(outbox.occurredAt()).isNotNull();
            assertThat(queryLong(ALLOCATION_DATABASE, "SELECT count(*) FROM allocations"))
                    .isEqualTo(1);
            assertThat(queryLong(
                    ALLOCATION_DATABASE,
                    "SELECT count(*) FROM idempotency_records"
            )).isEqualTo(1);

            JsonNode persistedPayload = OBJECT_MAPPER.readTree(outbox.payload());
            assertThat(persistedPayload.path("eventId").asText())
                    .isEqualTo(outbox.eventId().toString());
            assertThat(persistedPayload.path("schemaVersion").asInt()).isEqualTo(1);
            assertThat(persistedPayload.path("allocationId").asLong())
                    .isEqualTo(outbox.allocationId());
            assertThat(persistedPayload.path("requestId").asText())
                    .isEqualTo(firstAllocation.headers().firstValue("X-Request-Id").orElseThrow());
            assertThat(outbox.payload())
                    .doesNotContain(SECRET_GAME_KEY)
                    .doesNotContain(IDEMPOTENCY_KEY)
                    .doesNotContain("keyCode")
                    .doesNotContain("idempotencyKey");

            assertThat(auditService).as("audit service has not been started").isNull();
            assertThat(tableExists(AUDIT_DATABASE, "allocation_audit_record")).isFalse();

            ConsumerRecord<String, String> published = awaitRecord(
                    observer,
                    SOURCE_TOPIC,
                    outbox.eventId().toString(),
                    EVENTUAL_TIMEOUT
            );
            assertThat(published.key()).isEqualTo(outbox.eventId().toString());
            assertThat(published.value()).isEqualTo(outbox.payload());
            await().atMost(EVENTUAL_TIMEOUT).untilAsserted(() ->
                    assertThat(onlyOutboxRow().publishedAt()).isNotNull()
            );

            HttpResponse<String> replay = allocate(bearerToken, IDEMPOTENCY_KEY);
            assertThat(replay.statusCode()).isEqualTo(201);
            assertThat(OBJECT_MAPPER.readTree(replay.body())).isEqualTo(firstResponse);
            assertThat(queryLong(ALLOCATION_DATABASE, "SELECT count(*) FROM allocations"))
                    .isEqualTo(1);
            assertThat(queryLong(ALLOCATION_DATABASE, "SELECT count(*) FROM allocation_outbox"))
                    .isEqualTo(1);
            assertThat(additionalRecordsWithKey(
                    observer,
                    SOURCE_TOPIC,
                    outbox.eventId().toString(),
                    Duration.ofSeconds(2)
            )).isZero();

            startAuditService();
            await().atMost(EVENTUAL_TIMEOUT).untilAsserted(() -> {
                AuditSnapshot audit = auditRow(outbox.eventId());
                assertThat(audit.sourceEventId()).isEqualTo(outbox.eventId());
                assertThat(audit.sourceAllocationId()).isEqualTo(outbox.allocationId());
                assertThat(audit.schemaVersion()).isEqualTo(1);
                assertThat(audit.gameCode()).isEqualTo(GAME_CODE);
                assertThat(audit.requestId()).isEqualTo(persistedPayload.path("requestId").asText());
            });
            assertThat(tableExists(ALLOCATION_DATABASE, "allocation_audit_record")).isFalse();
            assertThat(tableExists(AUDIT_DATABASE, "allocations")).isFalse();

            producer.send(new ProducerRecord<>(
                    SOURCE_TOPIC,
                    outbox.eventId().toString(),
                    outbox.payload()
            )).get(10, TimeUnit.SECONDS);
            await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(auditRows(outbox.eventId())).isEqualTo(1)
            );

            UUID malformedEventId = UUID.randomUUID();
            String malformedValue = "{not-json";
            RecordMetadata malformedMetadata = producer.send(new ProducerRecord<>(
                    SOURCE_TOPIC,
                    malformedEventId.toString(),
                    malformedValue
            )).get(10, TimeUnit.SECONDS);
            ConsumerRecord<String, String> malformedDlt = awaitRecord(
                    observer,
                    DLT_TOPIC,
                    malformedEventId.toString(),
                    EVENTUAL_TIMEOUT
            );
            assertDeadLetterIdentity(
                    malformedDlt,
                    malformedMetadata,
                    malformedEventId.toString(),
                    malformedValue
            );
            assertThat(textHeader(malformedDlt, "secure-gkd-dlt-failure-reason"))
                    .isEqualTo("INVALID_EVENT_CONTRACT");
            assertThat(integerHeader(malformedDlt, KafkaHeaders.DELIVERY_ATTEMPT)).isEqualTo(1);
            assertThat(auditService.matchingLines(line ->
                    line.contains("allocation_audit_processing_failed_will_retry")
                            && line.contains("offset=" + malformedMetadata.offset())
            )).isEmpty();

            UUID validAfterMalformedId = UUID.randomUUID();
            String validAfterMalformed = eventJson(
                    validAfterMalformedId,
                    outbox.allocationId() + 100,
                    persistedPayload.path("gameId").asLong(),
                    GAME_CODE,
                    "valid-after-malformed"
            );
            producer.send(new ProducerRecord<>(
                    SOURCE_TOPIC,
                    validAfterMalformedId.toString(),
                    validAfterMalformed
            )).get(10, TimeUnit.SECONDS);
            await().atMost(EVENTUAL_TIMEOUT).untilAsserted(() ->
                    assertThat(auditRows(validAfterMalformedId)).isEqualTo(1)
            );

            UUID retryableEventId = UUID.randomUUID();
            String retryableValue = eventJson(
                    retryableEventId,
                    outbox.allocationId() + 200,
                    persistedPayload.path("gameId").asLong(),
                    "R".repeat(101),
                    "retryable-postgres-failure"
            );
            RecordMetadata retryableMetadata = producer.send(new ProducerRecord<>(
                    SOURCE_TOPIC,
                    retryableEventId.toString(),
                    retryableValue
            )).get(10, TimeUnit.SECONDS);
            ConsumerRecord<String, String> retryableDlt = awaitRecord(
                    observer,
                    DLT_TOPIC,
                    retryableEventId.toString(),
                    EVENTUAL_TIMEOUT
            );
            assertDeadLetterIdentity(
                    retryableDlt,
                    retryableMetadata,
                    retryableEventId.toString(),
                    retryableValue
            );
            assertThat(textHeader(retryableDlt, "secure-gkd-dlt-failure-reason"))
                    .isEqualTo("RETRY_EXHAUSTED");
            assertThat(integerHeader(retryableDlt, KafkaHeaders.DELIVERY_ATTEMPT)).isEqualTo(3);
            List<String> retryLines = auditService.matchingLines(line ->
                    line.contains("allocation_audit_processing_failed_will_retry")
                            && line.contains("offset=" + retryableMetadata.offset())
            );
            assertThat(retryLines).hasSize(2);
            assertThat(retryLines.get(0)).contains("deliveryAttempt=1", "nextDeliveryAttempt=2");
            assertThat(retryLines.get(1)).contains("deliveryAttempt=2", "nextDeliveryAttempt=3");
            assertThat(auditService.matchingLines(line ->
                    line.contains("allocation_audit_retry_exhausted")
                            && line.contains("offset=" + retryableMetadata.offset())
                            && line.contains("attempts=3")
            )).hasSize(1);
            assertThat(auditRows(retryableEventId)).isZero();

            UUID validAfterRetryId = UUID.randomUUID();
            String validAfterRetry = eventJson(
                    validAfterRetryId,
                    outbox.allocationId() + 300,
                    persistedPayload.path("gameId").asLong(),
                    GAME_CODE,
                    "valid-after-retry-exhaustion"
            );
            producer.send(new ProducerRecord<>(
                    SOURCE_TOPIC,
                    validAfterRetryId.toString(),
                    validAfterRetry
            )).get(10, TimeUnit.SECONDS);
            await().atMost(EVENTUAL_TIMEOUT).untilAsserted(() ->
                    assertThat(auditRows(validAfterRetryId)).isEqualTo(1)
            );
            assertThat(auditRows(outbox.eventId())).isEqualTo(1);
            assertThat(queryLong(AUDIT_DATABASE, "SELECT count(*) FROM allocation_audit_record"))
                    .isEqualTo(3);
        }
    }

    private static void createTopics() throws Exception {
        Map<String, Object> properties = Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                KAFKA.getBootstrapServers()
        );
        try (AdminClient admin = AdminClient.create(properties)) {
            admin.createTopics(List.of(
                    new NewTopic(SOURCE_TOPIC, 1, (short) 1),
                    new NewTopic(DLT_TOPIC, 1, (short) 1)
            )).all().get(30, TimeUnit.SECONDS);
        }
    }

    private static Map<String, String> allocationEnvironment(int port) {
        Map<String, String> environment = new HashMap<>();
        environment.put("SPRING_PROFILES_ACTIVE", "local");
        environment.put("SPRING_DATASOURCE_URL", ALLOCATION_DATABASE.getJdbcUrl());
        environment.put("SPRING_DATASOURCE_USERNAME", ALLOCATION_DATABASE.getUsername());
        environment.put("SPRING_DATASOURCE_PASSWORD", ALLOCATION_DATABASE.getPassword());
        environment.put("JWT_SIGNING_KEY_BASE64", JWT_SIGNING_KEY_BASE64);
        environment.put("SPRING_KAFKA_BOOTSTRAP_SERVERS", KAFKA.getBootstrapServers());
        environment.put("SECURE_GKD_KAFKA_PUBLISHER_ENABLED", "true");
        environment.put("SECURE_GKD_KAFKA_PUBLISHER_POLL_INTERVAL", "PT0.2S");
        environment.put("SECURE_GKD_KAFKA_PUBLISHER_ACKNOWLEDGEMENT_TIMEOUT", "PT10S");
        environment.put("SERVER_PORT", Integer.toString(port));
        return environment;
    }

    private static Map<String, String> auditEnvironment() {
        Map<String, String> environment = new HashMap<>();
        environment.put("AUDIT_DATASOURCE_URL", AUDIT_DATABASE.getJdbcUrl());
        environment.put("AUDIT_DATASOURCE_USERNAME", AUDIT_DATABASE.getUsername());
        environment.put("AUDIT_DATASOURCE_PASSWORD", AUDIT_DATABASE.getPassword());
        environment.put("SPRING_KAFKA_BOOTSTRAP_SERVERS", KAFKA.getBootstrapServers());
        environment.put("AUDIT_KAFKA_TOPIC", SOURCE_TOPIC);
        environment.put("AUDIT_KAFKA_CONSUMER_GROUP", "integration-audit-" + UUID.randomUUID());
        environment.put("AUDIT_KAFKA_DEAD_LETTER_TOPIC", DLT_TOPIC);
        return environment;
    }

    private static void startAuditService() throws Exception {
        Path auditJar = artifactPath(
                "audit.service.jar",
                repositoryRoot.resolve(
                        "audit-service/target/secure-gkd-allocation-audit-0.0.1-SNAPSHOT.jar"
                )
        );
        auditService = ServiceProcess.start(
                "audit-service",
                auditJar,
                repositoryRoot,
                auditEnvironment()
        );
        await().pollInterval(Duration.ofMillis(200)).atMost(EVENTUAL_TIMEOUT).untilAsserted(() -> {
            assertThat(auditService.isAlive())
                    .as("audit service process%n%s", auditService.recentOutput())
                    .isTrue();
            assertThat(tableExists(AUDIT_DATABASE, "allocation_audit_record")).isTrue();
        });
    }

    private static void awaitAllocationHealth() {
        await().pollInterval(Duration.ofMillis(200))
                .ignoreExceptions()
                .atMost(EVENTUAL_TIMEOUT)
                .untilAsserted(() -> {
            assertThat(allocationService.isAlive())
                    .as("allocation service process%n%s", allocationService.recentOutput())
                    .isTrue();
            HttpRequest request = HttpRequest.newBuilder(
                            allocationBaseUri.resolve("/api/health")
                    )
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            assertThat(HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
                    .isEqualTo(200);
                });
    }

    private static void seedAllocationDatabase() throws SQLException {
        try (Connection connection = allocationConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement identity = connection.prepareStatement("""
                    INSERT INTO authentication_identities (username, password_hash, role, created_at)
                    VALUES (?, ?, 'USER', CURRENT_TIMESTAMP)
                    """)) {
                identity.setString(1, TEST_USERNAME);
                identity.setString(2, "{noop}" + TEST_PASSWORD);
                identity.executeUpdate();
            }

            long gameId;
            try (PreparedStatement game = connection.prepareStatement("""
                    INSERT INTO games (code, title, created_at)
                    VALUES (?, 'Integration Game', CURRENT_TIMESTAMP)
                    RETURNING id
                    """)) {
                game.setString(1, GAME_CODE);
                try (ResultSet result = game.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    gameId = result.getLong(1);
                }
            }

            try (PreparedStatement gameKey = connection.prepareStatement("""
                    INSERT INTO game_keys (game_id, code, created_at)
                    VALUES (?, ?, CURRENT_TIMESTAMP)
                    """)) {
                gameKey.setLong(1, gameId);
                gameKey.setString(2, SECRET_GAME_KEY);
                gameKey.executeUpdate();
            }
            connection.commit();
        }
    }

    private static String requestBearerToken() throws Exception {
        HttpResponse<String> response = postJson(
                "/api/auth/token",
                """
                        {"username":"%s","password":"%s"}
                        """.formatted(TEST_USERNAME, TEST_PASSWORD),
                null
        );
        assertThat(response.statusCode()).isEqualTo(200);
        return OBJECT_MAPPER.readTree(response.body()).path("accessToken").asText();
    }

    private static HttpResponse<String> allocate(String bearerToken, String idempotencyKey)
            throws Exception {
        ObjectNode body = OBJECT_MAPPER.createObjectNode();
        body.put("idempotencyKey", idempotencyKey);
        return postJson(
                "/api/games/" + GAME_CODE + "/allocations",
                OBJECT_MAPPER.writeValueAsString(body),
                bearerToken
        );
    }

    private static HttpResponse<String> postJson(String path, String body, String bearerToken)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(allocationBaseUri.resolve(path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (bearerToken != null) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        return HTTP_CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static KafkaConsumer<String, String> observer(TopicPartition... partitions) {
        Map<String, Object> properties = new HashMap<>();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "integration-observer-" + UUID.randomUUID());
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties);
        List<TopicPartition> assignment = List.of(partitions);
        consumer.assign(assignment);
        consumer.seekToBeginning(assignment);
        return consumer;
    }

    private static KafkaProducer<String, String> producer() {
        Map<String, Object> properties = new HashMap<>();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new KafkaProducer<>(properties);
    }

    private static ConsumerRecord<String, String> awaitRecord(
            KafkaConsumer<String, String> consumer,
            String topic,
            String key,
            Duration timeout
    ) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(250));
            for (ConsumerRecord<String, String> record : records) {
                if (record.topic().equals(topic) && Objects.equals(record.key(), key)) {
                    return record;
                }
            }
        }
        throw new AssertionError("Timed out waiting for key " + key + " on topic " + topic);
    }

    private static int additionalRecordsWithKey(
            KafkaConsumer<String, String> consumer,
            String topic,
            String key,
            Duration duration
    ) {
        int matches = 0;
        Instant deadline = Instant.now().plus(duration);
        while (Instant.now().isBefore(deadline)) {
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(200))) {
                if (record.topic().equals(topic) && Objects.equals(record.key(), key)) {
                    matches++;
                }
            }
        }
        return matches;
    }

    private static void assertDeadLetterIdentity(
            ConsumerRecord<String, String> deadLetter,
            RecordMetadata source,
            String originalKey,
            String originalValue
    ) {
        assertThat(deadLetter.key()).isEqualTo(originalKey);
        assertThat(deadLetter.value()).isEqualTo(originalValue);
        assertThat(textHeader(deadLetter, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(SOURCE_TOPIC);
        assertThat(integerHeader(deadLetter, KafkaHeaders.DLT_ORIGINAL_PARTITION))
                .isEqualTo(source.partition());
        assertThat(longHeader(deadLetter, KafkaHeaders.DLT_ORIGINAL_OFFSET))
                .isEqualTo(source.offset());
        assertThat(deadLetter.headers().lastHeader(KafkaHeaders.DLT_EXCEPTION_FQCN)).isNotNull();
        assertThat(deadLetter.headers().lastHeader(KafkaHeaders.DLT_EXCEPTION_MESSAGE)).isNull();
        assertThat(deadLetter.headers().lastHeader(KafkaHeaders.DLT_EXCEPTION_STACKTRACE)).isNull();
    }

    private static String textHeader(ConsumerRecord<?, ?> record, String name) {
        Header header = requiredHeader(record, name);
        return new String(header.value(), StandardCharsets.UTF_8);
    }

    private static int integerHeader(ConsumerRecord<?, ?> record, String name) {
        return ByteBuffer.wrap(requiredHeader(record, name).value()).getInt();
    }

    private static long longHeader(ConsumerRecord<?, ?> record, String name) {
        return ByteBuffer.wrap(requiredHeader(record, name).value()).getLong();
    }

    private static Header requiredHeader(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        assertThat(header).as("Kafka header %s", name).isNotNull();
        return header;
    }

    private static String eventJson(
            UUID eventId,
            long allocationId,
            long gameId,
            String gameCode,
            String requestId
    ) throws Exception {
        ObjectNode event = OBJECT_MAPPER.createObjectNode();
        event.put("eventId", eventId.toString());
        event.put("schemaVersion", 1);
        event.put("occurredAt", Instant.now().toString());
        event.put("allocationId", allocationId);
        event.put("allocatedAt", Instant.now().toString());
        event.put("gameId", gameId);
        event.put("gameCode", gameCode);
        event.put("requestId", requestId);
        return OBJECT_MAPPER.writeValueAsString(event);
    }

    private static OutboxSnapshot onlyOutboxRow() throws SQLException {
        try (Connection connection = allocationConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT event_id, allocation_id, event_type, schema_version, payload,
                            occurred_at, published_at
                     FROM allocation_outbox
                     """)) {
            assertThat(result.next()).isTrue();
            OutboxSnapshot snapshot = new OutboxSnapshot(
                    result.getObject("event_id", UUID.class),
                    result.getLong("allocation_id"),
                    result.getString("event_type"),
                    result.getInt("schema_version"),
                    result.getString("payload"),
                    result.getTimestamp("occurred_at").toInstant(),
                    result.getTimestamp("published_at") == null
                            ? null
                            : result.getTimestamp("published_at").toInstant()
            );
            assertThat(result.next()).isFalse();
            return snapshot;
        }
    }

    private static AuditSnapshot auditRow(UUID eventId) throws SQLException {
        try (Connection connection = auditConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT source_event_id, source_allocation_id, schema_version,
                            game_code, request_id
                     FROM allocation_audit_record
                     WHERE source_event_id = ?
                     """)) {
            statement.setObject(1, eventId);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                AuditSnapshot snapshot = new AuditSnapshot(
                        result.getObject("source_event_id", UUID.class),
                        result.getLong("source_allocation_id"),
                        result.getInt("schema_version"),
                        result.getString("game_code"),
                        result.getString("request_id")
                );
                assertThat(result.next()).isFalse();
                return snapshot;
            }
        }
    }

    private static long auditRows(UUID eventId) throws SQLException {
        try (Connection connection = auditConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT count(*) FROM allocation_audit_record WHERE source_event_id = ?"
             )) {
            statement.setObject(1, eventId);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getLong(1);
            }
        }
    }

    private static boolean tableExists(PostgreSQLContainer database, String table)
            throws SQLException {
        try (Connection connection = connection(database);
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT count(*)
                     FROM information_schema.tables
                     WHERE table_schema = 'public' AND table_name = ?
                     """)) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getLong(1) == 1;
            }
        }
    }

    private static long queryLong(PostgreSQLContainer database, String sql)
            throws SQLException {
        try (Connection connection = connection(database);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }

    private static Connection allocationConnection() throws SQLException {
        return connection(ALLOCATION_DATABASE);
    }

    private static Connection auditConnection() throws SQLException {
        return connection(AUDIT_DATABASE);
    }

    private static Connection connection(PostgreSQLContainer database) throws SQLException {
        return DriverManager.getConnection(
                database.getJdbcUrl(),
                database.getUsername(),
                database.getPassword()
        );
    }

    private static int availablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static Path artifactPath(String property, Path defaultPath) {
        String configured = System.getProperty(property);
        return configured == null
                ? defaultPath.toAbsolutePath().normalize()
                : Path.of(configured).toAbsolutePath().normalize();
    }

    private record OutboxSnapshot(
            UUID eventId,
            long allocationId,
            String eventType,
            int schemaVersion,
            String payload,
            Instant occurredAt,
            Instant publishedAt
    ) {
    }

    private record AuditSnapshot(
            UUID sourceEventId,
            long sourceAllocationId,
            int schemaVersion,
            String gameCode,
            String requestId
    ) {
    }

    private static final class ServiceProcess implements AutoCloseable {

        private static final int MAX_LOG_LINES = 1000;

        private final String name;
        private final Process process;
        private final List<String> output = Collections.synchronizedList(new ArrayList<>());
        private final Thread outputReader;

        private ServiceProcess(String name, Process process) {
            this.name = name;
            this.process = process;
            this.outputReader = new Thread(this::captureOutput, name + "-integration-output");
            this.outputReader.setDaemon(true);
            this.outputReader.start();
        }

        static ServiceProcess start(
                String name,
                Path jar,
                Path workingDirectory,
                Map<String, String> environment
        ) throws IOException {
            Path javaBinary = Path.of(
                    System.getProperty("java.home"),
                    "bin",
                    System.getProperty("os.name").toLowerCase().contains("windows")
                            ? "java.exe"
                            : "java"
            );
            ProcessBuilder builder = new ProcessBuilder(
                    javaBinary.toString(),
                    "-Duser.timezone=UTC",
                    "-jar",
                    jar.toString()
            );
            builder.directory(workingDirectory.toFile());
            builder.redirectErrorStream(true);
            builder.environment().keySet().removeIf(ServiceProcess::isApplicationSetting);
            builder.environment().putAll(environment);
            return new ServiceProcess(name, builder.start());
        }

        private static boolean isApplicationSetting(String name) {
            String normalized = name.toUpperCase(Locale.ROOT);
            return normalized.startsWith("SPRING_")
                    || normalized.startsWith("SECURE_GKD_")
                    || normalized.startsWith("AUDIT_")
                    || normalized.startsWith("JWT_")
                    || normalized.startsWith("SERVER_");
        }

        boolean isAlive() {
            return process.isAlive();
        }

        List<String> matchingLines(Predicate<String> predicate) {
            synchronized (output) {
                return output.stream().filter(predicate).toList();
            }
        }

        String recentOutput() {
            synchronized (output) {
                return String.join(System.lineSeparator(), output);
            }
        }

        private void captureOutput() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(),
                    StandardCharsets.UTF_8
            ))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (output) {
                        output.add(line);
                        if (output.size() > MAX_LOG_LINES) {
                            output.remove(0);
                        }
                    }
                }
            } catch (IOException exception) {
                synchronized (output) {
                    output.add("Failed to capture " + name + " output: "
                            + exception.getClass().getSimpleName());
                }
            }
        }

        @Override
        public void close() {
            process.destroy();
            try {
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(10, TimeUnit.SECONDS);
                }
                outputReader.join(Duration.ofSeconds(2).toMillis());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }
}
