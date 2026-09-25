-- Derive historical lines only from the accepted order-owned snapshot.
CREATE TABLE order_line (
    order_id uuid NOT NULL REFERENCES customer_order(id),
    product_id uuid NOT NULL,
    quantity integer NOT NULL CHECK (quantity BETWEEN 1 AND 100),
    product_name text NOT NULL,
    unit_price_minor bigint NOT NULL CHECK (unit_price_minor >= 0),
    line_total_minor bigint NOT NULL CHECK (line_total_minor >= 0),
    catalog_version bigint NOT NULL CHECK (catalog_version >= 0),
    PRIMARY KEY (order_id, product_id),
    CHECK (line_total_minor::numeric = unit_price_minor::numeric * quantity)
);
INSERT INTO order_line SELECT id, product_id, quantity, product_name, unit_price_minor,
    total_minor, catalog_version FROM customer_order;
ALTER TABLE customer_order DROP COLUMN product_id, DROP COLUMN quantity, DROP COLUMN product_name,
    DROP COLUMN unit_price_minor, DROP COLUMN catalog_version;
