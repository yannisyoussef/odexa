import contextlib
import importlib.util
import io
import json
from pathlib import Path
import shutil
import stat
import sys
import tempfile
import unittest
from unittest.mock import patch
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
import contract_check
import ci
import smoke

spec = importlib.util.spec_from_file_location("bootstrap_local", ROOT / "scripts/bootstrap-local.py")
bootstrap = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bootstrap)

spec = importlib.util.spec_from_file_location("compose_smoke", ROOT / "scripts/compose-smoke.py")
compose_smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(compose_smoke)


class BootstrapTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        (self.root / ".gitignore").write_text(".env\n.local/\n")
        target = self.root / "infrastructure/keycloak"
        target.mkdir(parents=True)
        shutil.copy(ROOT / "infrastructure/keycloak/odexa-realm.template.json", target)

    def tearDown(self):
        self.temporary.cleanup()

    def test_secrets_are_isolated_unprinted_and_stable_across_bootstrap(self):
        captured = io.StringIO()
        with contextlib.redirect_stdout(captured):
            self.assertTrue(bootstrap.bootstrap(self.root))
            original = (self.root / ".env").read_bytes()
            self.assertFalse(bootstrap.bootstrap(self.root))
        self.assertEqual(b"", captured.getvalue().encode())
        self.assertEqual(original, (self.root / ".env").read_bytes())
        values = bootstrap.read_env(self.root / ".env")
        self.assertEqual(len(bootstrap.SECRET_KEYS), len({values[key] for key in bootstrap.SECRET_KEYS}))
        self.assertTrue(all(len(values[key]) >= 32 for key in bootstrap.SECRET_KEYS))
        self.assertEqual(0o600, stat.S_IMODE((self.root / ".env").stat().st_mode))
        self.assertEqual(0o700, stat.S_IMODE((self.root / ".local").stat().st_mode))
        realm = json.loads((self.root / ".local/keycloak/odexa-realm.json").read_text())
        self.assertEqual({"customer-a", "customer-b", "customer-other-a", "merchant-a"}, {user["username"] for user in realm["users"]})
        self.assertTrue(all(user["credentials"][0]["value"] == values["LOCAL_FIXTURE_PASSWORD"] for user in realm["users"]))
        self.assertIn("__LOCAL_FIXTURE_PASSWORD__", (self.root / "infrastructure/keycloak/odexa-realm.template.json").read_text())

    def test_refuses_incomplete_existing_credentials_without_overwriting(self):
        (self.root / ".env").write_text("POSTGRES_PASSWORD=fixture\n")
        with self.assertRaises(ValueError):
            bootstrap.bootstrap(self.root)
        self.assertEqual("POSTGRES_PASSWORD=fixture\n", (self.root / ".env").read_text())

    def test_refuses_unignored_secrets_and_symlinks(self):
        (self.root / ".gitignore").write_text("")
        with self.assertRaises(ValueError):
            bootstrap.bootstrap(self.root)
        self.assertFalse((self.root / ".env").exists())
        (self.root / ".gitignore").write_text(".env\n.local/\n")
        (self.root / ".env").symlink_to(self.root / "elsewhere")
        with self.assertRaises(ValueError):
            bootstrap.bootstrap(self.root)
        self.assertFalse((self.root / "elsewhere").exists())


