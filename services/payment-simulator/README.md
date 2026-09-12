# Payment simulator

Independent internal HTTP provider, default port **8085**, with its **own database**. It does not import `libraries/runtime` or use end-user bearer authentication.

- `POST /provider/v1/payments`: required `X-Provider-Key`, `Idempotency-Key` equal to the body order UUID, and JSON `{orderId, amountMinor, currency, paymentMethod}`. Positive integer minor-unit amounts and USD only.
- `pm_approved` deterministically returns `AUTHORIZED`; `pm_declined` returns `DECLINED`. These are documented sandbox references, not live card data or artificial test-control endpoints.
- New results return 201 with Location; exact semantic retries return 200 with the same `{id,status}`. Database uniqueness arbitrates concurrent requests. A changed valid payload for an existing order key returns 409, without overwriting the original result. The entire payload and outcome are stored atomically, so idempotency survives replicas/restarts.
- `GET /provider/v1/payments/{id}` also requires the provider key. Unknown IDs return 404.
- Missing, repeated or incorrect provider keys return 401. The required configured secret is hashed and compared using constant-time fixed-length digest comparison; no keys, payment request bodies, or underlying exceptions are logged. Health GETs are the only unauthenticated operational endpoint. Other non-provider paths are denied.
- No callback URLs, webhook routes, arbitrary outbound destinations, or testing/control endpoints. Unknown request properties are rejected. **Stripe and asynchronous provider webhook integration are deferred** per the initial brief. There is no live-money capability.

## Configuration and validation

Required environment: `DB_PASSWORD`, `PROVIDER_API_KEY` (same secret as payment). Optional: `DB_URL` (default `jdbc:postgresql://localhost:5432/simulator`), `DB_USER` (default `simulator`), `SERVER_PORT` (default 8085). Never reuse the payment service's database. SQL init applies this service's own initial immutable `schema.sql`; runtime inbox/outbox schema is not loaded. Keep this provider off the public gateway and use trusted networking/TLS outside local development.

From the repository root with Java 25, run `./gradlew :services:payment-simulator:test :services:payment-simulator:integrationTest :services:payment-simulator:bootJar`. Unit tests cover deterministic decisions and secret validation. Integration-tagged Testcontainers tests use real HTTP plus PostgreSQL 17.6 for concurrent idempotency, collisions, auth, malformed input and absent callback/testing routes. They skip without Docker; CI must perform a Docker preflight. No claim of successful execution should be inferred until those commands are actually run.
