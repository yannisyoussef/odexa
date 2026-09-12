#!/usr/bin/env bash
# Invoked only on an empty PostgreSQL volume. Passwords stay in the environment/psql session, never argv.
set -euo pipefail
psql --username "$POSTGRES_USER" --dbname postgres --set ON_ERROR_STOP=1 <<'SQL'
\getenv catalog_password CATALOG_DB_PASSWORD
\getenv inventory_password INVENTORY_DB_PASSWORD
\getenv order_password ORDER_DB_PASSWORD
\getenv payment_password PAYMENT_DB_PASSWORD
\getenv simulator_password SIMULATOR_DB_PASSWORD
\getenv keycloak_password KEYCLOAK_DB_PASSWORD
SELECT format('CREATE ROLE catalog LOGIN PASSWORD %L', :'catalog_password') \gexec
SELECT format('CREATE ROLE inventory LOGIN PASSWORD %L', :'inventory_password') \gexec
SELECT format('CREATE ROLE orders LOGIN PASSWORD %L', :'order_password') \gexec
SELECT format('CREATE ROLE payment LOGIN PASSWORD %L', :'payment_password') \gexec
SELECT format('CREATE ROLE simulator LOGIN PASSWORD %L', :'simulator_password') \gexec
SELECT format('CREATE ROLE keycloak LOGIN PASSWORD %L', :'keycloak_password') \gexec
CREATE DATABASE catalog OWNER catalog;
CREATE DATABASE inventory OWNER inventory;
CREATE DATABASE orders OWNER orders;
CREATE DATABASE payment OWNER payment;
CREATE DATABASE simulator OWNER simulator;
CREATE DATABASE keycloak OWNER keycloak;
REVOKE ALL ON DATABASE catalog FROM PUBLIC;
REVOKE ALL ON DATABASE inventory FROM PUBLIC;
REVOKE ALL ON DATABASE orders FROM PUBLIC;
REVOKE ALL ON DATABASE payment FROM PUBLIC;
REVOKE ALL ON DATABASE simulator FROM PUBLIC;
REVOKE ALL ON DATABASE keycloak FROM PUBLIC;
REVOKE ALL ON DATABASE postgres FROM PUBLIC;
REVOKE ALL ON DATABASE template1 FROM PUBLIC;
SQL
