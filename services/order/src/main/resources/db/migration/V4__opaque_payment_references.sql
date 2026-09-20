ALTER TABLE customer_order DROP CONSTRAINT customer_order_payment_method_check;
ALTER TABLE customer_order ALTER COLUMN payment_method TYPE varchar(128);
ALTER TABLE customer_order ADD CHECK (payment_method ~ '^pm_[A-Za-z0-9_]{1,125}$');
