---
title: Multi-item checkout
description: Submit an idempotent basket and follow its asynchronous outcome.
---

A checkout contains 1–20 distinct products and 1–100 units per line. All
products must belong to the caller's tenant, be active, and use USD. Duplicate
products are rejected. Catalog resolution and inventory reservation apply to
the whole basket; partial acceptance is not supported.

`POST /api/v1/orders` requires a customer bearer token and an
`Idempotency-Key`. The identity is tenant, customer, and key. An exact semantic
retry returns the same order; reusing the key for a different sorted basket or
provider reference returns conflict. A new order returns 201 and a replay 200.

Creation is asynchronous after persistence. Poll the owned order resource and
its history. A basket has one reservation, one payment, and at most one active
or successful full financial refund.
