CREATE INDEX allocation_outbox_pending_publication_idx
    ON allocation_outbox (occurred_at, event_id)
    WHERE published_at IS NULL;
