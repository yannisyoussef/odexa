-- Local fixtures only; application-local.properties is the sole importer.
INSERT INTO inventory_stock (tenant_id, product_id, on_hand)
VALUES ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', '11111111-1111-4111-8111-111111111111', 100),
       ('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', '22222222-2222-4222-8222-222222222222', 100),
       ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', '33333333-3333-4333-8333-333333333333', 100)
ON CONFLICT (tenant_id, product_id) DO NOTHING;
