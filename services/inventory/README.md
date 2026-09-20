# Inventory service

Inventory owns stock and basket reservations in its private PostgreSQL database. One logical
reservation per tenant/order has1–20 unique `reservation_line` rows. New order.created v2 and
historical v1 events normalize to the same sorted representation. A duplicate event or order
is inert; conflicting snapshots fail before stock changes. Rejected reservations remain durable.

Within one READ_COMMITTED transaction, claim the reservation, lock each stock row in ascending
canonical UUID order, check every line, then reserve all or none. Missing stock and insufficient
availability emit one v1 inventory.rejected with INSUFFICIENT_STOCK. No line-level availability
is disclosed. Rejected baskets do not advance any stock version. A successful basket emits one
inventory.reserved v2 carrying intent and aggregate total, without catalog names/prices.

Authorization commits every line; decline releases every line, using the same stock lock order
and reservation row lock. Each mutated stock row increments its version once per reservation or
settlement. Duplicate/late outcomes are inert. Payment causation must match the reserved event.
Unknown payment outcomes hold all lines pending reconciliation. A financial refund neither
restocks nor changes the committed reservation; returns/fulfilment remain separate.

V3 backfills historical active, committed, released and rejected reservations into one-line
records without changing stock, state, versions or causal IDs. Historical unpublished v1 work
remains supported. Inbox, stock, reservation and outcome outbox changes share one transaction.

Tests cover reverse-order/partially overlapping baskets, duplicate delivery and settlement,
no oversell/partial holds, rollback, Kafka delivery, old-schema upgrade and v1 replay.
