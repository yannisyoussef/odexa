-- Released simulator rows retain their provider even if the new default changes.
ALTER TABLE payment ADD COLUMN provider varchar(24) NOT NULL DEFAULT 'simulator'
    CHECK (provider IN ('simulator', 'stripe'));
ALTER TABLE payment ADD COLUMN reconciliation_attempts integer NOT NULL DEFAULT 0;
ALTER TABLE payment DROP CONSTRAINT payment_check1;
ALTER TABLE payment ADD CONSTRAINT payment_terminal_reference
    CHECK (status NOT IN ('AUTHORIZED', 'DECLINED') OR provider_id IS NOT NULL);
CREATE UNIQUE INDEX payment_provider_reference ON payment(provider, provider_id) WHERE provider_id IS NOT NULL;
CREATE INDEX payment_review_due ON payment(next_attempt_at) WHERE status = 'REVIEW_REQUIRED';

CREATE TABLE refund (
    id uuid PRIMARY KEY,
    payment_id uuid NOT NULL REFERENCES payment(id),
    idempotency_key varchar(128) NOT NULL,
    amount_minor bigint NOT NULL CHECK (amount_minor > 0),
    currency varchar(3) NOT NULL CHECK (currency = 'USD'),
    status varchar(24) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED', 'REVIEW_REQUIRED')),
    provider_id varchar(200),
    correlation_id varchar(36) NOT NULL,
    attempts integer NOT NULL DEFAULT 0,
    reconciliation_attempts integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_token uuid,
    lease_until timestamptz,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(payment_id, idempotency_key),
    CHECK ((lease_token IS NULL) = (lease_until IS NULL)),
    CHECK (status NOT IN ('SUCCEEDED', 'FAILED') OR provider_id IS NOT NULL)
);
CREATE UNIQUE INDEX refund_one_active_full ON refund(payment_id) WHERE status <> 'FAILED';
CREATE INDEX refund_due ON refund(next_attempt_at) WHERE status IN ('PENDING', 'REVIEW_REQUIRED');
CREATE TABLE provider_event (
    provider varchar(24) NOT NULL CHECK (provider IN ('simulator', 'stripe')),
    event_id varchar(200) NOT NULL,
    event_type varchar(100) NOT NULL,
    object_id varchar(200),
    received_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(provider, event_id)
);
