package com.shiv.securegkd.audit.configuration;

import com.shiv.securegkd.audit.consumer.NonRetryableAllocationAuditException;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.KafkaHeaders;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
@SuppressWarnings("unchecked")
class KafkaConsumerConfigurationTests {

    private static final String SOURCE_TOPIC = "secure-gkd.allocation-created";
    private static final String DLT_TOPIC = "secure-gkd.allocation-created.dlt";
    private static final String SECRET_PAYLOAD = "complete-payload-with-secret-value";

    @Test
    void configuresExplicitRecordAcknowledgementAndBoundedRetryDefaults() {
        KafkaProperties kafkaProperties = new KafkaProperties();
        kafkaProperties.setBootstrapServers(List.of("localhost:29092"));
        AllocationAuditConsumerProperties properties = new AllocationAuditConsumerProperties();
        KafkaOperations<Object, Object> kafkaOperations = successfulKafkaOperations();
        KafkaConsumerConfiguration configuration = new KafkaConsumerConfiguration();
        DefaultErrorHandler errorHandler = configuration.allocationAuditErrorHandler(
                kafkaOperations,
                properties
        );

        var factory = configuration.kafkaListenerContainerFactory(kafkaProperties, errorHandler);

        assertThat(properties.getDeadLetterTopic()).isEqualTo(DLT_TOPIC);
        assertThat(properties.getRetryAttempts()).isEqualTo(2);
        assertThat(properties.getRetryBackoff()).isEqualTo(Duration.ofSeconds(1));
        assertThat(factory.getContainerProperties().getAckMode())
                .isEqualTo(ContainerProperties.AckMode.RECORD);
        assertThat(factory.getContainerProperties().isDeliveryAttemptHeader()).isTrue();
        assertThat(factory.createContainer(SOURCE_TOPIC).getCommonErrorHandler())
                .isSameAs(errorHandler);
        assertThat(factory.getConsumerFactory())
                .isInstanceOf(DefaultKafkaConsumerFactory.class);

        DefaultKafkaConsumerFactory<?, ?> consumerFactory =
                (DefaultKafkaConsumerFactory<?, ?>) factory.getConsumerFactory();
        assertThat(consumerFactory.getConfigurationProperties())
                .containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
                .containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    }

    @Test
    void exhaustsRetryableFailureAfterTwoRetriesAndPublishesOriginalRecord(
            CapturedOutput output
    ) {
        KafkaOperations<Object, Object> kafkaOperations = successfulKafkaOperations();
        AllocationAuditConsumerProperties properties = fastRetryProperties();
        DefaultErrorHandler errorHandler = new KafkaConsumerConfiguration()
                .allocationAuditErrorHandler(kafkaOperations, properties);
        ConsumerRecord<String, String> record = sourceRecord();
        Exception failure = new IllegalStateException("infrastructure unavailable");

        assertThat(handle(errorHandler, failure, record)).isFalse();
        assertThat(handle(errorHandler, failure, record)).isFalse();
        assertThat(handle(errorHandler, failure, record)).isTrue();

        ArgumentCaptor<ProducerRecord<Object, Object>> captor = producerRecordCaptor();
        verify(kafkaOperations).send(captor.capture());
        ProducerRecord<Object, Object> deadLetterRecord = captor.getValue();
        assertThat(deadLetterRecord.topic()).isEqualTo(DLT_TOPIC);
        assertThat(deadLetterRecord.partition()).isEqualTo(0);
        assertThat(deadLetterRecord.key()).isEqualTo("not-a-valid-event-id");
        assertThat(deadLetterRecord.value()).isEqualTo(SECRET_PAYLOAD);
        assertThat(header(deadLetterRecord, KafkaHeaders.DLT_ORIGINAL_TOPIC))
                .isEqualTo(SOURCE_TOPIC);
        assertThat(integerHeader(deadLetterRecord, KafkaHeaders.DLT_ORIGINAL_PARTITION))
                .isZero();
        assertThat(longHeader(deadLetterRecord, KafkaHeaders.DLT_ORIGINAL_OFFSET))
                .isEqualTo(12L);
        assertThat(header(deadLetterRecord, KafkaHeaders.DLT_EXCEPTION_FQCN))
                .isEqualTo(IllegalStateException.class.getName());
        assertThat(header(deadLetterRecord, "secure-gkd-dlt-failure-reason"))
                .isEqualTo("RETRY_EXHAUSTED");
        assertThat(deadLetterRecord.headers().lastHeader(KafkaHeaders.DLT_EXCEPTION_MESSAGE))
                .isNull();
        assertThat(deadLetterRecord.headers().lastHeader(KafkaHeaders.DLT_EXCEPTION_STACKTRACE))
                .isNull();
        assertThat(output).contains("allocation_audit_processing_failed_will_retry")
                .contains("deliveryAttempt=1")
                .contains("deliveryAttempt=2")
                .contains("allocation_audit_retry_exhausted")
                .contains("attempts=3")
                .contains("allocation_audit_dead_letter_recovered")
                .doesNotContain(SECRET_PAYLOAD)
                .doesNotContain("infrastructure unavailable");
    }

