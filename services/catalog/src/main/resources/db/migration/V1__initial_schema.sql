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

CREATE TABLE product (
    tenant_id UUID NOT NULL,
    id UUID NOT NULL,
    name VARCHAR(200) NOT NULL CHECK (length(btrim(name)) > 0),
    description VARCHAR(2000) NOT NULL,
    unit_price_minor BIGINT NOT NULL CHECK (unit_price_minor >= 0),
    currency VARCHAR(3) NOT NULL CHECK (currency = 'USD'),
    active BOOLEAN NOT NULL,
    version BIGINT NOT NULL DEFAULT 1 CHECK (version > 0),
    PRIMARY KEY (tenant_id, id)
);
