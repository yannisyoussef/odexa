#!/usr/bin/env python3
"""Generate local-only credentials and a Keycloak import; never print credential values."""
import json
import os
from pathlib import Path
import secrets
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SECRET_KEYS = (
    "POSTGRES_PASSWORD", "CATALOG_DB_PASSWORD", "INVENTORY_DB_PASSWORD", "ORDER_DB_PASSWORD",
    "PAYMENT_DB_PASSWORD", "SIMULATOR_DB_PASSWORD", "KEYCLOAK_DB_PASSWORD",
    "KC_BOOTSTRAP_ADMIN_PASSWORD", "LOCAL_FIXTURE_PASSWORD", "PROVIDER_API_KEY", "SIMULATOR_WEBHOOK_SECRET",
)


def read_env(path):
    values = {}
    if path.is_symlink():
        raise ValueError("Refusing a symlinked credential file")
    for line in path.read_text().splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        key, separator, value = line.partition("=")
        if not separator or key in values:
            raise ValueError("Invalid or duplicate local environment entry")
        values[key.strip()] = value.strip()
    return values


def private_directory(path):
    if path.is_symlink():
        raise ValueError("Refusing a symlinked generated directory")
    path.mkdir(mode=0o700, parents=True, exist_ok=True)
    path.chmod(0o700)


def atomic_json(path, value):
    if path.is_symlink():
        raise ValueError("Refusing a symlinked realm import")
    descriptor, temporary = tempfile.mkstemp(prefix=".realm-", dir=path.parent)
    try:
        with os.fdopen(descriptor, "w") as stream:
            json.dump(value, stream, indent=2)
            stream.write("\n")
        # The containing host directories are 0700. The read-only file bind mount
        # must be readable by Keycloak's non-root container UID.
        os.chmod(temporary, 0o644)
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def bootstrap(root=ROOT):
    ignores = (root / ".gitignore").read_text().splitlines()
    if ".env" not in ignores or ".local/" not in ignores:
        raise ValueError("Repository must ignore .env and .local/ before generating secrets")
    env_path = root / ".env"
    if env_path.is_symlink():
        raise ValueError("Refusing a symlinked credential file")
    created = not env_path.exists()
    if created:
        values = {key: secrets.token_urlsafe(32) for key in SECRET_KEYS}
        values["OIDC_ISSUER"] = "http://localhost:8180/realms/odexa"
        descriptor = os.open(env_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "w") as stream:
            stream.write("# Generated local credentials; never commit or print this file.\n")
            stream.writelines(f"{key}={value}\n" for key, value in values.items())
    else:
        values = read_env(env_path)
    if any(not values.get(key) for key in SECRET_KEYS if key != "SIMULATOR_WEBHOOK_SECRET"):
        raise ValueError("Existing .env is incomplete; refusing automatic credential rotation")
    # Add only the new independent signing credential; never rotate existing keys.
    if not created and "SIMULATOR_WEBHOOK_SECRET" not in values:
        values["SIMULATOR_WEBHOOK_SECRET"] = secrets.token_urlsafe(32)
        with env_path.open("a") as stream:
            stream.write("\nSIMULATOR_WEBHOOK_SECRET=" + values["SIMULATOR_WEBHOOK_SECRET"] + "\n")
    if any(not values.get(key) for key in SECRET_KEYS):
        raise ValueError("Existing .env is incomplete; refusing automatic credential rotation")
    env_path.chmod(0o600)
    private_directory(root / ".local")
    private_directory(root / ".local" / "keycloak")
    template = json.loads((root / "infrastructure/keycloak/odexa-realm.template.json").read_text())
    for user in template["users"]:
        for credential in user["credentials"]:
            if credential["value"] != "__LOCAL_FIXTURE_PASSWORD__":
                raise ValueError("Realm template must contain placeholders, not credentials")
            credential["value"] = values["LOCAL_FIXTURE_PASSWORD"]
    atomic_json(root / ".local/keycloak/odexa-realm.json", template)
    return created


def main():
    try:
        created = bootstrap()
    except (OSError, ValueError, KeyError):
        print("Local bootstrap failed; check ignored paths, permissions and required environment keys. No credentials were displayed.", file=sys.stderr)
        return 1
    print("Local credentials created." if created else "Existing local credentials preserved; no rotation performed.")
    print("Keycloak local realm rendered under ignored .local/. Existing database volumes are not changed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
