#!/usr/bin/env python3
"""Local fixture verification using only Python stdlib; never print tokens, passwords or response bodies.

Run against Compose with `python3 scripts/compose-smoke.py`, or native processes
using SMOKE_GATEWAY_URL and optional SMOKE_{CATALOG,INVENTORY,ORDER,PAYMENT}_URL.
SMOKE_ISSUER_URL defaults to .env OIDC_ISSUER; SMOKE_SIMULATOR_URL defaults to
http://localhost:8085. Direct password grants are restricted to local fixture hosts.
This script mutates the local seeded tenant-A inventory through its real merchant API.
"""
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
import importlib.util
import json
import os
from pathlib import Path
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlsplit
from urllib.request import Request, build_opener, HTTPRedirectHandler, ProxyHandler
from uuid import uuid4, UUID

from contract_check import ContractError, validate_response

ROOT = Path(__file__).resolve().parents[1]
PRODUCT = "11111111-1111-4111-8111-111111111111"
TERMINAL = {"CONFIRMED", "STOCK_REJECTED", "PAYMENT_FAILED"}


class SmokeFailure(Exception):
    pass


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


@dataclass(repr=False)
class Response:
    status: int
    headers: object
    body: object


def require(condition, message):
    if not condition:
        raise SmokeFailure(message)


