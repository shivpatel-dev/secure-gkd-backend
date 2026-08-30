package com.shiv.securegkd.allocation.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class AllocationOutboxPublisherConfigurationTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AllocationOutboxPublisherConfiguration.class);

    @Test
    void publisherIsAbsentWhenNotExplicitlyEnabled() {
        contextRunner.run(context -> assertThat(context)
                .doesNotHaveBean(AllocationOutboxPublisher.class)
                .doesNotHaveBean(AllocationOutboxPublisherProperties.class));
    }
}
