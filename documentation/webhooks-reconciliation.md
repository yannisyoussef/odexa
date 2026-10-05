---
title: Webhooks and reconciliation
description: Treat signed provider callbacks as durable hints and provider reads as authority.
---

Stripe Test Mode and the simulator post only to fixed webhook routes. Each
callback is signature-checked against the exact raw body, bounded to 64 KiB,
deduplicated durably, and used to schedule reconciliation.

Webhooks are invalidation hints, not final authority. The payment service reads
provider state and verifies amount, currency, payment linkage, and refund
linkage before changing a durable result. Early delivery and lost command
responses converge through the same reconciliation path.

Missing or ambiguous provider evidence remains `REVIEW_REQUIRED`. Operators
must not edit provider metadata or manufacture success or failure events.