def environment():
    spec = importlib.util.spec_from_file_location("bootstrap_local", ROOT / "scripts/bootstrap-local.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    path = Path(os.environ.get("ODEXA_ENV_FILE", ROOT / ".env"))
    values = module.read_env(path)
    require(bool(values.get("LOCAL_FIXTURE_PASSWORD")) and bool(values.get("PROVIDER_API_KEY")), "Required local fixture credentials are missing")
    return values


def base_url(value, allowed_hosts=None):
    uri = urlsplit(value)
    require(uri.scheme in {"http", "https"} and uri.hostname and not uri.username and not uri.password and not uri.query and not uri.fragment,
            "Invalid smoke service base URL")
    if allowed_hosts is not None:
        require(uri.hostname in allowed_hosts, "Local fixture credentials may only be sent to local service hosts")
    return value.rstrip("/")


class Client:
    def __init__(self, values):
        local_hosts = {"localhost", "127.0.0.1", "::1", "gateway", "catalog", "inventory", "order", "payment", "payment-simulator", "keycloak"}
        gateway = base_url(os.environ.get("SMOKE_GATEWAY_URL", "http://localhost:8080"), local_hosts)
        self.bases = {service: base_url(os.environ.get(f"SMOKE_{service.upper()}_URL", gateway), local_hosts)
                      for service in ("catalog", "inventory", "order", "payment")}
        self.bases["gateway"] = gateway
        self.bases["payment-simulator"] = base_url(os.environ.get("SMOKE_SIMULATOR_URL", "http://localhost:8085"), local_hosts)
        self.issuer = base_url(os.environ.get("SMOKE_ISSUER_URL", values.get("OIDC_ISSUER", "http://localhost:8180/realms/odexa")), local_hosts)
        self.password = values["LOCAL_FIXTURE_PASSWORD"]
        self.provider_key = values["PROVIDER_API_KEY"]
        self.timeout = float(os.environ.get("SMOKE_TIMEOUT_SECONDS", "120"))

    @staticmethod
    def transport(url, method="GET", headers=None, data=None):
        # Ignore ambient HTTP proxy settings and never redirect credentials to a different origin.
        opener = build_opener(ProxyHandler({}), NoRedirect())
        try:
            try:
                response = opener.open(Request(url, data=data, headers=headers or {}, method=method), timeout=15)
            except HTTPError as error:
                response = error
            with response:
                raw = response.read(2_097_153)
                require(len(raw) <= 2_097_152, "HTTP response exceeded smoke bound")
                body = json.loads(raw) if raw else None
                return Response(response.status, response.headers, body)
        except (URLError, TimeoutError, OSError, ValueError):
            raise SmokeFailure("HTTP transport or JSON decoding failed (details suppressed)") from None

    def token(self, username):
        data = urlencode({"client_id": "odexa-cli", "grant_type": "password", "username": username, "password": self.password}).encode()
        response = self.transport(self.issuer + "/protocol/openid-connect/token", "POST",
                                  {"Content-Type": "application/x-www-form-urlencoded"}, data)
        require(response.status == 200 and isinstance(response.body, dict) and isinstance(response.body.get("access_token"), str), "Local fixture authentication failed")
        return response.body["access_token"]

    def call(self, service, method, path, token=None, body=None, headers=None, expected=(200,), contract=True):
        correlation = str(uuid4())
        request_headers = {"Accept": "application/json", "X-Correlation-ID": correlation}
        if token:
            request_headers["Authorization"] = "Bearer " + token
        if headers:
            request_headers.update(headers)
        data = None
        if body is not None:
            data = json.dumps(body).encode()
            request_headers["Content-Type"] = "application/json"
        response = self.transport(self.bases[service] + path, method, request_headers, data)
        require(response.status in expected, f"Unexpected HTTP status for {service} {method} ({response.status})")
        if service != "payment-simulator":
            require(response.headers.get("X-Correlation-ID") == correlation, "Correlation was not preserved")
        if contract:
            validate_response(service, method, path.split("?", 1)[0], response.status, response.headers, response.body)
        if response.status >= 400:
            require(isinstance(response.body, dict) and response.body.get("status") == response.status
                    and isinstance(response.body.get("code"), str), "Error is not a coded Problem Detail")
        return response

    def inventory(self, token):
        response = self.call("inventory", "GET", f"/api/v1/inventory/{PRODUCT}", token)
        body = response.body
        require(body["onHand"] >= 0 and body["reserved"] >= 0 and body["available"] >= 0
                and body["available"] == body["onHand"] - body["reserved"], "Inventory invariant violated")
        return response

    def wait_order(self, order_id, token, expected=None):
        deadline = time.monotonic() + self.timeout
        while time.monotonic() < deadline:
            response = self.call("order", "GET", f"/api/v1/orders/{order_id}", token)
            if response.body["status"] in TERMINAL:
                require(expected is None or response.body["status"] == expected, "Unexpected terminal order state")
                return response.body
            time.sleep(0.5)
        raise SmokeFailure("Order did not reach its terminal state before the deadline")

    def wait_inventory(self, token, on_hand, reserved):
        deadline = time.monotonic() + self.timeout
        while time.monotonic() < deadline:
            response = self.inventory(token)
            if response.body["onHand"] == on_hand and response.body["reserved"] == reserved:
                return response
            time.sleep(0.5)
        raise SmokeFailure("Inventory did not converge before the deadline")


def run():
    client = Client(environment())
    tokens = {username: client.token(username) for username in ("customer-a", "customer-b", "customer-other-a", "merchant-a")}
    customer, merchant = tokens["customer-a"], tokens["merchant-a"]
    client.call("catalog", "GET", "/api/v1/products", expected=(401,))
    print("PASS unauthenticated request rejected")
    page = client.call("catalog", "GET", "/api/v1/products?limit=1&q=Odexa", customer).body
    require(len(page["items"]) <= 1, "Catalog ignored its pagination bound")
    product = client.call("catalog", "GET", f"/api/v1/products/{PRODUCT}", customer).body
    UUID(product["id"])
    require(product["active"] and product["unitPriceMinor"] > 0 and product["currency"] == "USD", "Local product is not orderable")
    client.call("catalog", "GET", f"/api/v1/products/{PRODUCT}", tokens["customer-b"], expected=(404,))
    before = client.inventory(merchant)
    require(before.body["reserved"] == 0, "Local inventory has in-flight reservations; use an idle fixture")
    etag = before.headers.get("ETag")
    require(bool(etag), "Inventory GET omitted ETag")
    client.call("inventory", "PUT", f"/api/v1/inventory/{PRODUCT}", customer, {"onHand": 6}, {"If-Match": etag}, expected=(403,))
    print("PASS role and tenant isolation")
    client.call("inventory", "PUT", f"/api/v1/inventory/{PRODUCT}", merchant, {"onHand": 6}, {"If-Match": etag}, expected=(200,))
    client.call("inventory", "PUT", f"/api/v1/inventory/{PRODUCT}", merchant, {"onHand": 6}, {"If-Match": etag}, expected=(412,))
    print("PASS stale ETag rejected")
    payload = {"productId": PRODUCT, "quantity": 1, "paymentMethod": "pm_approved"}
    key = str(uuid4())
    first = client.call("order", "POST", "/api/v1/orders", customer, payload, {"Idempotency-Key": key}, expected=(201,))
    order_id = first.body["id"]
    require(first.body["totalMinor"] == product["unitPriceMinor"], "Order price is not authoritative")
    require(first.headers.get("Location", "").endswith("/api/v1/orders/" + order_id), "Created order omitted its Location")
    confirmed = client.wait_order(order_id, customer, "CONFIRMED")
    client.wait_inventory(merchant, 5, 0)
    client.call("payment", "GET", f"/api/v1/payments/{order_id}", customer)
    print("PASS authenticated catalog -> reserved stock -> payment -> confirmed order")
    replay = client.call("order", "POST", "/api/v1/orders", customer, payload, {"Idempotency-Key": key}, expected=(200,))
    require(replay.body["id"] == order_id and replay.headers.get("Location") == first.headers.get("Location"), "Idempotent order retry changed identity or Location")
    def retry_checkout(_):
        return client.call("order", "POST", "/api/v1/orders", customer, payload, {"Idempotency-Key": key}, expected=(200,)).body["id"]
    with ThreadPoolExecutor(max_workers=4) as executor:
        require(set(executor.map(retry_checkout, range(4))) == {order_id}, "Concurrent retries changed the order identity")
    client.call("order", "POST", "/api/v1/orders", customer, {**payload, "quantity": 2}, {"Idempotency-Key": key}, expected=(409,))
    for username in ("customer-b", "customer-other-a"):
        client.call("order", "GET", f"/api/v1/orders/{order_id}", tokens[username], expected=(404,))
        client.call("payment", "GET", f"/api/v1/payments/{order_id}", tokens[username], expected=(404,))
    print("PASS idempotent retry, conflicting key and resource ownership")
    rejected = client.call("order", "POST", "/api/v1/orders", customer, {**payload, "quantity": 100}, {"Idempotency-Key": str(uuid4())}, expected=(201,))
    client.wait_order(rejected.body["id"], customer, "STOCK_REJECTED")
    client.wait_inventory(merchant, 5, 0)
    print("PASS insufficient stock leaves inventory unchanged")
    declined = client.call("order", "POST", "/api/v1/orders", customer, {**payload, "paymentMethod": "pm_declined"}, {"Idempotency-Key": str(uuid4())}, expected=(201,))
    client.wait_order(declined.body["id"], customer, "PAYMENT_FAILED")
    client.wait_inventory(merchant, 5, 0)
    print("PASS payment decline releases reservation without reducing on-hand stock")
    provider_body = {"orderId": order_id, "amountMinor": confirmed["totalMinor"], "currency": "USD", "paymentMethod": "pm_approved"}
    provider_headers = {"Idempotency-Key": order_id, "X-Provider-Key": client.provider_key}
    provider_first = client.call("payment-simulator", "POST", "/provider/v1/payments", body=provider_body, headers=provider_headers, expected=(200, 201))
    provider_retry = client.call("payment-simulator", "POST", "/provider/v1/payments", body=provider_body, headers=provider_headers, expected=(200, 201))
    require(provider_first.body == provider_retry.body and provider_first.body["status"] == "AUTHORIZED", "Duplicate provider request changed authoritative outcome")
    client.call("payment-simulator", "POST", "/provider/v1/payments", body={**provider_body, "amountMinor": confirmed["totalMinor"] + 1}, headers=provider_headers, expected=(409,))
    client.wait_inventory(merchant, 5, 0)
    print("PASS duplicate provider processing is idempotent; conflicting provider payload rejected")
    def checkout(_):
        result = client.call("order", "POST", "/api/v1/orders", customer, payload, {"Idempotency-Key": str(uuid4())}, expected=(201,))
        return result.body["id"]
    with ThreadPoolExecutor(max_workers=8) as executor:
        order_ids = list(executor.map(checkout, range(8)))
    outcomes = [client.wait_order(identity, customer)["status"] for identity in order_ids]
    require(outcomes.count("CONFIRMED") == 5 and outcomes.count("STOCK_REJECTED") == 3, "Concurrent checkout oversold or lost available stock")
    client.wait_inventory(merchant, 0, 0)
    print("PASS concurrent reservations cannot oversell (5 units, 8 competing orders)")
    client.call("gateway", "GET", "/actuator/env", expected=(404,), contract=False)
    print("PASS edge denies administrative actuator routes")
    print("PASS local smoke and REST response-shape subset checks; not official schema validation. Local fixture stock was consumed.")


if __name__ == "__main__":
    try:
        run()
    except (SmokeFailure, ContractError) as error:
        # These exceptions carry only internal, deliberately non-sensitive descriptions.
        print(f"FAIL: {error}", file=sys.stderr)
        sys.exit(1)
    except Exception:
        print("FAIL: unexpected smoke/contract failure (details suppressed to protect credentials)", file=sys.stderr)
        sys.exit(1)
