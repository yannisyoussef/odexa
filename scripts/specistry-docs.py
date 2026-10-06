#!/usr/bin/env python3
"""Build Odexa docs with an already packed public Specistry CLI tarball."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def run(command, cwd=ROOT):
    result = subprocess.run(command, cwd=cwd)
    if result.returncode:
        raise RuntimeError("Specistry documentation command failed")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tarball", default=os.environ.get("SPECISTRY_CLI_TARBALL"))
    parser.add_argument("--node", default=shutil.which("node"))
    parser.add_argument("--npm", default=shutil.which("npm"))
    args = parser.parse_args()
    if not args.tarball:
        raise RuntimeError(
            "Pass --tarball or SPECISTRY_CLI_TARBALL; "
            "source/workspace imports are forbidden"
        )
    tarball = Path(args.tarball).resolve()
    if not tarball.is_file() or tarball.suffix != ".tgz":
        raise RuntimeError(
            "The Specistry distribution must be an existing .tgz package"
        )
    if not args.node or not args.npm:
        raise RuntimeError("Node 24 and npm are required")
    with tempfile.TemporaryDirectory(prefix="odexa-specistry-") as temporary:
        install = Path(temporary)
        (install / "package.json").write_text(
            '{"name":"odexa-specistry-clean-room","private":true,"version":"1.0.0"}\n'
        )
        run(
            [
                args.node,
                args.npm,
                "install",
                str(tarball),
                "--ignore-scripts",
                "--no-audit",
                "--no-fund",
                "--cache",
                str(install / ".npm-cache"),
            ],
            install,
        )
        executable = install / "node_modules" / ".bin" / (
            "specistry.cmd" if os.name == "nt" else "specistry"
        )
        run([args.node, str(executable), "validate", "--root", str(ROOT)])
        run([args.node, str(executable), "build", "--root", str(ROOT)])
        run([args.node, str(executable), "check", "--root", str(ROOT)])
    print("PASS Odexa developer portal (packed public Specistry CLI)")


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError) as error:
        raise SystemExit(f"FAIL: {error}")
