CREATE TABLE allocation_outbox (
    event_id UUID NOT NULL,
    allocation_id BIGINT NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    schema_version INTEGER NOT NULL,
    payload TEXT NOT NULL,
    occurred_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    published_at TIMESTAMP(6) WITH TIME ZONE NULL,
    CONSTRAINT allocation_outbox_pkey PRIMARY KEY (event_id),
    CONSTRAINT allocation_outbox_allocation_id_key UNIQUE (allocation_id),
    CONSTRAINT allocation_outbox_allocation_id_fkey
        FOREIGN KEY (allocation_id) REFERENCES allocations (id)
);
