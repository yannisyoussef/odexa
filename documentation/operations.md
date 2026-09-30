---
title: Operations
description: Verify health, smoke workflows, logs, backups, and contract drift.
---

Use service liveness for process state and readiness for dependency state. The
gateway exposes health routes but does not replace per-service diagnostics.
Production ingress, TLS, identity provisioning, secret management, retention,
and alerting remain deployment-owner work.

CI validates real contract structure, service tests, infrastructure integration
tests, and a repeated compose smoke with preserved database volumes. The
developer portal adds a packed Specra build and quality gate against the same
contracts, preventing documentation copies from drifting.

Logs may include bounded correlation identifiers, route templates, status
classes, and timing. They must not include bearer tokens, cookies, provider
keys, payment methods, raw webhook bodies, or customer payloads.

Back up service databases and the configuration needed to restore their
ownership boundaries. Generated portal artifacts are reproducible from the
repository contracts and authored documentation.
