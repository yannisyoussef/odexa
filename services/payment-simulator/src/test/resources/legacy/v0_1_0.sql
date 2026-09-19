-- Separate simulator database. Idempotency survives restarts and spans replicas.
CREATE TABLE IF NOT EXISTS provider_payment (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL UNIQUE,
    amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
    currency VARCHAR(3) NOT NULL CHECK (currency = 'USD'),
    payment_method VARCHAR(128) NOT NULL CHECK (payment_method IN ('pm_approved', 'pm_declined')),
    status VARCHAR(16) NOT NULL CHECK (status IN ('AUTHORIZED', 'DECLINED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
