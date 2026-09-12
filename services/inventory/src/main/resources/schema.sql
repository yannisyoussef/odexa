CREATE TABLE IF NOT EXISTS inventory_stock (
    tenant_id UUID NOT NULL,
    product_id UUID NOT NULL,
    on_hand BIGINT NOT NULL CHECK (on_hand >= 0),
    reserved BIGINT NOT NULL DEFAULT 0 CHECK (reserved >= 0 AND reserved <= on_hand),
    version BIGINT NOT NULL DEFAULT 1 CHECK (version > 0),
    PRIMARY KEY (tenant_id, product_id)
);

-- Rejections are retained to make repeated order events deterministic, even after restocking.
-- No product FK: a nonexistent product is a durable insufficient-stock rejection.
CREATE TABLE IF NOT EXISTS inventory_reservation (
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
