ALTER TABLE allocation_audit_record
    ADD CONSTRAINT uk_allocation_audit_source_event_id UNIQUE (source_event_id);
