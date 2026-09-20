ALTER TABLE provider_payment DROP CONSTRAINT provider_payment_payment_method_check;
ALTER TABLE provider_payment DROP CONSTRAINT provider_payment_status_check;
ALTER TABLE provider_payment ALTER COLUMN status TYPE varchar(24);
ALTER TABLE provider_payment ADD CHECK (status IN ('AUTHORIZED', 'DECLINED', 'REVIEW_REQUIRED'));
ALTER TABLE provider_payment ADD CHECK (payment_method IN ('pm_approved', 'pm_declined', 'pm_lost_response',
    'pm_unknown', 'pm_reconcile_declined', 'pm_refund_declined', 'pm_refund_unknown', 'pm_refund_lost'));
CREATE TABLE provider_refund (
    id uuid PRIMARY KEY,
    refund_id uuid NOT NULL UNIQUE,
    payment_id uuid NOT NULL REFERENCES provider_payment(id),
    amount_minor bigint NOT NULL CHECK(amount_minor > 0),
    currency varchar(3) NOT NULL CHECK(currency = 'USD'),
    status varchar(24) NOT NULL CHECK(status IN ('SUCCEEDED','FAILED','REVIEW_REQUIRED')),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX provider_refund_one_full ON provider_refund(payment_id) WHERE status <> 'FAILED';
CREATE TABLE provider_delivery (
    id uuid PRIMARY KEY,
    object_id uuid NOT NULL,
    event_type varchar(100) NOT NULL,
    attempts integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_token uuid,
    lease_until timestamptz,
    delivered_at timestamptz
);
