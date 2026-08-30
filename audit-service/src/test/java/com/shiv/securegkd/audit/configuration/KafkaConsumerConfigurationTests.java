package com.shiv.securegkd.audit.configuration;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaConsumerConfigurationTests {

    @Test
    void configuresExplicitRecordAcknowledgementWithoutAutoCommitOrRecoveryPolicy() {
        KafkaProperties properties = new KafkaProperties();
        properties.setBootstrapServers(List.of("localhost:29092"));

        var factory = new KafkaConsumerConfiguration().kafkaListenerContainerFactory(properties);

        assertThat(factory.getContainerProperties().getAckMode())
                .isEqualTo(ContainerProperties.AckMode.RECORD);
        assertThat(factory.createContainer("secure-gkd.allocation-created").getCommonErrorHandler())
                .isInstanceOf(CommonContainerStoppingErrorHandler.class);
        assertThat(factory.getConsumerFactory())
                .isInstanceOf(DefaultKafkaConsumerFactory.class);

        DefaultKafkaConsumerFactory<?, ?> consumerFactory =
                (DefaultKafkaConsumerFactory<?, ?>) factory.getConsumerFactory();
        assertThat(consumerFactory.getConfigurationProperties())
                .containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
                .containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    }
}
