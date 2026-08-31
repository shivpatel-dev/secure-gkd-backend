CREATE TABLE allocation_audit_record (
    audit_record_id UUID PRIMARY KEY,
    source_event_id UUID NOT NULL,
    schema_version INTEGER NOT NULL,
    event_occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    source_allocation_id BIGINT NOT NULL,
    allocated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    source_game_id BIGINT NOT NULL,
    game_code VARCHAR(100) NOT NULL,
    request_id VARCHAR(255) NOT NULL,
    persisted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_allocation_audit_schema_version CHECK (schema_version = 1),
    CONSTRAINT chk_allocation_audit_source_allocation_id CHECK (source_allocation_id > 0),
    CONSTRAINT chk_allocation_audit_source_game_id CHECK (source_game_id > 0)
);
