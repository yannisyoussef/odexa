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
import threading
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlsplit
from urllib.request import Request, build_opener, HTTPRedirectHandler, ProxyHandler
from uuid import uuid4, UUID

from contract_check import ContractError, validate_response

ROOT = Path(__file__).resolve().parents[1]
PRODUCT = "11111111-1111-4111-8111-111111111111"
TERMINAL = {"CONFIRMED", "STOCK_REJECTED", "PAYMENT_FAILED", "CANCELLED", "EXPIRED"}


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
        self._token_lock = threading.RLock()
        self._token_users = {}
        self._sessions = {}

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
        # A long recovery smoke can outlive Keycloak's short access-token lifetime.
        # Renew only known fixture identities before expiry; never retry an unexpected 401.
        with self._token_lock:
            started = time.monotonic()
            data = urlencode({"client_id": "odexa-cli", "grant_type": "password", "username": username, "password": self.password}).encode()
            response = self.transport(self.issuer + "/protocol/openid-connect/token", "POST",
                                      {"Content-Type": "application/x-www-form-urlencoded"}, data)
            require(response.status == 200 and isinstance(response.body, dict)
                    and isinstance(response.body.get("access_token"), str) and bool(response.body["access_token"]),
                    "Local fixture authentication failed")
            lifetime = response.body.get("expires_in")
            require(type(lifetime) is int and 0 < lifetime <= 86400, "Invalid fixture token lifetime")
            token = response.body["access_token"]
            self._token_users[token] = username
            self._sessions[username] = (token, started + lifetime - min(30, lifetime / 10))
            return token

    def current_token(self, token):
        with self._token_lock:
            username = self._token_users.get(token)
            if username is None:
                return token
            current, renew_at = self._sessions[username]
            if time.monotonic() >= renew_at:
                return self.token(username)
            return current

    def call(self, service, method, path, token=None, body=None, headers=None, expected=(200,), contract=True):
        correlation = str(uuid4())
        request_headers = {"Accept": "application/json", "X-Correlation-ID": correlation}
        if token:
            request_headers["Authorization"] = "Bearer " + self.current_token(token)
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
    values = environment()
    client = Client(values)
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
    own = client.call("order", "GET", "/api/v1/orders?limit=2", customer).body
    require(len(own["items"]) == 2 and own["nextCursor"], "Order collection did not produce a bounded continuation")
    next_page = client.call("order", "GET", "/api/v1/orders?limit=2&cursor=" + own["nextCursor"], customer).body
    require(next_page["items"] and not ({item["id"] for item in own["items"]} & {item["id"] for item in next_page["items"]}),
            "Order continuation was empty or repeated an item")
    confirmed_only = client.call("order", "GET", "/api/v1/orders?status=CONFIRMED&limit=100", customer).body["items"]
    require(order_id in {item["id"] for item in confirmed_only} and all(item["status"] == "CONFIRMED" for item in confirmed_only),
            "Order status filter was ignored")
    merchant_page = client.call("order", "GET", "/api/v1/merchant/orders?limit=100", merchant).body
    require(order_id in {item["id"] for item in merchant_page["items"]}, "Merchant cannot see a tenant order")
    detail = client.call("order", "GET", f"/api/v1/merchant/orders/{order_id}", merchant).body
    require(detail == confirmed and not {"customerId", "tenantId", "paymentMethod"} & detail.keys(), "Merchant representation exposes identity or differs from public order")
    history = client.call("order", "GET", f"/api/v1/orders/{order_id}/history", customer).body
    require([entry["status"] for entry in history] == ["CREATED", "PENDING_PAYMENT", "CONFIRMED"], "Successful lifecycle history is incomplete")
    require(client.call("order", "GET", f"/api/v1/merchant/orders/{order_id}/history", merchant).body == history, "Merchant history differs")
    for username in ("customer-b", "customer-other-a"):
        page = client.call("order", "GET", "/api/v1/orders?limit=100", tokens[username]).body
        require(order_id not in {item["id"] for item in page["items"]}, "Customer collection leaked an order")
        client.call("order", "GET", f"/api/v1/orders/{order_id}/history", tokens[username], expected=(404,))
        client.call("order", "POST", f"/api/v1/orders/{order_id}/cancel", tokens[username], expected=(404,))
    client.call("order", "GET", "/api/v1/merchant/orders", customer, expected=(403,))
    client.call("order", "GET", "/api/v1/orders", merchant, expected=(403,))
    for query, code in (("limit=101", "INVALID_ORDER_LIMIT"), ("cursor=bad", "INVALID_ORDER_CURSOR"),
                        ("status=unknown", "INVALID_ORDER_STATUS"), ("customerId=other", "INVALID_ORDER_FILTER"),
                        ("createdFrom=bad", "INVALID_ORDER_TIMESTAMP")):
        failure = client.call("order", "GET", "/api/v1/orders?" + query, customer, expected=(400,))
        require(failure.body["code"] == code, "Query failure code changed")
    conflict = client.call("order", "POST", f"/api/v1/orders/{order_id}/cancel", customer, expected=(409,))
    require(conflict.body["code"] == "ORDER_NOT_CANCELLABLE", "Paid cancellation did not fail safely")
    for failed, reason in ((rejected, "INSUFFICIENT_STOCK"), (declined, "PAYMENT_DECLINED")):
        entries = client.call("order", "GET", f"/api/v1/orders/{failed.body['id']}/history", customer).body
        require(entries[-1]["reason"] == reason, "Failure history reason is missing")
    print("PASS owned and merchant queries, pagination, lifecycle history, privacy and coded validation")
    cancellable = client.call("order", "POST", "/api/v1/orders", customer, payload,
                              {"Idempotency-Key": str(uuid4())}, expected=(201,)).body["id"]
    cancellation = client.call("order", "POST", f"/api/v1/orders/{cancellable}/cancel", customer, expected=(200, 409))
    if cancellation.status == 200:
        retry = client.call("order", "POST", f"/api/v1/orders/{cancellable}/cancel", customer)
        require(retry.body == cancellation.body and retry.body["status"] == "CANCELLED", "Cancellation retry changed outcome")
        entries = client.call("order", "GET", f"/api/v1/orders/{cancellable}/history", customer).body
        require([entry["status"] for entry in entries] == ["CREATED", "CANCELLED"], "Cancellation history is incorrect")
    else:
        require(cancellation.body["code"] == "ORDER_NOT_CANCELLABLE", "Dispatch race returned an unexpected conflict")
        client.wait_order(cancellable, customer, "STOCK_REJECTED")
    client.wait_inventory(merchant, 0, 0)
    outcome = "retry-safe success" if cancellation.status == 200 else "safe dispatch conflict"
    print(f"PASS cancellation through gateway: {outcome}; stock unchanged")
    refund_path = f"/api/v1/merchant/payments/{order_id}/refunds"
    refund_body = {"amountMinor": confirmed["totalMinor"], "currency": "USD"}
    refund_key = {"Idempotency-Key": str(uuid4())}
    client.call("payment", "POST", refund_path, customer, refund_body, refund_key, expected=(403,))
    refund = client.call("payment", "POST", refund_path, merchant, refund_body, refund_key, expected=(201,)).body
    replay = client.call("payment", "POST", refund_path, merchant, refund_body, refund_key).body
    require(replay["id"] == refund["id"], "Refund retry changed identity")
    client.call("payment", "POST", refund_path, merchant, {**refund_body, "amountMinor": refund_body["amountMinor"] + 1}, refund_key, expected=(409,))
    def wait_refund(order, identity):
        deadline = time.monotonic() + client.timeout
        while time.monotonic() < deadline:
            current = client.call("payment", "GET", f"/api/v1/payments/{order}/refunds/{identity}", customer).body
            if current["status"] == "SUCCEEDED":
                return current
            require(current["status"] != "FAILED", "Refund unexpectedly failed")
            time.sleep(0.5)
        raise SmokeFailure("Refund did not converge before deadline")
    wait_refund(order_id, refund["id"])
    client.call("payment", "GET", f"/api/v1/payments/{order_id}/refunds/{refund['id']}", tokens["customer-other-a"], expected=(404,))
    client.wait_order(order_id, customer, "CONFIRMED")
    client.wait_inventory(merchant, 0, 0)
    print("PASS merchant full refund, replay/conflict, privacy and financial-only inventory semantics")
    import hashlib
    import hmac
    signed_body = {"id": "evt_" + uuid4().hex, "type": "unknown.future_type", "livemode": False}
    raw = json.dumps(signed_body).encode()
    timestamp = str(int(time.time()))
    signature = "t=" + timestamp + ",v1=" + hmac.new(values["SIMULATOR_WEBHOOK_SECRET"].encode(), timestamp.encode() + b"." + raw, hashlib.sha256).hexdigest()
    for _ in range(2):
        client.call("payment", "POST", "/api/v1/webhooks/simulator", body=signed_body,
                    headers={"Simulator-Signature": signature}, expected=(204,))
    client.call("payment", "POST", "/api/v1/webhooks/simulator", body={**signed_body, "type": "modified"},
                headers={"Simulator-Signature": signature}, expected=(400,))
    print("PASS signed raw webhook through gateway, duplicate acceptance and tamper rejection")
    current = client.inventory(merchant)
    client.call("inventory", "PUT", f"/api/v1/inventory/{PRODUCT}", merchant, {"onHand": 2}, {"If-Match": current.headers.get("ETag")})
    for method, expected in (("pm_lost_response", "CONFIRMED"), ("pm_reconcile_declined", "PAYMENT_FAILED"), ("pm_refund_lost", "CONFIRMED")):
        created = client.call("order", "POST", "/api/v1/orders", customer, {**payload, "paymentMethod": method},
                              {"Idempotency-Key": str(uuid4())}, expected=(201,)).body
        resolved = client.wait_order(created["id"], customer, expected)
        if method == "pm_refund_lost":
            pending = client.call("payment", "POST", f"/api/v1/merchant/payments/{created['id']}/refunds", merchant,
                                  {"amountMinor": resolved["totalMinor"], "currency": "USD"},
                                  {"Idempotency-Key": str(uuid4())}, expected=(201,)).body
            wait_refund(created["id"], pending["id"])
    client.wait_inventory(merchant, 0, 0)
    print("PASS lost payment/refund responses reconcile; authoritative decline releases stock")
    # Genuine baskets use two same-tenant fixtures; seed inserts are idempotent across rebuilds.
    second_product = "33333333-3333-4333-8333-333333333333"
    second_price = client.call("catalog", "GET", f"/api/v1/products/{second_product}", customer).body["unitPriceMinor"]
    for identity in (PRODUCT, second_product):
        state = client.call("inventory", "GET", f"/api/v1/inventory/{identity}", merchant)
        require(state.body["reserved"] == 0, "Basket fixture has unfinished work")
        client.call("inventory", "PUT", f"/api/v1/inventory/{identity}", merchant, {"onHand": 6}, {"If-Match": state.headers.get("ETag")})
    def wait_basket_stock(first, second):
        deadline = time.monotonic() + client.timeout
        while time.monotonic() < deadline:
            states = [client.call("inventory", "GET", f"/api/v1/inventory/{identity}", merchant).body
                      for identity in (PRODUCT, second_product)]
            if [state["onHand"] for state in states] == [first, second] and all(state["reserved"] == 0 for state in states):
                return states
            time.sleep(0.5)
        raise SmokeFailure("Basket inventory failed to converge")
    basket = {"items": [{"productId": PRODUCT, "quantity": 2}, {"productId": second_product, "quantity": 1}], "paymentMethod": "pm_approved"}
    basket_key = {"Idempotency-Key": str(uuid4())}
    accepted = client.call("order", "POST", "/api/v1/orders", customer, basket, basket_key, expected=(201,)).body
    total = product["unitPriceMinor"] * 2 + second_price
    require(accepted["totalMinor"] == total and len(accepted["items"]) == 2 and "productId" not in accepted,
            "Basket snapshot or legacy field omission is incorrect")
    replay = client.call("order", "POST", "/api/v1/orders", customer, {**basket, "items": list(reversed(basket["items"]))}, basket_key).body
    require(replay["id"] == accepted["id"], "Reordered basket did not replay")
    client.wait_order(accepted["id"], customer, "CONFIRMED")
    wait_basket_stock(4, 5)
    payment = client.call("payment", "GET", f"/api/v1/payments/{accepted['id']}", customer).body
    require(payment["amountMinor"] == total, "Payment did not use full basket total")
    client.call("order", "POST", "/api/v1/orders", customer,
                {**basket, "items": [basket["items"][0], {"productId": "22222222-2222-4222-8222-222222222222", "quantity": 1}]},
                {"Idempotency-Key": str(uuid4())}, expected=(404,))
    before = wait_basket_stock(4, 5)
    bad = {**basket, "items": [{"productId": PRODUCT, "quantity": 1}, {"productId": second_product, "quantity": 100}]}
    rejected_basket = client.call("order", "POST", "/api/v1/orders", customer, bad, {"Idempotency-Key": str(uuid4())}, expected=(201,)).body
    client.wait_order(rejected_basket["id"], customer, "STOCK_REJECTED")
    require(wait_basket_stock(4, 5) == before, "Rejected basket partially held stock or advanced versions")
    refund = client.call("payment", "POST", f"/api/v1/merchant/payments/{accepted['id']}/refunds", merchant,
                         {"amountMinor": total, "currency": "USD"}, {"Idempotency-Key": str(uuid4())}, expected=(201,)).body
    wait_refund(accepted["id"], refund["id"])
    require(wait_basket_stock(4, 5) == before, "Financial basket refund changed physical stock")
    for method, expected, quantities in (("pm_declined", "PAYMENT_FAILED", (4,5)),
                                         ("pm_lost_response", "CONFIRMED", (2,4)),
                                         ("pm_reconcile_declined", "PAYMENT_FAILED", (2,4))):
        pending = client.call("order", "POST", "/api/v1/orders", customer, {**basket, "paymentMethod": method},
                              {"Idempotency-Key": str(uuid4())}, expected=(201,)).body
        client.wait_order(pending["id"], customer, expected)
        wait_basket_stock(*quantities)
    print("PASS multi-item pricing, reordered replay, all-or-nothing stock, tenant isolation, full refund and reconciliation")
    client.call("gateway", "GET", "/actuator/env", expected=(404,), contract=False)
    print("PASS edge denies administrative actuator routes")
    print("PASS local smoke and REST response-shape subset checks; not official schema validation. Local fixture stock was consumed.")


if __name__ == "__main__":
    sys.stdout.reconfigure(line_buffering=True)
    try:
        run()
    except (SmokeFailure, ContractError) as error:
        # These exceptions carry only internal, deliberately non-sensitive descriptions.
        print(f"FAIL: {error}", file=sys.stderr)
        sys.exit(1)
    except Exception:
        print("FAIL: unexpected smoke/contract failure (details suppressed to protect credentials)", file=sys.stderr)
        sys.exit(1)
