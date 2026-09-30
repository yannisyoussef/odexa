---
title: Local quickstart
description: Start the complete local stack and make an authenticated request through the edge.
---

Generate local-only credentials, build the services, and start the stack using
the repository commands:

```bash
python3 scripts/bootstrap-local.py
./gradlew clean check bootJar
docker compose up --build -d
python3 scripts/smoke.py
```

The gateway listens on `http://localhost:8080`; Keycloak listens on
`http://localhost:8180`. The smoke workflow obtains fixture identities and
proves authenticated catalog, inventory, single and multi-item checkout,
tenant isolation, idempotency, decline release, full refund, and reconciliation
paths.

Do not copy generated `.env` values into documentation, commits, logs, or bug
reports. Re-run the bootstrap script when the local credentials should rotate.
