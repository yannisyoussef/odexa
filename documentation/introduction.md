---
title: Architecture at a glance
description: Learn which Odexa service owns each commerce capability and trust boundary.
---

The gateway exposes fixed public routes and preserves bearer authentication,
ETags, conditional headers, idempotency keys, correlation IDs, bodies, and
status codes. Catalog owns products; inventory owns stock and reservations;
order owns checkout and lifecycle; payment owns provider interaction and full
refunds. The deterministic payment simulator is a local provider, not a public
commerce API.

Keycloak issues identities. PostgreSQL stores service-owned state. Kafka carries
commerce integration events written through transactional outboxes and applied
through transactional inboxes.

## Contract sources

The generated reference reads the real OpenAPI 3.1 files under
`contracts/openapi`. No documentation copy is maintained. Event behavior is
authored guidance based on `contracts/asyncapi/commerce.json`; the portal does
not claim first-class AsyncAPI rendering.
