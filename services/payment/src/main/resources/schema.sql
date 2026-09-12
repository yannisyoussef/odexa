-- Initial immutable service schema. The payment row is also the durable work item.
CREATE TABLE IF NOT EXISTS payment (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    order_id UUID NOT NULL,
    customer_id VARCHAR(255) NOT NULL,
    amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
    currency VARCHAR(3) NOT NULL CHECK (currency = 'USD'),
    payment_method VARCHAR(128) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'AUTHORIZED', 'DECLINED', 'REVIEW_REQUIRED')),
    provider_id VARCHAR(200),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    reservation_event_id UUID NOT NULL,
    correlation_id VARCHAR(36) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, order_id),
    CHECK ((lease_token IS NULL) = (lease_until IS NULL)),
    CHECK ((status IN ('AUTHORIZED', 'DECLINED')) = (provider_id IS NOT NULL))
);
CREATE INDEX IF NOT EXISTS payment_due_idx ON payment (next_attempt_at, created_at)
    WHERE status = 'PENDING';
