ALTER TABLE customer_order DROP CONSTRAINT customer_order_status_check;
ALTER TABLE customer_order ADD CONSTRAINT customer_order_status_check
    CHECK (status IN ('CREATED','PENDING_PAYMENT','CONFIRMED','STOCK_REJECTED','PAYMENT_FAILED','CANCELLED','EXPIRED'));
ALTER TABLE customer_order ADD CONSTRAINT order_undispatched_terminal_evidence
    CHECK (status NOT IN ('CANCELLED','EXPIRED') OR (reservation_event_id IS NULL AND deferred_event_id IS NULL));

CREATE TABLE order_history (
    order_id UUID NOT NULL REFERENCES customer_order(id),
    version BIGINT NOT NULL CHECK (version >= 0),
    status VARCHAR(32) NOT NULL CHECK (status IN ('CREATED','PENDING_PAYMENT','CONFIRMED','STOCK_REJECTED','PAYMENT_FAILED','CANCELLED','EXPIRED')),
    occurred_at TIMESTAMPTZ NOT NULL,
    reason VARCHAR(40) NOT NULL CHECK (reason IN ('ORDER_CREATED','STOCK_RESERVED','INSUFFICIENT_STOCK','PAYMENT_AUTHORIZED','PAYMENT_DECLINED','CUSTOMER_CANCELLED','DISPATCH_EXPIRED','LEGACY_SNAPSHOT')),
    PRIMARY KEY (order_id, version)
);
-- Prior transition times were not persisted. This records an observation, not invented history.
INSERT INTO order_history (order_id, version, status, occurred_at, reason)
    SELECT id, version, status, CURRENT_TIMESTAMP, 'LEGACY_SNAPSHOT' FROM customer_order;
CREATE INDEX order_customer_created ON customer_order (tenant_id, customer_id, created_at DESC, id DESC);
CREATE INDEX order_customer_status_created ON customer_order (tenant_id, customer_id, status, created_at DESC, id DESC);
CREATE INDEX order_merchant_created ON customer_order (tenant_id, created_at DESC, id DESC);
CREATE INDEX order_merchant_status_created ON customer_order (tenant_id, status, created_at DESC, id DESC);
CREATE INDEX order_stale_created ON customer_order (created_at, id) WHERE status = 'CREATED';
