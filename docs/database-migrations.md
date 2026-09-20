# Database migrations

Catalog, inventory, order, payment and payment-simulator each run Flyway against their own
database at startup. Keycloak retains its own schema management. Flyway is supplied by Spring
Boot's managed starter and PostgreSQL module; dependency locks pin the resolved versions.

Fresh databases need no extra command: bootstrap credentials and run Compose as documented
in the root README. Local product/stock seeds still run only in the local profile, after
migration, and do not overwrite existing rows. SQL bootstrap DDL is no longer run on startup.

Versioned migrations under each service's `src/main/resources/db/migration` are append-only.
Never edit an applied migration or repair a checksum to hide drift. Add a new migration instead.
Flyway serializes migrations and validates checksums on restart. Cleaning is disabled.

## Upgrade from v0.1.0

This release requires a maintenance window; mixed old/new publishers are unsupported.
Stop the gateway and all five business/provider services, preserving PostgreSQL and Kafka
volumes. Back up each service database and verify restoration before proceeding.

Compare each existing schema against that service's frozen V1 migration, including columns,
constraints and indexes. Confirm the database/user mapping in Compose. V1 represents the
released v0.1.0 schema, including local inbox/outbox tables where applicable. If the schema
differs, diagnose it before baselining; do not guess a version or delete data to make startup pass.

An existing nonempty schema without Flyway history deliberately fails ordinary startup.
After verifying it is v0.1.0, perform the **one-time explicit baseline** by starting each service
with `SPRING_FLYWAY_BASELINE_ON_MIGRATE=true` and `SPRING_FLYWAY_BASELINE_VERSION=1`.
For local Compose, run the following for each service (`orders` is the database name;
`order` is the service name):

```sh
docker compose run --rm --no-deps \
  -e SPRING_FLYWAY_BASELINE_ON_MIGRATE=true \
  -e SPRING_FLYWAY_BASELINE_VERSION=1 \
  -e SPRING_KAFKA_LISTENER_AUTO_STARTUP=false \
  -e RUNTIME_OUTBOX_ENABLED=false \
  -e PAYMENT_WORKER_ENABLED=false \
  -e ORDER_LIFECYCLE_WORKER_ENABLED=false \
  order
```

The service starts after migration; stop that one-off container normally after its successful
startup. Repeat with catalog, inventory, payment and payment-simulator using their own images
and configured credentials. Infrastructure dependencies must be running. Restart the normal
stack with the new images, without the baseline override. Never retain that override in `.env`
or deployment configuration. No credential values need to be printed or placed on the command
line. Alternatively use the Flyway `baseline` command at version 1 with securely supplied
service-specific connection settings, then start the normal application to migrate.

The dispatch-fence migration conservatively marks every preexisting unpublished outbox event
as attempted; published rows are never consulted by the fence and are left untouched.
It does not delete pending messages or change their payloads. The order history migration
preserves legacy state and adds a `LEGACY_SNAPSHOT` at migration time, rather than inventing
missing historical transition times. Existing orders and idempotency keys remain readable.

Migrations add indexes and backfill existing rows in transactions. On large databases this
requires sizing the maintenance window for table locks and backfill work; this release does
not claim zero-downtime upgrades. A failed PostgreSQL migration rolls back its transaction.
Restore a backup and the old application together if rollback is needed; do not run old
publishers against an active new lifecycle deployment.

## Verification

Each stateful service has Testcontainers checks for fresh migration, explicit v0.1.0 baseline
and upgrade with retained business data, checksum validation and restart without reapplication.
Transport-bearing services also preserve inbox entries and conservatively fence legacy outbox
entries. Tests keep frozen v0.1.0 DDL under test resources; production loads only migrations.

References: [Spring Boot database initialization](https://docs.spring.io/spring-boot/how-to/data-initialization.html)
and [Flyway baseline safety](https://documentation.red-gate.com/fd/flyway-baseline-on-migrate-setting-277578974.html).

Payment V3 preserves the simulator provider on all existing rows and adds refund work and
provider notification inboxes. Simulator V2 adds fault scenarios, refunds and durable callback
delivery. Order V4 widens opaque payment references without changing existing order states.
All migrations released in v0.2.0 remain unchanged. Before upgrading, rerun local bootstrap
to append the independent simulator signing credential; existing credentials are preserved.
