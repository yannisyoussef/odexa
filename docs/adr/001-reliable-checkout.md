# 001 — Durable asynchronous checkout

Status: accepted

## Context

Order acceptance, stock reservation and payment have different persistence owners. Losing a message after committing state, repeating a payment after a timeout, or releasing uncertain stock would violate checkout invariants. A distributed transaction would couple service availability and infrastructure unnecessarily.

## Decision

Use separate catalog, inventory, order and payment deployables/databases. Identity remains in Keycloak; a fixed-route edge and independent payment provider complete the local topology. The only synchronous business lookup before order acceptance is the authoritative catalog read. Checkout is intentionally one product and USD initially.

Every domain mutation that emits a business fact appends an outbox record in the same PostgreSQL transaction. A publisher takes `SKIP LOCKED` row claims, waits for a bounded Kafka acknowledgement, then marks publication inside that transaction. Only the earliest unpublished record for an aggregate is eligible. A crash after Kafka acknowledgement can duplicate delivery; it cannot erase the database's publication intent. This small-system implementation holds database claims during bounded broker sends instead of introducing CDC.

Consumers insert an inbox marker and apply their business changes/outbox in one local transaction. Kafka offsets advance only after successful handling. Bounded retries route poison records to a matching-partition DLT; failed DLT publication is not successful recovery. DLTs require operator diagnosis/replay; no automatic data repair is implied.

Inventory uses conditional database updates, not process locks. Reservations remember their emitted event ID; payment outcomes must reference it. Orders persist early payment evidence until its causative reservation is seen. Contradictory late terminal outcomes never reopen a completed aggregate.

Payments are durable jobs with database leases and fencing. Provider calls occur outside database transactions with a stable order UUID as the provider idempotency key. Provider outcomes and result events commit atomically. Timeout is uncertainty, never decline: bounded retries end in `REVIEW_REQUIRED` with inventory held. Automatic expiry without payment reconciliation is deliberately excluded.

## Consequences

The workflow is eventually consistent and responses expose intermediate states. Clients poll the owned resource rather than assuming synchronous completion. Each service can restart independently without losing accepted work. The outbox and inbox need retention/operational tooling before long-lived production use. Kafka carries business facts; RabbitMQ will be considered only when an actual work-distribution use case exists.

The shared runtime contains transport/security primitives, not shared business entities. Services cannot query each other's databases. Provider-specific representations remain behind the payment port; Stripe is not required for local operation and is not yet implemented.
