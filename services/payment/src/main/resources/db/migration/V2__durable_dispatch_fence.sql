-- Existing rows may already have escaped despite an uncommitted acknowledgement.
ALTER TABLE outbox ADD COLUMN dispatch_started_at TIMESTAMPTZ;
-- Published rows are never consulted for the fence, so only the unpublished tail is rewritten.
UPDATE outbox SET dispatch_started_at = CURRENT_TIMESTAMP WHERE published_at IS NULL;
