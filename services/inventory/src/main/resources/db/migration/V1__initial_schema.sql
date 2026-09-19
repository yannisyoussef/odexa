-- Initial immutable runtime schema. Every service owns its own local database.
CREATE TABLE outbox (
    sequence bigint GENERATED ALWAYS AS IDENTITY UNIQUE NOT NULL,
    event_id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    aggregate_id varchar(128) NOT NULL,
    topic varchar(100) NOT NULL CHECK (topic = 'commerce.events.v1'),
    payload jsonb NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    occurred_at timestamptz NOT NULL,
    published_at timestamptz
);
CREATE INDEX outbox_unpublished_sequence ON outbox (sequence)
    WHERE published_at IS NULL;
CREATE INDEX outbox_unpublished_aggregate ON outbox (aggregate_id, sequence)
    WHERE published_at IS NULL;

CREATE TABLE inbox (
    consumer varchar(128) NOT NULL,
    event_id uuid NOT NULL,
    received_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (consumer, event_id)
);

CREATE TABLE inventory_stock (
    tenant_id UUID NOT NULL,
    product_id UUID NOT NULL,
    on_hand BIGINT NOT NULL CHECK (on_hand >= 0),
    reserved BIGINT NOT NULL DEFAULT 0 CHECK (reserved >= 0 AND reserved <= on_hand),
    version BIGINT NOT NULL DEFAULT 1 CHECK (version > 0),
    PRIMARY KEY (tenant_id, product_id)
);

-- Rejections are retained to make repeated order events deterministic, even after restocking.
-- No product FK: a nonexistent product is a durable insufficient-stock rejection.
CREATE TABLE inventory_reservation (
    tenant_id UUID NOT NULL,
    order_id UUID NOT NULL,
    customer_id VARCHAR(255) NOT NULL CHECK (length(customer_id) > 0),
    product_id UUID NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity BETWEEN 1 AND 100),
    total_minor BIGINT NOT NULL CHECK (total_minor >= 0),
    currency VARCHAR(3) NOT NULL CHECK (currency = 'USD'),
    payment_method VARCHAR(200) NOT NULL CHECK (length(payment_method) > 0),
    state VARCHAR(16) NOT NULL CHECK (state IN ('RESERVED', 'COMMITTED', 'RELEASED', 'REJECTED')),
    reservation_event_id UUID,
    PRIMARY KEY (tenant_id, order_id)
);
