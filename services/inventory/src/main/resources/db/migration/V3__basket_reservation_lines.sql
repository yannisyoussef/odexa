CREATE TABLE reservation_line (
    tenant_id uuid NOT NULL,
    order_id uuid NOT NULL,
    product_id uuid NOT NULL,
    quantity integer NOT NULL CHECK (quantity BETWEEN 1 AND 100),
    PRIMARY KEY (tenant_id, order_id, product_id),
    FOREIGN KEY (tenant_id, order_id) REFERENCES inventory_reservation(tenant_id, order_id)
);
INSERT INTO reservation_line SELECT tenant_id, order_id, product_id, quantity FROM inventory_reservation;
ALTER TABLE inventory_reservation DROP COLUMN product_id, DROP COLUMN quantity;