class ContractTests(unittest.TestCase):
    def setUp(self):
        self.source = ROOT / "contracts/asyncapi/commerce.json"

    def test_asyncapi_three_structure_and_reference_membership(self):
        contract_check.check_contract(self.source)

    def test_schema_rejects_missing_fields_and_invalid_types(self):
        schema = {"type": "object", "required": ["amount"], "properties": {"amount": {"type": "integer", "minimum": 1}}, "additionalProperties": False}
        contract_check.validate({"amount": 4}, schema, self.source)
        for value in ({}, {"amount": True}, {"amount": -1}, {"amount": 1, "secret": "fixture"}):
            with self.assertRaises(contract_check.ContractError):
                contract_check.validate(value, schema, self.source)

    def test_references_cannot_escape_contracts_or_fetch_network(self):
        for ref in ("https://example.invalid/schema", "../../.env", "#/does-not-exist"):
            with self.assertRaises(contract_check.ContractError):
                contract_check.resolve(ref, self.source)

    def test_rejects_unsupported_assertions(self):
        with self.assertRaises(contract_check.ContractError):
            contract_check.validate({}, {"if": {}}, self.source)

    def test_basket_event_versions_and_checkout_compatibility(self):
        schema, target = contract_check.resolve("#/components/messages/OrderCreatedV2/payload", self.source)
        payload = {"eventId": str(uuid4()), "eventType": "order.created", "eventVersion": 2,
                   "occurredAt": "2026-01-01T00:00:00Z", "correlationId": str(uuid4()), "tenantId": str(uuid4()),
                   "payload": {"orderId": str(uuid4()), "customerId": "owner", "items": [{"productId": str(uuid4()), "quantity": 2}],
                               "totalMinor": 5000, "currency": "USD", "paymentMethod": "pm_approved"}}
        contract_check.validate(payload, schema, target)
        with self.assertRaises(contract_check.ContractError):
            contract_check.validate({**payload, "eventVersion": 1}, schema, target)
        source = contract_check.ROOT / "contracts/openapi/order.json"
        checkout = contract_check.load(source)["components"]["schemas"]["CheckoutRequest"]
        legacy = {"productId": str(uuid4()), "quantity": 1, "paymentMethod": "pm_approved"}
        basket = {"items": [{"productId": legacy["productId"], "quantity": 1}], "paymentMethod": "pm_approved"}
        for valid in (legacy, basket):
            contract_check.validate(valid, checkout, source)
        for invalid in ({**legacy, **basket}, {**basket, "totalMinor": 1}, {**basket, "items": []}):
            with self.assertRaises(contract_check.ContractError):
                contract_check.validate(invalid, checkout, source)

    def test_event_payload_envelope_and_kind_are_validated(self):
        schema, target = contract_check.resolve("#/components/messages/OrderCreated/payload", self.source)
        payload = {"eventId": str(uuid4()), "eventType": "order.created", "eventVersion": 1,
                   "occurredAt": "2026-09-12T00:00:00Z", "correlationId": str(uuid4()), "causationId": None,
                   "tenantId": str(uuid4()), "payload": {"orderId": str(uuid4()), "productId": str(uuid4()),
                   "customerId": "customer-subject", "quantity": 1, "totalMinor": 2500, "currency": "USD", "paymentMethod": "pm_approved"}}
        contract_check.validate(payload, schema, target)
        with self.assertRaises(contract_check.ContractError):
            contract_check.validate({**payload, "eventVersion": 2}, schema, target)
        with self.assertRaises(contract_check.ContractError):
            contract_check.validate({**payload, "eventType": "payment.authorized"}, schema, target)

    def test_http_path_parameters_and_shape_validation(self):
        spec = {"paths": {"/api/v1/orders/{id}": {"get": {"responses": {"200": {
            "content": {"application/json": {"schema": {"type": "object", "required": ["id"]}}}
        }}}}}}
        with patch.object(contract_check, "load", return_value=spec):
            contract_check.validate_response("order", "GET", "/api/v1/orders/abc", 200, {"Content-Type": "application/json;charset=UTF-8"}, {"id": "abc"})
            with self.assertRaises(contract_check.ContractError):
                contract_check.validate_response("order", "GET", "/api/v1/orders/abc", 200, {"Content-Type": "application/json"}, {})
            with self.assertRaises(contract_check.ContractError):
                contract_check.validate_response("order", "GET", "/api/v1/orders/abc", 202, {}, {})


