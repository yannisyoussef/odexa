"""Small executable architecture rules for independently deployable contexts."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
SERVICES = {"gateway", "catalog", "inventory", "order", "payment", "payment-simulator"}


class BoundaryTests(unittest.TestCase):
    def test_services_never_compile_against_another_service(self):
        for name in SERVICES:
            build = (ROOT / "services" / name / "build.gradle.kts").read_text()
            projects = re.findall(r'project\("([^\"]+)"\)', build)
            self.assertTrue(set(projects) <= {":libraries:runtime"}, name)
            if name in {"gateway", "payment-simulator"}:
                self.assertEqual([], projects, name)
            for source in (ROOT / "services" / name / "src/main/java").rglob("*.java"):
                for imported in re.findall(r"^import (?:static )?commerce\.([a-z]+)\.", source.read_text(), re.M):
                    self.assertIn(imported, {name.replace("-", ""), "runtime"}, str(source.relative_to(ROOT)))

    def test_runtime_cannot_depend_on_business_contexts(self):
        build = (ROOT / "libraries/runtime/build.gradle.kts").read_text()
        self.assertNotIn('project(', build)
        for source in (ROOT / "libraries/runtime/src/main/java").rglob("*.java"):
            for imported in re.findall(r"^import (?:static )?commerce\.([a-z]+)\.", source.read_text(), re.M):
                self.assertEqual("runtime", imported, str(source.relative_to(ROOT)))

    def test_service_contracts_and_dependency_locks_are_present(self):
        for name in SERVICES:
            self.assertTrue((ROOT / "contracts/openapi" / (name + ".json")).is_file(), name)
            self.assertTrue((ROOT / "services" / name / "gradle.lockfile").is_file(), name)


if __name__ == "__main__":
    unittest.main()
