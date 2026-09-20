-- Local fixtures only; application-local.properties is the sole importer.
INSERT INTO product (tenant_id, id, name, description, unit_price_minor, currency, active)
VALUES ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', '11111111-1111-4111-8111-111111111111',
        'Odexa canvas tote', 'A reusable canvas tote.', 2500, 'USD', TRUE),
       ('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', '22222222-2222-4222-8222-222222222222',
        'Odexa canvas tote', 'A reusable canvas tote.', 2500, 'USD', TRUE),
       ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', '33333333-3333-4333-8333-333333333333',
        'Odexa notebook', 'A ruled notebook.', 1200, 'USD', TRUE)
ON CONFLICT (tenant_id, id) DO NOTHING;
