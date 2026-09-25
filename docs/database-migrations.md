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

## Multi-item upgrade from the payment-provider baseline

OrderV5 introduces immutable order_line rows from the existing accepted snapshots and drops
obsolete header line columns. InventoryV3 backfills reservation_line from every historical
reservation, then drops its scalar product/quantity columns. IDs, totals, state, timestamps,
versions, fingerprints, history, active stock holds, payment/refund rows and outbox payloads
remain intact. No catalog/network lookup is involved. Payment and simulator need no new schema.
Previously applied migration files remain byte-for-byte unchanged.

Use the same coordinated maintenance window: stop edge/business workers, keep databases and
Kafka volumes, rebuild all services together, then start the complete new set. No Flyway baseline
is needed when history already exists. New publishers emit order.created/inventory.reserved v2;
new consumers accept both v1 and v2, including unpublished old outboxes. Do not run old consumers
against new publishers. Rollback requires restoring the old database backup with old binaries;
dropping scalar columns prevents simply restarting old applications. This is not a rolling or
zero-downtime migration. The latest merged payment baseline was inspected on main; do not assume
a v0.3.0 tag exists when selecting release artifacts.

Tests install the actual released migration sequence to orderV4/inventoryV2, seed old records,
then apply new migrations. They verify one-line snapshots, stopped orders/history, old keys,
active holds, unchanged durable messages and successful v1 replay/settlement. Compose smoke
also runs on preserved baseline volumes and after a new-version rebuild.
