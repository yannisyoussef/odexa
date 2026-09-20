# Odexa

Multi-tenant commerce and order management, with durable multi-item checkout and atomic basket reservations. **[odexa.cc](https://odexa.cc)** is the product domain; this repository does not deploy to it.

Java 25 · Spring Boot 4.1.1 · Gradle Kotlin DSL · PostgreSQL 17 · Kafka · Keycloak

## Architecture

The API gateway routes authenticated requests to catalog, inventory, order and payment services. Each business service validates OIDC tokens and owns its database and credentials. Keycloak supplies identity; there is no custom authentication service. The payment simulator is a separate, database-backed provider reached over HTTP, not an in-process substitute.

Checkout reads authoritative catalog pricing, then commits the order and an `order.created` outbox entry. Inventory reserves stock atomically and emits its decision. Payment consumes successful reservations, durably schedules a provider request, and publishes the outcome. Order confirms the purchase or records failure; inventory consumes or releases the hold. One Kafka key per order carries correlation and causation through the workflow.

- **Catalog:** tenant-scoped products, merchant writes, strong ETags, bounded cursor pagination and substring search.
- **Inventory:** conditional PostgreSQL stock updates, durable reservations, overselling prevention, duplicate-safe settlement with causal checks.
- **Orders:** customer and merchant queries, durable lifecycle history, safe cancellation before dispatch, authoritative price snapshots and semantic idempotency.
- **Payments:** provider-neutral port, fenced work leases, bounded retries and explicit uncertain outcomes.
- **Runtime library:** HTTP security/correlation and transactional event transport only; no shared business entities or cross-service persistence.

Decisions: [reliable checkout](docs/adr/001-reliable-checkout.md), [tenant and trust boundaries](docs/adr/002-tenant-and-trust-boundaries.md), [order lifecycle and queries](docs/adr/003-order-lifecycle-and-queries.md).

## Run locally

Requires Docker Engine with Compose v2 and Python 3. A host Java installation is unnecessary for the container build. Allow roughly 6 GB of Docker memory for the full stack.

1. Run `python3 scripts/bootstrap-local.py` to generate local credentials and a Keycloak realm import. Nothing secret is printed or committed.
2. Run `docker compose up --build -d --wait`.
3. Run `python3 scripts/compose-smoke.py --no-build` to exercise the real HTTP/event workflow. This consumes and adjusts **local fixture stock**.
4. Use `docker compose down` to stop services while preserving data. Bootstrap does not rotate credentials or overwrite database volumes.

Stateful services apply their own versioned Flyway migrations. Existing v0.1.0 volumes require the explicit, data-preserving [upgrade procedure](docs/database-migrations.md) before starting new images.

Only the gateway (`http://localhost:8080`) and local Keycloak (`http://localhost:8180`) bind host ports, both on loopback. Databases, Kafka, provider and business-service ports stay inside the Compose network. Do not expose this local-development stack to the internet.

The imported realm has two tenants, two customers in tenant A, one customer in tenant B, and a tenant-A merchant. The smoke script obtains fixture tokens internally. The local `odexa-cli` password grant is restricted to development; production and future browser clients must use authorization code + PKCE. Sample credentials live only in ignored `.env` and `.local/` files. Seed products/inventory load only under the `local` profile.

## API and events

Maintained [OpenAPI contracts](contracts/openapi) cover every implemented HTTP boundary. [AsyncAPI](contracts/asyncapi/commerce.json) defines stable integration events.

- `/api/v1`, opaque UUIDs, UTC timestamps and integer minor-unit USD amounts; no client-supplied checkout price.
- `X-Correlation-ID` is a canonical UUID, generated when absent and echoed on public responses.
- RFC 9457 errors carry stable `code` values; no rejected secrets, stack traces or SQL details.
- Order submission requires `Idempotency-Key`, scoped to tenant/customer. Exact retries return the same resource; a different semantic request returns `409`.
- Product/inventory writes require a strong `If-Match`; stale versions return `412`, missing preconditions `428`.
- Cross-tenant and other-customer resource reads return `404`; authenticated role violations return `403`.
- Customers list their own orders at `GET /api/v1/orders`; merchants list/detail tenant orders at `/api/v1/merchant/orders`. Both expose `/{id}/history`. Collections use bounded cursors with optional status and creation-time filters.
- `POST /api/v1/orders/{id}/cancel` synchronously cancels only before checkout dispatch has ever started. Repeats return the cancelled order; unsafe cancellation returns `409 ORDER_NOT_CANCELLABLE`. Never-dispatched stale orders expire after 30 minutes by default.
- The simulator accepts `pm_approved` and `pm_declined` sandbox references. It cannot move real money.

## Build and verify

With Java 25, run `./gradlew check bootJar`. Dependencies are locked; wrapper distribution is checksum-verified. Compilation enables deprecation/unchecked warnings as errors. Product tests use JUnit, Spring test support and Testcontainers, not external portfolio QA suites.

- `python3 scripts/ci.py check`: Java tests, Python tooling tests, repository boundary checks and structural contract checks.
- `python3 scripts/ci.py integration`: PostgreSQL/Kafka/component tests; requires Docker and fails if infrastructure tests are skipped.
- `python3 scripts/compose-smoke.py`: container builds, health checks, authenticated single/multi-item checkout, isolation, whole-basket stock rejection, canonical idempotency, decline/release, full refunds, provider reconciliation and concurrent reservations.

Direct `./gradlew integrationTest` skips Testcontainers when Docker is absent; those skips **are not successful infrastructure verification**. CI uses the stricter wrapper. Contract checks currently verify local references and supported response-schema assertions, not official OpenAPI/AsyncAPI meta-schema conformance.

## Repository

- `services/`: six independently packaged Spring Boot deployables.
- `libraries/runtime/`: narrowly scoped security and event-delivery mechanics.
- `infrastructure/`: local Keycloak import template and isolated PostgreSQL provisioning.
- `contracts/`: HTTP and integration-event specifications.
- `scripts/`: bootstrap, smoke and CI checks.
- `docs/adr/`: durable architecture decisions.

## Current limits

This is a working initial slice, **not production-ready commerce**. Checkout supports 1–20 distinct products, 1–100 units per line, USD only. Duplicate products are rejected; stock is reserved for the entire basket or none. Merchant-created products need inventory provisioning; free checkout is rejected before persistence. Partial refunds, cancellation after dispatch, reservation expiry and fulfilment are not implemented. Cancellation before dispatch has a deliberately small window. Expiry applies only to never-dispatched checkout; dispatched CREATED orders await inventory delivery/operator recovery. Unknown payments retain stock during automatic provider reconciliation; unresolved cases remain REVIEW_REQUIRED, with no support UI yet.

Future work includes outbox/inbox retention, authenticated Kafka transport/ACLs, production OIDC provisioning, telemetry export and full standards-based contract validation. Versioned migrations support fresh databases and explicit v0.1.0 upgrades; old orders start their public history with a labelled migration snapshot.

No frontend, mobile app, external QA repositories, Redis cache, RabbitMQ jobs, object storage or cloud deployment is scaffolded without a workflow that needs it.

## Payment providers and full refunds

Payments support the default independent HTTP simulator and an explicitly enabled Stripe
Test Mode adapter. Signed webhooks durably schedule provider reconciliation; unknown outcomes
retain reservations. Same-tenant merchants can request idempotent full financial refunds, and
customers can read their own refund resources. Refunds do not restore inventory or rewrite
a confirmed checkout outcome. See the [payment guide](services/payment/README.md),
[simulator scenarios](services/payment-simulator/README.md), and [ADR 004](docs/adr/004-payment-providers-and-financial-refunds.md).

Run local bootstrap again after upgrading to add the independent simulator signing secret
without rotating existing credentials. All existing databases are upgraded by append-only
Flyway migrations. Deploy all event consumers together because two new financial event types
are now recognized. Default development and CI need no Stripe keys or internet access to Stripe.

This remains Test Mode commerce: no live-money certification, frontend authentication flow,
partial refunds, fulfilment, returns, production identity provisioning,
Kafka TLS/ACLs, cloud deployment, support UI, or accounting ledger.

## Multi-item checkout

```json
{"items":[{"productId":"11111111-1111-4111-8111-111111111111","quantity":2},{"productId":"33333333-3333-4333-8333-333333333333","quantity":1}],"paymentMethod":"pm_approved"}
```

The local tenant-A fixtures include a $25 tote and $12 notebook. The example totals6200 USD
minor units. Send a customer bearer token and `Idempotency-Key` to `POST /api/v1/orders`.
Legacy `{productId, quantity, paymentMethod}` requests still work. Both normalize to canonical
product order; retrying with reordered identical items returns the original order and prices.
Do not mix request forms or submit prices. Responses always contain immutable `items`; legacy
`productId`/`quantity` appear only on one-line orders. Collection and merchant views use the same
representation. Baskets remain one reservation, one payment and one full financial refund.

Catalog resolves all products under the forwarded customer's tenant in one bounded private
batch call. Any unavailable line prevents acceptance or the entire stock reservation. Free
lines can accompany a paid line, but a zero-total basket fails422. There is no saved cart,
partial acceptance, item refund, shipping, tax or discount model.

See [basket design and compatibility](docs/adr/005-multi-item-commerce.md) and
[upgrade guidance](docs/database-migrations.md). Verify fresh volumes with
`python3 scripts/compose-smoke.py`, then rerun the same command for a rebuild with retained data.
`--no-build` runs against already built images. Neither command removes existing volumes.

The smoke client renews its known local fixture tokens before their advertised expiry so long
recovery scenarios remain authenticated, including after local VM suspension or clock changes.
Unexpected 401 responses still fail verification; it
does not extend server token lifetimes or retry authorization failures.
