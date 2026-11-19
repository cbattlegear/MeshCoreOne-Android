import hashlib
import json
import math
import re
from pathlib import Path

from .errors import PortError


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise PortError(f"Duplicate JSON key: {key}")
        result[key] = value
    return result


def decode_json(text: str):
    def invalid_constant(value):
        raise PortError(f"Non-finite JSON number: {value}")

    try:
        return json.loads(
            text, object_pairs_hook=_unique_object, parse_constant=invalid_constant
        )
    except json.JSONDecodeError as error:
        raise PortError(f"Malformed JSON at line {error.lineno}: {error.msg}") from error


def load_json(path: Path, maximum_bytes: int = 16 * 1024 * 1024):
    if not path.is_file():
        raise PortError(f"Missing JSON input: {path}")
    if path.stat().st_size > maximum_bytes:
        raise PortError(f"JSON input exceeds {maximum_bytes} bytes: {path}")
    try:
        return decode_json(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError) as error:
        raise PortError(f"Cannot read UTF-8 JSON: {path}") from error


def digest(value) -> str:
    return hashlib.sha256(
        json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode()
    ).hexdigest()


def check_schema(value, schema: dict, location: str = "$"):
    """Validate the JSON Schema keywords used by the checked-in port schemas."""
    supported = {
        "$schema", "title", "description", "type", "const", "enum", "required", "properties",
        "additionalProperties", "minItems", "uniqueItems", "items", "minLength", "pattern", "minimum",
    }
    if schema.keys() - supported:
        raise PortError(f"{location}: unsupported schema keywords {sorted(schema.keys() - supported)}")
    types = {
        "object": lambda v: isinstance(v, dict),
        "array": lambda v: isinstance(v, list),
        "string": lambda v: isinstance(v, str),
        "integer": lambda v: type(v) is int,
        "number": lambda v: type(v) in (int, float) and math.isfinite(v),
        "boolean": lambda v: type(v) is bool,
        "null": lambda v: v is None,
    }
    expected = schema.get("type")
    if expected is not None:
        names = expected if isinstance(expected, list) else [expected]
        if not any(types[name](value) for name in names):
            raise PortError(f"{location}: expected {expected}")
    if "const" in schema and value != schema["const"]:
        raise PortError(f"{location}: expected constant {schema['const']!r}")
    if "enum" in schema and value not in schema["enum"]:
        raise PortError(f"{location}: unknown value {value!r}")
    if isinstance(value, dict):
        missing = set(schema.get("required", [])) - value.keys()
        if missing:
            raise PortError(f"{location}: missing fields {sorted(missing)}")
        properties = schema.get("properties", {})
        if schema.get("additionalProperties") is False:
            unknown = value.keys() - properties.keys()
            if unknown:
                raise PortError(f"{location}: unknown fields {sorted(unknown)}")
        for key, child in value.items():
            if key in properties:
                check_schema(child, properties[key], f"{location}.{key}")
    if isinstance(value, list):
        if len(value) < schema.get("minItems", 0):
            raise PortError(f"{location}: empty or undersized array")
        if schema.get("uniqueItems") and len({digest(v) for v in value}) != len(value):
            raise PortError(f"{location}: duplicate array entries")
        if "items" in schema:
            for index, child in enumerate(value):
                check_schema(child, schema["items"], f"{location}[{index}]")
    if isinstance(value, str):
        if len(value) < schema.get("minLength", 0):
            raise PortError(f"{location}: empty or undersized string")
        if "pattern" in schema and re.search(schema["pattern"], value) is None:
            raise PortError(f"{location}: invalid format")
    if type(value) in (int, float) and "minimum" in schema:
        if value < schema["minimum"]:
            raise PortError(f"{location}: below minimum {schema['minimum']}")
