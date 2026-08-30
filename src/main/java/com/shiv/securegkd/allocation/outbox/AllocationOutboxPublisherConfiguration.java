package com.shiv.securegkd.allocation.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(AllocationOutboxPublisherProperties.class)
@ConditionalOnProperty(
        prefix = "secure-gkd.kafka.publisher",
        name = "enabled",
        havingValue = "true"
)
class AllocationOutboxPublisherConfiguration {

    @Bean
    AllocationOutboxPublisher allocationOutboxPublisher(
            AllocationOutboxRepository allocationOutboxRepository,
            AllocationOutboxPublicationStatusService publicationStatusService,
            KafkaTemplate<String, String> kafkaTemplate,
            AllocationOutboxPublisherProperties properties
    ) {
        return new AllocationOutboxPublisher(
                allocationOutboxRepository,
                publicationStatusService,
                kafkaTemplate,
                properties
        );
    }
}
