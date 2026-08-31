package com.shiv.securegkd.audit.configuration;

import com.shiv.securegkd.audit.consumer.NonRetryableAllocationAuditException;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.KafkaException;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.kafka.support.SendResult;
import org.springframework.util.backoff.FixedBackOff;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AllocationAuditConsumerProperties.class)
public class KafkaConsumerConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(KafkaConsumerConfiguration.class);
    private static final String FAILURE_REASON_HEADER = "secure-gkd-dlt-failure-reason";
    private static final Duration DEAD_LETTER_SEND_TIMEOUT = Duration.ofSeconds(10);

    @Bean
    DefaultErrorHandler allocationAuditErrorHandler(
            KafkaOperations<Object, Object> kafkaOperations,
            AllocationAuditConsumerProperties properties
    ) {
        validate(properties);

        DeadLetterPublishingRecoverer deadLetterRecoverer = new DeadLetterPublishingRecoverer(
                kafkaOperations,
                (record, exception) -> new TopicPartition(
                        properties.getDeadLetterTopic(),
                        record.partition()
                )
        ) {
            @Override
            protected void verifySendResult(
                    KafkaOperations<Object, Object> operations,
                    ProducerRecord<Object, Object> outboundRecord,
                    CompletableFuture<SendResult<Object, Object>> result,
                    ConsumerRecord<?, ?> sourceRecord
            ) {
                try {
                    result.get(DEAD_LETTER_SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new DeadLetterPublicationException(
                            exception.getClass().getSimpleName()
                    );
                } catch (ExecutionException | TimeoutException | RuntimeException exception) {
                    throw new DeadLetterPublicationException(rootCauseType(exception));
                }
            }
        };
        deadLetterRecoverer.setVerifyPartition(false);
        deadLetterRecoverer.setFailIfSendResultIsError(true);
        deadLetterRecoverer.setWaitForSendResultTimeout(DEAD_LETTER_SEND_TIMEOUT);
        deadLetterRecoverer.setLogLevel(KafkaException.Level.DEBUG);
        deadLetterRecoverer.setLogRecoveryRecord(false);
        deadLetterRecoverer.excludeHeader(
                DeadLetterPublishingRecoverer.HeaderNames.HeadersToAdd.EX_MSG,
                DeadLetterPublishingRecoverer.HeaderNames.HeadersToAdd.EX_STACKTRACE
        );
        deadLetterRecoverer.addHeadersFunction((record, exception) -> new RecordHeaders().add(
                FAILURE_REASON_HEADER,
                failureReason(exception).getBytes(StandardCharsets.UTF_8)
        ));

        ConsumerRecordRecoverer observableRecoverer = (record, exception) -> {
            boolean nonRetryable = findNonRetryable(exception) != null;
            if (nonRetryable) {
                LOGGER.warn(
                        "allocation_audit_poison_message topic={} partition={} offset={} "
                                + "failureReason={} failureType={}",
                        record.topic(),
                        record.partition(),
                        record.offset(),
                        failureReason(exception),
                        rootCauseType(exception)
                );
            } else {
                LOGGER.warn(
                        "allocation_audit_retry_exhausted topic={} partition={} offset={} "
                                + "attempts={} failureType={}",
                        record.topic(),
                        record.partition(),
                        record.offset(),
                        properties.getRetryAttempts() + 1,
                        rootCauseType(exception)
                );
            }

            try {
                deadLetterRecoverer.accept(record, exception);
                LOGGER.warn(
                        "allocation_audit_dead_letter_recovered sourceTopic={} partition={} "
                                + "offset={} deadLetterTopic={} failureReason={}",
                        record.topic(),
                        record.partition(),
                        record.offset(),
                        properties.getDeadLetterTopic(),
                        failureReason(exception)
                );
            } catch (RuntimeException recoveryFailure) {
                LOGGER.error(
                        "allocation_audit_dead_letter_publication_failed sourceTopic={} "
                                + "partition={} offset={} deadLetterTopic={} failureType={}",
                        record.topic(),
                        record.partition(),
                        record.offset(),
                        properties.getDeadLetterTopic(),
                        recoveryFailure instanceof DeadLetterPublicationException publicationFailure
                                ? publicationFailure.getFailureType()
                                : rootCauseType(recoveryFailure)
                );
                throw recoveryFailure;
            }
        };

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                observableRecoverer,
                new FixedBackOff(
                        properties.getRetryBackoff().toMillis(),
                        properties.getRetryAttempts()
                )
        );
        errorHandler.addNotRetryableExceptions(NonRetryableAllocationAuditException.class);
        errorHandler.setAckAfterHandle(true);
        errorHandler.setResetStateOnRecoveryFailure(true);
        errorHandler.setLogLevel(KafkaException.Level.DEBUG);
        errorHandler.setRetryListeners(retryLogging(properties));
        return errorHandler;
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            KafkaProperties kafkaProperties,
            DefaultErrorHandler allocationAuditErrorHandler
    ) {
        Map<String, Object> consumerProperties = kafkaProperties.buildConsumerProperties(null);
        consumerProperties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        DefaultKafkaConsumerFactory<String, String> consumerFactory = new DefaultKafkaConsumerFactory<>(
                consumerProperties,
                new StringDeserializer(),
                new StringDeserializer()
        );
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.getContainerProperties().setDeliveryAttemptHeader(true);
        factory.setCommonErrorHandler(allocationAuditErrorHandler);
        return factory;
    }

    private RetryListener retryLogging(AllocationAuditConsumerProperties properties) {
        return new RetryListener() {
            @Override
            public void failedDelivery(
                    ConsumerRecord<?, ?> record,
                    Exception exception,
                    int deliveryAttempt
            ) {
                if (findNonRetryable(exception) == null
                        && deliveryAttempt <= properties.getRetryAttempts()) {
                    LOGGER.warn(
                            "allocation_audit_processing_failed_will_retry topic={} partition={} "
                                    + "offset={} deliveryAttempt={} nextDeliveryAttempt={} "
                                    + "failureType={}",
                            record.topic(),
                            record.partition(),
                            record.offset(),
                            deliveryAttempt,
                            deliveryAttempt + 1,
                            rootCauseType(exception)
                    );
                }
            }
        };
    }

    private void validate(AllocationAuditConsumerProperties properties) {
        if (properties.getDeadLetterTopic() == null || properties.getDeadLetterTopic().isBlank()) {
            throw new IllegalArgumentException("Audit dead-letter topic must not be blank");
        }
        if (properties.getRetryAttempts() < 0) {
            throw new IllegalArgumentException("Audit retry attempts must not be negative");
        }
        if (properties.getRetryBackoff() == null
                || properties.getRetryBackoff().isNegative()
                || properties.getRetryBackoff().isZero()) {
            throw new IllegalArgumentException("Audit retry backoff must be positive");
        }
    }

    private static String failureReason(Throwable failure) {
        NonRetryableAllocationAuditException nonRetryable = findNonRetryable(failure);
        return nonRetryable == null ? "RETRY_EXHAUSTED" : nonRetryable.getReason().name();
    }

    private static NonRetryableAllocationAuditException findNonRetryable(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof NonRetryableAllocationAuditException nonRetryable) {
                return nonRetryable;
            }
            current = current.getCause();
        }
        return null;
    }

    private static String rootCauseType(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getClass().getSimpleName();
    }

    private static final class DeadLetterPublicationException extends KafkaException {

        private final String failureType;

        private DeadLetterPublicationException(String failureType) {
            super("Allocation audit dead-letter publication failed");
            this.failureType = failureType;
        }

        private String getFailureType() {
            return failureType;
        }
    }
}
