# ADR 005: atomic multi-item checkout and reservation

Status: accepted

## Context

Single-product order and reservation headers cannot honestly represent a basket. The durable
workflow must preserve accepted prices, one payment, no overselling, causal transitions and
historical work across an upgrade. Partial acceptance would introduce fulfilment and financial
allocation policies that are outside this change.

## Decision

A checkout contains 1–20 distinct products, each with 1–100 units (at most 2000 total units).
Reject duplicate IDs rather than merging quantities. Canonical order is the lexical order of
lowercase UUID strings. It governs semantic fingerprints, line representations and inventory
stock lock acquisition. USD remains the only supported currency. Free lines may accompany paid
lines; a zero-total basket is rejected before persistence. Both line multiplication and total
addition use checked signed 64-bit arithmetic; overflow returns 422 ORDER_TOTAL_OVERFLOW.

The v1 HTTP boundary accepts either the legacy productId/quantity pair or items, with one
opaque payment reference. Both normalize immediately. One-line fingerprints use the unchanged
released SHA-256 input; baskets use a domain-separated canonical input. Same key/same intent
replays before catalog lookup; added/removed lines, quantities or payment reference conflict.
Prices are deliberately absent from the fingerprint: accepted snapshots remain authoritative.

Order forwards the end-user bearer and correlation to a bounded, private catalog batch route.
Catalog independently verifies JWT, CUSTOMER role and tenant. A single SQL statement gives one
consistent snapshot of all requested products. Missing/foreign IDs return the same 404; inactive
products cause whole-checkout 409. Catalog failure remains a bounded failure before acceptance.
There is no remote call inside the order writer transaction and no cross-service database read.

The order header owns total, currency, lifecycle and idempotency. Immutable order_line records
own product ID, name, accepted unit/line price, quantity and catalog version. Order header,
lines, initial history and order.created v2 commit atomically. Later product changes never
reprice the order. Every public view embeds the same bounded lines. Legacy productId/quantity
are present only for one-line orders; no first-product approximation appears on a basket.
History remains a small sequence of state transitions, not repeated price snapshots.

Inventory owns one reservation header per tenant/order and unique reservation_line records.
At READ_COMMITTED isolation, claim the reservation header, acquire existing stock row locks in
canonical product order, inspect every availability, then mutate all lines or none. No distributed
compensation is needed. Missing stock at lookup is a rejection; a concurrent stock creation can
be used by a later checkout. The reservation claim serializes distinct events for the same order.
All stock mutations and inbox/outbox records share the local transaction. Merchant stock writes
lock one row, so they cannot introduce an inverse multi-row lock dependency.

| Reservation state | Signal | Result | Stock effect |
| --- | --- | --- | --- |
| Absent | Complete basket available | RESERVED + one inventory.reserved v2 | Hold every line; each stock version +1 |
| Absent | Any line unavailable | REJECTED + one inventory.rejected v1 | None; no version changes |
| Any existing | Same checkout snapshot | Same state | None |
| Any existing | Conflicting checkout snapshot | Reject event; rollback inbox | None |
| RESERVED | Causally matching authorized payment | COMMITTED | Subtract every line from held/on-hand; each version +1 |
| RESERVED | Causally matching decline | RELEASED | Subtract every held quantity; each version +1 |
| COMMITTED / RELEASED | Duplicate or late matching outcome | Same terminal state | None |
| RESERVED | Provider uncertainty | RESERVED | Retain every hold pending reconciliation |
| COMMITTED | Full financial refund | COMMITTED | None; refund is not a physical return |

Settlement locks the reservation first, then mutates stock in the same canonical order as
reservation. Failure on any line rolls back all earlier line mutations. No process-local lock
or retry-on-deadlock policy is part of correctness. Tests synchronize concurrent starts and
assert aggregate invariants independently of which basket wins.

Payment validates v1/v2 reservation transport, then retains its existing total-only provider
port. One payment covers the whole basket. Reconciliation, signed webhook invalidation hints,
stable provider idempotency, amount/currency/linkage verification and full refunds are unchanged.
No line details are sent to Stripe or the simulator. A full refund equals the entire payment;
there are no item allocations or partial refunds. Unknown outcomes retain all holds.

Pre-dispatch cancellation/expiry keep their existing state and dispatch fence. They remove one
never-attempted order.created event, irrespective of version; they never independently release
reserved items. The order state machine and early-payment causality rules do not change.

## Compatibility and migrations

New publishers emit order.created v2 and inventory.reserved v2 on commerce.events.v1. All other
facts remain v1. The runtime explicitly allows v2 only for those two types. Consumers decode
both historical v1 and current v2, normalizing to one local model. Unknown versions still fail
closed. Rejections are already order-level, so their shape/version does not change.

Order V5 and inventory V3 copy existing owned data into line tables and drop obsolete scalar
columns. IDs, states, accepted totals, versions, timestamps, fingerprints, history, active holds,
provider records and durable event payloads stay intact. No new payment/simulator migration is
needed. Install the complete released Flyway chain in tests before applying these append-only
migrations. Historical unpublished work can still reserve, pay and settle after upgrade.

Use a coordinated deployment/maintenance window; rolling mixed-version binaries are unsupported.
Take restorable backups first. Old applications cannot run against the new line schema. Rollback
means restoring the old database and binaries together. Neither migrations nor startup rewrite
old outbox payloads. Legacy client request compatibility has no promised removal date. Clients
reading multi-item orders must understand items; additive fields require tolerant decoders.

## Consequences and evidence

The maximum compact request is under 2 KB; the existing 32 KB gateway limit is unchanged. Bounded
20-line events remain well below broker limits. A page embeds at most 100 orders × 20 lines.
Line reads are bounded indexed lookups; a future measured query bottleneck may justify batching
those reads. No new dependencies, speculative indexes, cart service, discounts, tax, shipping,
partial fulfilment or return model are introduced.

Verification includes canonical/legacy replay, price changes, zero/overflow boundaries, reverse
and partial overlap under PostgreSQL row locking, duplicate/late settlement, rollback, migration
with active holds and old durable work, Kafka delivery, gateway limits and fresh/preserved Compose
flows. The smoke path validates the authoritative full total, whole-basket rejection, reconciliation
and refund without restocking. Structural contract checks remain deliberately lightweight.
