#!/usr/bin/env python3
"""Dependency-free contract structure/reference checks and a deliberately bounded JSON Schema subset.

This is NOT official OpenAPI/AsyncAPI meta-schema validation. Runtime smoke checks
use the response-schema subset below; unknown assertion keywords fail explicitly.
"""
import json
from pathlib import Path
import sys
from urllib.parse import unquote, urlsplit
from uuid import UUID
from datetime import datetime
import re

ROOT = Path(__file__).resolve().parents[1]
METHODS = {"get", "post", "put", "patch", "delete", "head", "options", "trace"}


class ContractError(Exception):
    pass


def load(path):
    return json.loads(Path(path).read_text())


def resolve(ref, source):
    parts = urlsplit(ref)
    if parts.scheme or parts.netloc:
        raise ContractError("Remote contract references are not supported")
    target = (Path(source).parent / unquote(parts.path)).resolve() if parts.path else Path(source).resolve()
    contracts = (ROOT / "contracts").resolve()
    if not target.is_relative_to(contracts):
        raise ContractError("Contract reference escapes contracts directory")
    value = load(target)
    if parts.fragment:
        if not parts.fragment.startswith("/"):
            raise ContractError("Only JSON Pointer fragments are supported")
        for segment in unquote(parts.fragment)[1:].split("/"):
            key = segment.replace("~1", "/").replace("~0", "~")
            try:
                value = value[int(key)] if isinstance(value, list) else value[key]
            except (KeyError, ValueError, IndexError, TypeError) as exc:
                raise ContractError("Unresolved contract reference") from exc
    return value, target


def references(value, source):
    if isinstance(value, dict):
        if "$ref" in value:
            resolve(value["$ref"], source)
        for nested in value.values():
            references(nested, source)
    elif isinstance(value, list):
        for nested in value:
            references(nested, source)


def check_contract(path):
    value = load(path)
    references(value, path)
    if not value.get("info", {}).get("title") or not value.get("info", {}).get("version"):
        raise ContractError("Contract info is incomplete")
    if "openapi" in value:
        if not value["openapi"].startswith("3.") or not value.get("paths"):
            raise ContractError("Expected a nonempty OpenAPI 3 document")
        ids = set()
        for route, item in value["paths"].items():
            if not route.startswith("/"):
                raise ContractError("OpenAPI paths must be absolute")
            if "$ref" in item:
                item, _ = resolve(item["$ref"], path)
            for method, operation in item.items():
                if method not in METHODS:
                    continue
                if not operation.get("responses"):
                    raise ContractError("HTTP operation has no responses")
                operation_id = operation.get("operationId")
                if operation_id:
                    if operation_id in ids:
                        raise ContractError("Duplicate operationId")
                    ids.add(operation_id)
    elif value.get("asyncapi", "").startswith("3."):
        if not value.get("channels") or not value.get("operations"):
            raise ContractError("AsyncAPI 3 channels and operations are required")
        for channel in value["channels"].values():
            if not channel.get("address") or not channel.get("messages"):
                raise ContractError("AsyncAPI channel requires address and messages")
        for operation in value["operations"].values():
            if operation.get("action") not in {"send", "receive"} or "$ref" not in operation.get("channel", {}):
                raise ContractError("Invalid AsyncAPI 3 operation")
            channel, _ = resolve(operation["channel"]["$ref"], path)
            for reference in operation.get("messages", []):
                message, _ = resolve(reference["$ref"], path)
                if message not in channel["messages"].values():
                    raise ContractError("Operation message does not belong to its channel")
    else:
        raise ContractError("Expected OpenAPI 3 or AsyncAPI 3")


ANNOTATIONS = {"title", "description", "example", "examples", "default", "deprecated", "readOnly", "writeOnly", "$id", "$schema", "$comment", "discriminator", "xml", "externalDocs"}
ASSERTIONS = {"$ref", "type", "nullable", "required", "properties", "additionalProperties", "items", "enum", "const", "allOf", "anyOf", "oneOf", "not", "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf", "minLength", "maxLength", "pattern", "format", "minItems", "maxItems", "uniqueItems", "minProperties", "maxProperties"}


