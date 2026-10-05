---
title: Orders and lifecycle
description: Read owned and merchant views, history, cancellation, and terminal states.
---

Customer queries filter by signed tenant and subject together. Merchant paths
are explicit and tenant-scoped. Collection cursors are opaque; do not parse or
manufacture them.

The history resource exposes lifecycle facts without provider references or
customer subjects. Event delivery is at least once, but terminal order states
never move backward in response to a late contradictory outcome.

Cancellation is deliberately narrow. A customer may cancel only a CREATED
order whose checkout dispatch has never been attempted. The command has no
mutable body and checks eligibility atomically, so it needs neither `If-Match`
nor another idempotency key. Dispatched, uncertain, or terminal orders return
conflict and are not compensated automatically.
