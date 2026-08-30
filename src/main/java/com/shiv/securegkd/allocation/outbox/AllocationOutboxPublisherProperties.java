package com.shiv.securegkd.allocation.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Objects;

@ConfigurationProperties("secure-gkd.kafka.publisher")
public class AllocationOutboxPublisherProperties {

    static final int MAX_BATCH_SIZE = 1_000;
    static final Duration MAX_ACKNOWLEDGEMENT_TIMEOUT = Duration.ofMinutes(1);

    private boolean enabled;
    private String topic = "secure-gkd.allocation-created";
    private Duration pollInterval = Duration.ofSeconds(1);
    private int batchSize = 100;
    private Duration acknowledgementTimeout = Duration.ofSeconds(10);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("topic must not be blank");
        }
        this.topic = topic;
    }

    public Duration getPollInterval() {
        return pollInterval;
    }

    public void setPollInterval(Duration pollInterval) {
        this.pollInterval = requirePositive(pollInterval, "pollInterval");
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "batchSize must be between 1 and " + MAX_BATCH_SIZE
            );
        }
        this.batchSize = batchSize;
    }

    public Duration getAcknowledgementTimeout() {
        return acknowledgementTimeout;
    }

    public void setAcknowledgementTimeout(Duration acknowledgementTimeout) {
        Duration validated = requirePositive(acknowledgementTimeout, "acknowledgementTimeout");
        if (validated.compareTo(MAX_ACKNOWLEDGEMENT_TIMEOUT) > 0) {
            throw new IllegalArgumentException(
                    "acknowledgementTimeout must not exceed " + MAX_ACKNOWLEDGEMENT_TIMEOUT
            );
        }
        this.acknowledgementTimeout = validated;
    }

    private Duration requirePositive(Duration value, String name) {
        Duration required = Objects.requireNonNull(value, name + " is required");
        if (required.isZero() || required.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return required;
    }
}
