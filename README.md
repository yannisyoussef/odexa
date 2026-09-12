# Odexa

Multi-tenant commerce and order management, starting with a reliable single-product checkout. **[odexa.cc](https://odexa.cc)** is the product domain; this repository does not deploy to it.

Java 25 · Spring Boot 4.1.1 · Gradle Kotlin DSL · PostgreSQL 17 · Kafka · Keycloak

## Architecture

The API gateway routes authenticated requests to catalog, inventory, order and payment services. Each business service validates OIDC tokens and owns its database and credentials. Keycloak supplies identity; there is no custom authentication service. The payment simulator is a separate, database-backed provider reached over HTTP, not an in-process substitute.

Checkout reads authoritative catalog pricing, then commits the order and an `order.created` outbox entry. Inventory reserves stock atomically and emits its decision. Payment consumes successful reservations, durably schedules a provider request, and publishes the outcome. Order confirms the purchase or records failure; inventory consumes or releases the hold. One Kafka key per order carries correlation and causation through the workflow.

- **Catalog:** tenant-scoped products, merchant writes, strong ETags, bounded cursor pagination and substring search.
- **Inventory:** conditional PostgreSQL stock updates, durable reservations, overselling prevention, duplicate-safe settlement with causal checks.
- **Orders:** customer ownership, authoritative price snapshots, semantic idempotency and an explicit state machine.
- **Payments:** provider-neutral port, fenced work leases, bounded retries and explicit uncertain outcomes.
- **Runtime library:** HTTP security/correlation and transactional event transport only; no shared business entities or cross-service persistence.

Decisions: [reliable checkout](docs/adr/001-reliable-checkout.md), [tenant and trust boundaries](docs/adr/002-tenant-and-trust-boundaries.md).

## Run locally

Requires Docker Engine with Compose v2 and Python 3. A host Java installation is unnecessary for the container build. Allow roughly 6 GB of Docker memory for the full stack.

1. Run `python3 scripts/bootstrap-local.py` to generate local credentials and a Keycloak realm import. Nothing secret is printed or committed.
2. Run `docker compose up --build -d --wait`.
3. Run `python3 scripts/compose-smoke.py --no-build` to exercise the real HTTP/event workflow. This consumes and adjusts **local fixture stock**.
4. Use `docker compose down` to stop services while preserving data. Bootstrap does not rotate credentials or overwrite database volumes.

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
- The simulator accepts `pm_approved` and `pm_declined` sandbox references. It cannot move real money.

## Build and verify

With Java 25, run `./gradlew check bootJar`. Dependencies are locked; wrapper distribution is checksum-verified. Compilation enables deprecation/unchecked warnings as errors. Product tests use JUnit, Spring test support and Testcontainers, not external portfolio QA suites.

- `python3 scripts/ci.py check`: Java tests, Python tooling tests, repository boundary checks and structural contract checks.
- `python3 scripts/ci.py integration`: PostgreSQL/Kafka/component tests; requires Docker and fails if infrastructure tests are skipped.
- `python3 scripts/compose-smoke.py`: container builds, health checks, authenticated checkout, isolation, stock rejection, idempotency, decline/release, provider replay and concurrent reservations.

Direct `./gradlew integrationTest` skips Testcontainers when Docker is absent; those skips **are not successful infrastructure verification**. CI uses the stricter wrapper. Contract checks currently verify local references and supported response-schema assertions, not official OpenAPI/AsyncAPI meta-schema conformance.

## Repository

- `services/`: six independently packaged Spring Boot deployables.
- `libraries/runtime/`: narrowly scoped security and event-delivery mechanics.
- `infrastructure/`: local Keycloak import template and isolated PostgreSQL provisioning.
- `contracts/`: HTTP and integration-event specifications.
- `scripts/`: bootstrap, smoke and CI checks.
- `docs/adr/`: durable architecture decisions.

## Current limits

This is a working initial slice, **not production-ready commerce**. Checkout is single-product/USD. Merchant-created products need inventory provisioning; free checkout is rejected before persistence. Refunds, cancellation, reservation expiry and fulfilment are not implemented. Unknown payments retain stock and eventually require manual review; there is no reconciliation UI/API yet.

Stripe Test Mode integration, signed webhooks and richer delayed/faulting provider scenarios remain next steps. So do versioned database migrations, outbox/inbox retention, authenticated Kafka transport/ACLs, production OIDC provisioning, telemetry export and full standards-based contract validation. Local schemas are initial bootstrap schemas, not an upgrade mechanism.

No frontend, mobile app, external QA repositories, Redis cache, RabbitMQ jobs, object storage or cloud deployment is scaffolded without a workflow that needs it.
