CREATE TABLE IF NOT EXISTS product (
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
