---
title: Catalog and inventory
description: Manage tenant products and stock with strong optimistic preconditions.
---

Catalog product writes and inventory adjustments use strong entity tags. Read
the resource, keep the quoted `ETag`, and send it as `If-Match` on the next
write. Missing preconditions return 428, malformed tags return 400, and a stale
version returns 412. Re-read and decide whether the intended change is still
valid instead of retrying blindly.

Inventory adjustment changes an existing row; it is not stock provisioning.
The requested on-hand amount cannot drop below active reservations. A
successful adjustment, reservation, commitment, or release increments the
version, so background checkout activity can invalidate a merchant's tag.

Conditional GET with `If-None-Match` may return 304 without a body.