class ComposeSmokeTests(unittest.TestCase):
    def run_main(self, *, no_build=False):
        output = io.StringIO()
        with (
            patch.object(compose_smoke, "docker_preflight"),
            patch.object(compose_smoke, "checked") as checked,
            patch.object(compose_smoke.importlib.util, "spec_from_file_location"),
            patch.object(compose_smoke.importlib.util, "module_from_spec", return_value=bootstrap),
            patch.object(bootstrap, "bootstrap") as initialize,
            patch.object(sys, "argv", ["compose-smoke.py"] + (["--no-build"] if no_build else [])),
            contextlib.redirect_stdout(output),
        ):
            compose_smoke.main()
        initialize.assert_called_once_with()
        self.assertEqual("PASS Docker Compose build/health/smoke. Services and persistent data remain available.\n", output.getvalue())
        self.assertEqual(["docker", "compose", "config", "--quiet"], checked.call_args_list[0].args[0])
        self.assertEqual({"quiet": True}, checked.call_args_list[0].kwargs)
        up = ["docker", "compose", "up", "--detach", "--wait", "--wait-timeout", "300"]
        self.assertEqual(up + ([] if no_build else ["--build"]), checked.call_args_list[1].args[0])
        self.assertEqual(3, checked.call_count)
        return checked.call_args_list[2].args[0]

    def test_smoke_runs_as_invoking_uid_and_gid(self):
        for no_build in (False, True):
            with (
                self.subTest(no_build=no_build),
                patch.object(compose_smoke.os, "getuid", return_value=1234, create=True) as getuid,
                patch.object(compose_smoke.os, "getgid", return_value=5678, create=True) as getgid,
            ):
                command = self.run_main(no_build=no_build)
                self.assertEqual(["docker", "compose", "--profile", "verification", "run", "--rm", "--no-deps", "--user", "1234:5678", "smoke"], command)
                getuid.assert_called_once_with()
                getgid.assert_called_once_with()

    def test_smoke_omits_user_when_identity_apis_are_unavailable(self):
        for missing in (("getuid",), ("getgid",), ("getuid", "getgid")):
            with (
                self.subTest(missing=missing),
                patch.object(compose_smoke.os, "getuid", return_value=1234, create=True),
                patch.object(compose_smoke.os, "getgid", return_value=5678, create=True),
            ):
                for name in missing:
                    delattr(compose_smoke.os, name)
                command = self.run_main()
                self.assertEqual(["docker", "compose", "--profile", "verification", "run", "--rm", "--no-deps", "smoke"], command)


