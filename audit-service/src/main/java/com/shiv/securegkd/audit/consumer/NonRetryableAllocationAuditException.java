package com.shiv.securegkd.audit.consumer;

public final class NonRetryableAllocationAuditException extends AllocationAuditProcessingException {

    public enum Reason {
        INVALID_KAFKA_EVENT_KEY,
        INVALID_EVENT_CONTRACT,
        EVENT_IDENTITY_MISMATCH
    }

    private final Reason reason;

    public NonRetryableAllocationAuditException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
