---
title: Commerce integration events
description: Operate Odexa's at-least-once transactional outbox and inbox flows.
---

Odexa publishes order, inventory, payment, and refund facts to
`commerce.events.v1`. Every record key is the order UUID. Ordering is per Kafka
partition, not a global causal guarantee across services.

Publishers use transactional outboxes. Consumers deduplicate `eventId` in the
same database transaction as their state change. Malformed owned events and
unknown versions retry within a bound, then move to the diagnostic dead-letter
topic with transport metadata.

Multi-item checkout publishes `order.created` and `inventory.reserved` version
2 payloads on the existing topic while consumers retain version 1 decoding for
historical outboxes and replay. Services must be upgraded together; rolling
mixed event-schema versions are unsupported.

This page is authored guidance. The source AsyncAPI contract remains
authoritative, and Specistry does not ingest it in this release.
