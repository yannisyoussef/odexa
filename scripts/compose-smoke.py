#!/usr/bin/env python3
"""Build and verify local Compose. Leaves data/services intact for inspection; never dumps compose config or secrets."""
import argparse
import importlib.util
import os
from pathlib import Path
import subprocess
import sys

from ci import docker_preflight

ROOT = Path(__file__).resolve().parents[1]


def checked(command, quiet=False):
    kwargs = {"stdout": subprocess.DEVNULL, "stderr": subprocess.DEVNULL} if quiet else {}
    environment = os.environ.copy()
    environment.setdefault("COMPOSE_PARALLEL_LIMIT", "2")
    if subprocess.run(command, cwd=ROOT, env=environment, **kwargs).returncode:
        raise RuntimeError("Compose verification command failed; credentials and service logs were not dumped")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--no-build", action="store_true", help="Verify previously built images")
    args = parser.parse_args()
    docker_preflight()
    spec = importlib.util.spec_from_file_location("bootstrap_local", ROOT / "scripts/bootstrap-local.py")
    bootstrap = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(bootstrap)
    bootstrap.bootstrap()
    # Do not call `docker compose config` without --quiet: it renders every interpolated secret.
    checked(["docker", "compose", "config", "--quiet"], quiet=True)
    command = ["docker", "compose", "up", "--detach", "--wait", "--wait-timeout", "300"]
    if not args.no_build:
        command.append("--build")
    checked(command)
    command = ["docker", "compose", "--profile", "verification", "run", "--rm", "--no-deps"]
    # Match the owner of the private bind-mounted env file without changing its permissions.
    if hasattr(os, "getuid") and hasattr(os, "getgid"):
        command.extend(["--user", f"{os.getuid()}:{os.getgid()}"])
    command.append("smoke")
    checked(command)
    print("PASS Docker Compose build/health/smoke. Services and persistent data remain available.")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, ValueError, KeyError):
        print("FAIL: Compose preflight/build/health/smoke; inspect local service status without exposing credentials. No infrastructure was deleted.", file=sys.stderr)
        sys.exit(1)