    @Test
    void sendsNonRetryableFailureDirectlyToDeadLetterWithoutRetry(CapturedOutput output) {
        KafkaOperations<Object, Object> kafkaOperations = successfulKafkaOperations();
        DefaultErrorHandler errorHandler = new KafkaConsumerConfiguration()
                .allocationAuditErrorHandler(kafkaOperations, fastRetryProperties());
        Exception failure = new NonRetryableAllocationAuditException(
                NonRetryableAllocationAuditException.Reason.INVALID_EVENT_CONTRACT,
                "invalid event contract",
                new IllegalArgumentException("unsupported schema")
        );

        assertThat(handle(errorHandler, failure, sourceRecord())).isTrue();

        verify(kafkaOperations).send(any(ProducerRecord.class));
        assertThat(output).contains("allocation_audit_poison_message")
                .contains("failureReason=INVALID_EVENT_CONTRACT")
                .contains("allocation_audit_dead_letter_recovered")
                .doesNotContain("allocation_audit_processing_failed_will_retry")
                .doesNotContain(SECRET_PAYLOAD)
                .doesNotContain("unsupported schema");
    }

    @Test
    void deadLetterPublicationFailureDoesNotReportSuccessfulRecovery(CapturedOutput output) {
        KafkaOperations<Object, Object> kafkaOperations = mock(KafkaOperations.class);
        when(kafkaOperations.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(
                        new IllegalStateException("broker unavailable")
                ));
        DefaultErrorHandler errorHandler = new KafkaConsumerConfiguration()
                .allocationAuditErrorHandler(kafkaOperations, fastRetryProperties());
        Exception failure = new NonRetryableAllocationAuditException(
                NonRetryableAllocationAuditException.Reason.INVALID_KAFKA_EVENT_KEY,
                "invalid Kafka key",
                new IllegalArgumentException("invalid UUID")
        );

        assertThat(handle(errorHandler, failure, sourceRecord())).isFalse();

        verify(kafkaOperations).send(any(ProducerRecord.class));
        assertThat(output).contains("allocation_audit_dead_letter_publication_failed")
                .contains("failureType=IllegalStateException")
                .doesNotContain("allocation_audit_dead_letter_recovered")
                .doesNotContain(SECRET_PAYLOAD);
    }

    @Test
    void configuredFixedBackoffDelaysEachRetry() {
        KafkaOperations<Object, Object> kafkaOperations = successfulKafkaOperations();
        AllocationAuditConsumerProperties properties = new AllocationAuditConsumerProperties();
        DefaultErrorHandler errorHandler = new KafkaConsumerConfiguration()
                .allocationAuditErrorHandler(kafkaOperations, properties);
        ConsumerRecord<String, String> record = sourceRecord();
        Exception failure = new IllegalStateException("retryable");

        long startedAt = System.nanoTime();
        assertThat(handle(errorHandler, failure, record)).isFalse();
        assertThat(handle(errorHandler, failure, record)).isFalse();
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt))
                .isGreaterThanOrEqualTo(Duration.ofMillis(1900));

        verify(kafkaOperations, times(0)).send(any(ProducerRecord.class));
        errorHandler.clearThreadState();
    }

    private AllocationAuditConsumerProperties fastRetryProperties() {
        AllocationAuditConsumerProperties properties = new AllocationAuditConsumerProperties();
        properties.setRetryBackoff(Duration.ofMillis(1));
        return properties;
    }

    private KafkaOperations<Object, Object> successfulKafkaOperations() {
        KafkaOperations<Object, Object> kafkaOperations = mock(KafkaOperations.class);
        when(kafkaOperations.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        return kafkaOperations;
    }

    private ArgumentCaptor<ProducerRecord<Object, Object>> producerRecordCaptor() {
        return ArgumentCaptor.forClass(ProducerRecord.class);
    }

    private boolean handle(
            DefaultErrorHandler errorHandler,
            Exception failure,
            ConsumerRecord<String, String> record
    ) {
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        when(container.isRunning()).thenReturn(true);
        return errorHandler.handleOne(
                failure,
                record,
                mock(Consumer.class),
                container
        );
    }

    private ConsumerRecord<String, String> sourceRecord() {
        return new ConsumerRecord<>(
                SOURCE_TOPIC,
                0,
                12L,
                "not-a-valid-event-id",
                SECRET_PAYLOAD
        );
    }

    private String header(ProducerRecord<Object, Object> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    private int integerHeader(ProducerRecord<Object, Object> record, String name) {
        return ByteBuffer.wrap(record.headers().lastHeader(name).value()).getInt();
    }

    private long longHeader(ProducerRecord<Object, Object> record, String name) {
        return ByteBuffer.wrap(record.headers().lastHeader(name).value()).getLong();
    }
}
