-- Existing rows may already have escaped despite an uncommitted acknowledgement.
ALTER TABLE outbox ADD COLUMN dispatch_started_at TIMESTAMPTZ;
UPDATE outbox SET dispatch_started_at = COALESCE(published_at, CURRENT_TIMESTAMP);
