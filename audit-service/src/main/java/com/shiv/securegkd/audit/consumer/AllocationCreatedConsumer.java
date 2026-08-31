package com.shiv.securegkd.audit.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shiv.securegkd.audit.event.AllocationCreated;
import com.shiv.securegkd.audit.persistence.AllocationAuditPersistenceOutcome;
import com.shiv.securegkd.audit.persistence.AllocationAuditPersistenceService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class AllocationCreatedConsumer {

    private static final Logger LOGGER = LoggerFactory.getLogger(AllocationCreatedConsumer.class);

    private final ObjectMapper objectMapper;
    private final AllocationAuditPersistenceService persistenceService;

    public AllocationCreatedConsumer(
            ObjectMapper objectMapper,
            AllocationAuditPersistenceService persistenceService
    ) {
        this.objectMapper = objectMapper;
        this.persistenceService = persistenceService;
    }

    @KafkaListener(
            topics = "${secure-gkd.audit.consumer.topic}",
            groupId = "${secure-gkd.audit.consumer.group}",
            autoStartup = "${spring.kafka.listener.auto-startup:true}"
    )
    public void consume(ConsumerRecord<String, String> record) {
        UUID transportEventId = parseTransportEventId(record.key());
        AllocationCreated event = deserializeEvent(record.value());

        if (!transportEventId.equals(event.eventId())) {
            throw new NonRetryableAllocationAuditException(
                    NonRetryableAllocationAuditException.Reason.EVENT_IDENTITY_MISMATCH,
                    "Kafka key does not match payload eventId",
                    null
            );
        }

        try {
            AllocationAuditPersistenceOutcome outcome = persistenceService.persist(event);
            if (outcome == AllocationAuditPersistenceOutcome.PERSISTED) {
                LOGGER.info(
                        "allocation_audit_persisted eventId={} topic={} partition={} offset={}",
                        event.eventId(),
                        record.topic(),
                        record.partition(),
                        record.offset()
                );
            } else if (outcome == AllocationAuditPersistenceOutcome.DUPLICATE) {
                LOGGER.info(
                        "allocation_audit_duplicate_ignored eventId={} topic={} partition={} offset={}",
                        event.eventId(),
                        record.topic(),
                        record.partition(),
                        record.offset()
                );
            } else {
                throw new IllegalStateException("Unexpected allocation audit persistence outcome");
            }
        } catch (RuntimeException exception) {
            throw new AllocationAuditProcessingException(
                    "Allocation audit persistence failed",
                    exception
            );
        }
    }

    private UUID parseTransportEventId(String key) {
        try {
            return UUID.fromString(key);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new NonRetryableAllocationAuditException(
                    NonRetryableAllocationAuditException.Reason.INVALID_KAFKA_EVENT_KEY,
                    "Kafka key is not a valid event UUID",
                    exception
            );
        }
    }

    private AllocationCreated deserializeEvent(String value) {
        try {
            return objectMapper.readValue(value, AllocationCreated.class);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new NonRetryableAllocationAuditException(
                    NonRetryableAllocationAuditException.Reason.INVALID_EVENT_CONTRACT,
                    "AllocationCreated event contract is invalid",
                    exception
            );
        }
    }
}
