---
title: Payments and full refunds
description: Understand provider uncertainty, customer visibility, and merchant refund commands.
---

Payment creation begins internally after inventory reservation. The payment
service calls the configured provider outside its database transaction with a
stable order UUID as the provider idempotency key.

Timeout, malformed response, transport failure, and unexpected status are
uncertain—not declines. Bounded retries move unresolved work to
`REVIEW_REQUIRED`; stock stays reserved while read-only reconciliation seeks an
authoritative provider result. There is no administrative bypass endpoint.

Merchant full-refund commands require a merchant role and their own
`Idempotency-Key`. Exact retries converge on the same refund; changed semantics
conflict. Customers can read refunds on payments they own. A successful
financial refund does not restore inventory, cancel the order, or rewrite the
checkout result. Partial and item refunds are not supported.
