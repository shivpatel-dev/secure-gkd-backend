package com.shiv.securegkd.audit.consumer;

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
        try {
            UUID transportEventId = UUID.fromString(record.key());
            AllocationCreated event = objectMapper.readValue(record.value(), AllocationCreated.class);

            if (!transportEventId.equals(event.eventId())) {
                throw new IllegalArgumentException("Kafka key does not match payload eventId");
            }

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
        } catch (Exception exception) {
            LOGGER.warn(
                    "allocation_audit_processing_failed topic={} partition={} offset={} failureType={}",
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    exception.getClass().getSimpleName()
            );
            throw new AllocationAuditProcessingException("Allocation audit event processing failed");
        }
    }
}
