#!/usr/bin/env python3
"""Fast verification and a Docker-required integration gate; never silently skip infrastructure."""
import argparse
from pathlib import Path
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def docker_preflight():
    if not shutil.which("docker"):
        raise RuntimeError("Docker CLI is required for integration verification")
    for command in (["docker", "info"], ["docker", "compose", "version"]):
        # Metadata may contain registry/proxy configuration. Only use its exit status.
        if subprocess.run(command, cwd=ROOT, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode:
            raise RuntimeError("A running Docker daemon and Docker Compose v2 are required")
    print("PASS Docker daemon/Compose preflight", flush=True)


def run(command):
    result = subprocess.run(command, cwd=ROOT)
    if result.returncode:
        raise RuntimeError("Verification command failed")


def verify_integration_results(root=ROOT):
    executed = 0
    skipped = 0
    reported = set()
    for path in root.glob("**/build/test-results/integrationTest/TEST-*.xml"):
        suite = ET.parse(path).getroot()
        executed += int(suite.get("tests", "0"))
        skipped += int(suite.get("skipped", "0"))
        if int(suite.get("failures", "0")) or int(suite.get("errors", "0")):
            raise RuntimeError("Integration reports contain failures or errors")
        if int(suite.get("tests", "0")):
            reported.add(path)
    # A passing module must not conceal missing reports from another module.
    for source in root.glob("**/src/test/java/**/*.java"):
        if '@Tag("integration")' not in source.read_text():
            continue
        module = next(parent for parent in source.parents if (parent / "src/test/java").is_dir())
        name = ".".join(source.relative_to(module / "src/test/java").with_suffix("").parts)
        expected = module / "build/test-results/integrationTest" / f"TEST-{name}.xml"
        if expected not in reported:
            raise RuntimeError("Integration gate requires a nonempty report for every infrastructure test class")
    if executed == 0 or skipped:
        raise RuntimeError("Integration gate requires executed tests and forbids silently skipped infrastructure tests")
    print(f"PASS integration reports: {executed} tests, zero skipped", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("target", choices=("check", "integration"))
    args = parser.parse_args()
    if args.target == "integration":
        docker_preflight()
        # Every suite starts its own brokers and databases; bound how many start at once.
        run(["sh", "./gradlew", "--no-daemon", "--max-workers=2", "integrationTest", "--rerun-tasks"])
        verify_integration_results()
    else:
        run([sys.executable, "-B", "-m", "unittest", "discover", "-s", "scripts/tests", "-v"])
        run([sys.executable, "-B", "scripts/contract_check.py"])
        run(["sh", "./gradlew", "--no-daemon", "check", "bootJar"])
    print(f"PASS {args.target} verification", flush=True)


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError) as error:
        # The wrapper constructs RuntimeError messages; subprocess output never includes credential commands.
        print(f"FAIL: {error if isinstance(error, RuntimeError) else 'Could not execute verification tool'}", file=sys.stderr)
        sys.exit(1)