class VerificationTests(unittest.TestCase):
    def test_docker_unavailable_is_a_failure_not_a_skip(self):
        with patch.object(ci.shutil, "which", return_value=None):
            with self.assertRaises(RuntimeError):
                ci.docker_preflight()

    def test_skipped_or_missing_integration_reports_fail_the_gate(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaises(RuntimeError):
                ci.verify_integration_results(root)
            report = root / "services/example/build/test-results/integrationTest/TEST-example.xml"
            report.parent.mkdir(parents=True)
            report.write_text('<testsuite tests="2" skipped="1"/>')
            with self.assertRaises(RuntimeError):
                ci.verify_integration_results(root)
            report.write_text('<testsuite tests="2" skipped="0"/>')
            with contextlib.redirect_stdout(io.StringIO()):
                ci.verify_integration_results(root)
            source = root / "services/missing/src/test/java/example/DatabaseTest.java"
            source.parent.mkdir(parents=True)
            source.write_text('@Tag("integration") class DatabaseTest {}')
            with self.assertRaises(RuntimeError):
                ci.verify_integration_results(root)

    def test_fixture_credentials_cannot_be_redirected_or_sent_to_public_hosts(self):
        with self.assertRaises(smoke.SmokeFailure):
            smoke.base_url("https://example.invalid", {"localhost"})
        with self.assertRaises(smoke.SmokeFailure):
            smoke.base_url("http://username:fixture@localhost", {"localhost"})
        self.assertIsNone(smoke.NoRedirect().redirect_request(None, None, 302, None, None, "https://example.invalid"))


class SmokeTokenTests(unittest.TestCase):
    def client(self):
        with patch.dict(smoke.os.environ, {}, clear=True):
            return smoke.Client({"LOCAL_FIXTURE_PASSWORD": "test-placeholder", "PROVIDER_API_KEY": "test-placeholder"})

    def test_long_workflow_renews_before_expiry_and_preserves_fixture_identity(self):
        client = self.client()
        replies = [smoke.Response(200, {}, {"access_token": value, "expires_in": 300})
                   for value in ("customer-old", "merchant-old", "customer-new")]
        with patch.object(client, "transport", side_effect=replies) as transport, patch.object(smoke.time, "monotonic", return_value=1000) as clock:
            customer = client.token("customer-a")
            clock.return_value = 1100
            merchant = client.token("merchant-a")
            clock.return_value = 1269
            self.assertEqual(customer, client.current_token(customer))
            clock.return_value = 1270
            self.assertEqual("customer-new", client.current_token(customer))
            self.assertEqual("customer-new", client.current_token(customer))
            self.assertEqual(merchant, client.current_token(merchant))
            self.assertEqual("external-token", client.current_token("external-token"))
            self.assertEqual(3, transport.call_count)
            self.assertIn(b"username=customer-a", transport.call_args.args[3])
        def denied(url, method, headers, data):
            self.assertEqual("Bearer customer-new", headers["Authorization"])
            return smoke.Response(401, {}, {})
        with patch.object(smoke.time, "monotonic", return_value=1271), patch.object(client, "transport", side_effect=denied) as transport:
            with self.assertRaises(smoke.SmokeFailure):
                client.call("order", "GET", "/api/v1/orders", customer, contract=False)
            self.assertEqual(1, transport.call_count)  # A real unexpected 401 is never hidden by retry.

    def test_concurrent_expired_aliases_trigger_one_renewal(self):
        client = self.client()
        replies = [smoke.Response(200, {}, {"access_token": value, "expires_in": 300}) for value in ("old", "new")]
        with patch.object(client, "transport", side_effect=replies) as transport, patch.object(smoke.time, "monotonic", return_value=1000) as clock:
            original = client.token("customer-a")
            clock.return_value = 1300
            with smoke.ThreadPoolExecutor(max_workers=8) as pool:
                self.assertEqual({"new"}, set(pool.map(lambda _: client.current_token(original), range(16))))
            self.assertEqual(2, transport.call_count)

    def test_clock_jump_or_suspension_renews_without_waiting_for_monotonic_expiry(self):
        for later_wall, later_monotonic in ((1600, 1001), (900, 1300)):
            client = self.client()
            replies = [smoke.Response(200, {}, {"access_token": value, "expires_in": 300}) for value in ("old", "new")]
            with patch.object(client, "transport", side_effect=replies) as transport, patch.object(smoke.time, "monotonic", return_value=1000) as monotonic, patch.object(smoke.time, "time", return_value=1000) as wall:
                original = client.token("customer-a")
                monotonic.return_value = later_monotonic
                wall.return_value = later_wall
                self.assertEqual("new", client.current_token(original))
                self.assertEqual("new", client.current_token(original))
                self.assertEqual(2, transport.call_count)

    def test_invalid_expiry_metadata_fails_without_reusing_a_token(self):
        for lifetime in (None, 0, -1, "300", True, 86401):
            client = self.client()
            response = smoke.Response(200, {}, {"access_token": "unused", "expires_in": lifetime})
            with patch.object(client, "transport", return_value=response):
                with self.assertRaises(smoke.SmokeFailure):
                    client.token("customer-a")
            self.assertFalse(client._sessions)


if __name__ == "__main__":
    unittest.main()