def validate(value, schema, source, depth=0):
    if depth > 50:
        raise ContractError("Schema recursion bound exceeded")
    if schema is True:
        return
    if schema is False:
        raise ContractError("Value is forbidden")
    if not isinstance(schema, dict):
        raise ContractError("Invalid schema")
    unknown = set(schema) - ANNOTATIONS - ASSERTIONS
    if unknown:
        raise ContractError("Response schema uses an unsupported assertion keyword")
    if "$ref" in schema:
        referenced, target = resolve(schema["$ref"], source)
        validate(value, referenced, target, depth + 1)
    if value is None and schema.get("nullable"):
        return
    for keyword in ("allOf", "anyOf", "oneOf"):
        if keyword in schema:
            successes = 0
            for branch in schema[keyword]:
                try:
                    validate(value, branch, source, depth + 1)
                    successes += 1
                except ContractError:
                    pass
            if (keyword == "allOf" and successes != len(schema[keyword])) or (keyword == "anyOf" and successes == 0) or (keyword == "oneOf" and successes != 1):
                raise ContractError("Schema composition mismatch")
    if "not" in schema:
        try:
            validate(value, schema["not"], source, depth + 1)
        except ContractError:
            pass
        else:
            raise ContractError("Value matches a forbidden schema")
    types = schema.get("type", [])
    types = [types] if isinstance(types, str) else types
    predicates = {"null": value is None, "object": isinstance(value, dict), "array": isinstance(value, list),
                  "string": isinstance(value, str), "boolean": isinstance(value, bool),
                  "integer": isinstance(value, int) and not isinstance(value, bool),
                  "number": isinstance(value, (int, float)) and not isinstance(value, bool)}
    if types and not any(predicates.get(kind, False) for kind in types):
        raise ContractError("Response type mismatch")
    if "enum" in schema and value not in schema["enum"]:
        raise ContractError("Response enum mismatch")
    if "const" in schema and value != schema["const"]:
        raise ContractError("Response constant mismatch")
    if isinstance(value, dict):
        if not set(schema.get("required", [])).issubset(value):
            raise ContractError("Required response field missing")
        for key, nested in value.items():
            if key in schema.get("properties", {}):
                validate(nested, schema["properties"][key], source, depth + 1)
            elif schema.get("additionalProperties") is False:
                raise ContractError("Unexpected response field")
            elif isinstance(schema.get("additionalProperties"), dict):
                validate(nested, schema["additionalProperties"], source, depth + 1)
        bounded(len(value), schema, "minProperties", "maxProperties")
    elif isinstance(value, list):
        bounded(len(value), schema, "minItems", "maxItems")
        for nested in value:
            validate(nested, schema.get("items", {}), source, depth + 1)
        if schema.get("uniqueItems") and len({json.dumps(item, sort_keys=True) for item in value}) != len(value):
            raise ContractError("Response array contains duplicates")
    elif isinstance(value, str):
        bounded(len(value), schema, "minLength", "maxLength")
        if "pattern" in schema and re.search(schema["pattern"], value) is None:
            raise ContractError("Response string pattern mismatch")
        try:
            if schema.get("format") == "uuid":
                if str(UUID(value)) != value.lower():
                    raise ValueError()
            if schema.get("format") == "date-time":
                if datetime.fromisoformat(value.replace("Z", "+00:00")).tzinfo is None:
                    raise ValueError()
        except ValueError as exc:
            raise ContractError("Response string format mismatch") from exc
    elif isinstance(value, (int, float)) and not isinstance(value, bool):
        bounded(value, schema, "minimum", "maximum")
        for name, invalid in (("exclusiveMinimum", lambda boundary: value <= boundary), ("exclusiveMaximum", lambda boundary: value >= boundary)):
            if name in schema and not isinstance(schema[name], bool) and invalid(schema[name]):
                raise ContractError("Response number outside exclusive bounds")
        if schema.get("multipleOf") and abs(value / schema["multipleOf"] - round(value / schema["multipleOf"])) > 1e-9:
            raise ContractError("Response multipleOf mismatch")


def bounded(value, schema, low, high):
    if (low in schema and value < schema[low]) or (high in schema and value > schema[high]):
        raise ContractError("Response value outside schema bounds")


def validate_response(service, method, path, status, headers, body):
    source = ROOT / "contracts/openapi" / f"{service}.json"
    spec = load(source)
    for route, item in spec["paths"].items():
        expression = "^" + re.sub(r"\\\{[^}]+\\\}", "[^/]+", re.escape(route)) + "$"
        if not re.fullmatch(expression, path):
            continue
        if "$ref" in item:
            item, source = resolve(item["$ref"], source)
        operation = item.get(method.lower())
        if not operation:
            continue
        response = operation["responses"].get(str(status), operation["responses"].get("default"))
        if response is None:
            raise ContractError("Response status is not documented")
        if "$ref" in response:
            response, source = resolve(response["$ref"], source)
        content = response.get("content", {})
        if content:
            media_type = headers.get("Content-Type", "").split(";")[0]
            if media_type not in content:
                raise ContractError("Response media type is not documented")
            if "schema" in content[media_type]:
                validate(body, content[media_type]["schema"], source)
        return
    raise ContractError("HTTP operation is absent from service contract")


def main():
    paths = sorted((ROOT / "contracts").glob("**/*.json"))
    expected = {"catalog", "inventory", "order", "payment", "payment-simulator", "gateway"}
    if {path.stem for path in paths if path.parent.name == "openapi"} != expected:
        raise ContractError("Missing or unexpected service OpenAPI documents")
    if not (ROOT / "contracts/asyncapi/commerce.json").exists():
        raise ContractError("Missing commerce AsyncAPI document")
    for path in paths:
        check_contract(path)
    print(f"PASS: {len(paths)} contracts checked for structure and local references (not official meta-schema validation).")


if __name__ == "__main__":
    try:
        main()
    except (ContractError, OSError, ValueError, KeyError, TypeError):
        print("FAIL: contract structure/reference validation; no payload values displayed.", file=sys.stderr)
        sys.exit(1)
