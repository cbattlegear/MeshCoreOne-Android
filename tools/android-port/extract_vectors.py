"""AndroidOnly: WP-004 Copy independent pinned byte literals, never run candidate Kotlin."""

import argparse
import re
import sys
from pathlib import Path

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parent))

from portlib.errors import PortError
from oracle.reference import (
    FrozenReference, OracleError, REPO, SOURCE_SHA, exact_fields, json_bytes,
    repo_path, sha256, write_or_check,
)
from oracle.swift import Syntax, assertion_sites, canonical, declarations

PYTHON_PATH = "MeshCore/Tests/MeshCoreTests/Fixtures/PythonReferenceBytes.swift"
JSON_PATH = "android/core/testing/fixtures/protocol-vectors.json"
TSV_PATH = "android/core/testing/fixtures/protocol-vectors.tsv"
PROTOCOL_VERSION = "pinned-reference-snapshot"
TSV_COLUMNS = ("id", "category", "role", "hex", "byte_count", "source_path", "blob_sha", "source_line", "protocol_version")
EXTRAS = (
    ("uint32-little-endian", "Protocol/DataExtensionsTests.swift", "appendLittleEndian UInt32", 0, "bytes", "expected-bytes"),
    ("int32-minus-one", "Protocol/DataExtensionsTests.swift", "appendLittleEndian Int32", 0, "bytes", "expected-bytes"),
    ("lpp-negative-int24", "Validation/LPPPythonReferenceTests.swift", "Load negative round trips through 24-bit sign extension", 0, "lpp", "input-with-source-assertions"),
    ("lpp-high-bit-uint32", "Validation/LPPPythonReferenceTests.swift", "Generic sensor decodes high bit set as a large positive integer", 0, "lpp", "input-with-source-assertions"),
    ("channel-crypto-truncated", "ChannelCryptoTests.swift", "Decrypt payload too short", 0, "crypto", "invalid-input-with-source-assertions"),
    ("direct-crypto-truncated", "DirectMessageCryptoTests.swift", "Decrypt payload too short", 0, "crypto", "invalid-input-with-source-assertions"),
    ("raw-data-truncated", "Validation/RawDataParsingTests.swift", "rawData rejects short payload", 0, "parser", "invalid-input-with-source-assertions"),
    ("region-response-truncated", "Protocol/RegionTests.swift", "throws on response shorter than 4 bytes", 0, "parser", "invalid-input-with-source-assertions"),
    ("wifi-incomplete-header", "Transport/WiFiFrameCodecTests.swift", "Buffers incomplete frame", 0, "frame", "input-with-source-assertions"),
    ("wifi-incomplete-body", "Transport/WiFiFrameCodecTests.swift", "Buffers incomplete frame", 1, "frame", "input-with-source-assertions"),
    ("wifi-final-body", "Transport/WiFiFrameCodecTests.swift", "Buffers incomplete frame", 2, "frame", "input-with-source-assertions"),
    ("wifi-expected-body", "Transport/WiFiFrameCodecTests.swift", "Buffers incomplete frame", 3, "frame", "expected-bytes"),
)


def byte_literal(token):
    if token.kind != "number" or not re.fullmatch(r"(?:0[xX][0-9a-fA-F_]+|0[bB][01_]+|0[oO][0-7_]+|[0-9][0-9_]*)", token.text):
        raise OracleError(f"Non-integer/dynamic byte literal at line {token.line}: {token.text}")
    value = token.text.replace("_", "")
    base = 16 if value.lower().startswith("0x") else 2 if value.lower().startswith("0b") else 8 if value.lower().startswith("0o") else 10
    integer = int(value, base)
    if not 0 <= integer <= 255:
        raise OracleError(f"Out-of-range reference byte at line {token.line}: {integer}")
    return integer


def data_array(syntax, begin, end):
    tokens = syntax.tokens
    if [token.text for token in tokens[begin:begin + 3]] != ["Data", "(", "["]:
        raise OracleError("Expected independent Data([literal bytes])")
    close = syntax.pairs[begin + 2]
    if close + 2 != end or tokens[close + 1].text != ")":
        raise OracleError("Reference bytes are not a single complete literal Data array")
    result = []
    for a, b in syntax.split(begin + 3, close):
        if b != a + 1:
            raise OracleError("Dynamic/computed reference byte expression is forbidden")
        result.append(byte_literal(tokens[a]))
    return result


def literal_arrays(syntax, begin, end):
    result, index = [], begin
    while index < end:
        if [token.text for token in syntax.tokens[index:index + 3]] == ["Data", "(", "["]:
            finish = syntax.pairs[index + 1] + 1
            result.append((index, finish, data_array(syntax, index, finish)))
            index = finish
        else:
            index += 1
    return result


def vector(reference, path, text, syntax, identity, category, role, begin, end, values, *, field, case=None):
    copied = bytes(values)
    return {
        "id": identity, "category": category, "role": role,
        "protocol_version": PROTOCOL_VERSION, "bytes": values, "hex": copied.hex(),
        "byte_count": len(copied), "bytes_sha256": sha256(copied),
        "provenance": {
            **reference.provenance(path, text),
            "field": field, "line": syntax.tokens[begin].line,
            "expression": syntax.raw(begin, end),
            "origin": "pinned-meshcore-py-literal" if path == PYTHON_PATH else "pinned-swift-test-literal",
            "case": case,
        },
    }


def validate_vectors(value, reference=None):
    exact_fields(value, {"schema_version", "source_sha", "generator", "vectors"}, "golden vector document")
    if value["schema_version"] != 1 or value["source_sha"] != SOURCE_SHA:
        raise OracleError("Stale/unsupported independent-vector source/schema")
    if value["generator"] != "tools/android-port/extract_vectors.py" or not isinstance(value["vectors"], list) or not value["vectors"]:
        raise OracleError("Missing/zero independent golden vectors")
    identities = set()
    for item in value["vectors"]:
        exact_fields(item, {"id", "category", "role", "protocol_version", "bytes", "hex", "byte_count",
                            "bytes_sha256", "provenance"}, "golden vector")
        if not isinstance(item["id"], str) or not item["id"] or item["id"] in identities:
            raise OracleError("Duplicate/empty vector identity")
        identities.add(item["id"])
        if item["protocol_version"] != PROTOCOL_VERSION:
            raise OracleError("Unknown/invented protocol version")
        if not isinstance(item["bytes"], list) or any(type(byte) is not int or not 0 <= byte <= 255 for byte in item["bytes"]):
            raise OracleError("Invalid golden byte input")
        copied = bytes(item["bytes"])
        if type(item["byte_count"]) is not int or item["byte_count"] != len(copied) or item["hex"] != copied.hex() or item["bytes_sha256"] != sha256(copied):
            raise OracleError("Golden vector byte count/encoding/digest mismatch")
        provenance = item["provenance"]
        exact_fields(provenance, {"path", "source_sha", "blob_sha", "utf8_bytes", "sha256", "field", "line",
                                 "expression", "origin", "case"}, "vector provenance")
        if (provenance["source_sha"] != SOURCE_SHA or not re.fullmatch(r"[0-9a-f]{40}", provenance["blob_sha"])
                or type(provenance["line"]) is not int or provenance["line"] < 1
                or type(provenance["utf8_bytes"]) is not int or provenance["utf8_bytes"] < 1):
            raise OracleError("Malformed/stale vector source provenance")
        if reference is not None and reference.inventory.get(provenance["path"], {}).get("blob_sha") != provenance["blob_sha"]:
            raise OracleError("Golden source blob drift")
        literal = Syntax(provenance["expression"])
        if data_array(literal, 0, len(literal.tokens)) != item["bytes"]:
            raise OracleError("Golden bytes differ from the copied independent literal")


def tsv_bytes(value):
    validate_vectors(value)
    lines = [
        f"# Generated by tools/android-port/extract_vectors.py from {SOURCE_SHA}; MIT MeshCore reference fixtures.",
        "\t".join(TSV_COLUMNS),
    ]
    for item in value["vectors"]:
        source = item["provenance"]
        row = (item["id"], item["category"], item["role"], item["hex"], str(item["byte_count"]),
               source["path"], source["blob_sha"], str(source["line"]), item["protocol_version"])
        if any("\t" in field or "\n" in field or "\r" in field for field in row):
            raise OracleError("Invalid TSV fixture field")
        lines.append("\t".join(row))
    return ("\n".join(lines) + "\n").encode("utf-8")


def generate(reference: FrozenReference):
    paths = [PYTHON_PATH] + ["MeshCore/Tests/MeshCoreTests/" + extra[1] for extra in EXTRAS]
    sources = reference.read_many(paths)
    result = {"schema_version": 1, "source_sha": SOURCE_SHA,
              "generator": "tools/android-port/extract_vectors.py", "vectors": []}
    syntax = Syntax(sources[PYTHON_PATH])
    members = declarations(syntax)
    definitions = [member for member in members if member.scope == ("PythonReferenceBytes",) and member.kind == "let"]
    if not definitions:
        raise OracleError("Zero pinned Python reference byte fields")
    for definition in definitions:
        equals = next((i for i in range(definition.keyword, definition.end) if syntax.tokens[i].text == "="), None)
        if equals is None:
            raise OracleError("Reference field has no literal initializer")
        begin, end = equals + 1, definition.end
        values = data_array(syntax, begin, end)
        result["vectors"].append(vector(
            reference, PYTHON_PATH, sources[PYTHON_PATH], syntax, "python." + definition.name,
            "lpp" if definition.name.startswith("lpp_") else "builder", "expected-bytes",
            begin, end, values, field=definition.name,
        ))
    for identity, relative, name, ordinal, category, role in EXTRAS:
        path = "MeshCore/Tests/MeshCoreTests/" + relative
        syntax = Syntax(sources[path])
        functions = [member for member in declarations(syntax) if member.kind == "func" and member.name == name]
        if len(functions) != 1 or functions[0].body is None:
            raise OracleError(f"Missing/ambiguous independent vector source case: {path}::{name}")
        function = functions[0]
        sites = assertion_sites(syntax.tokens[slice(*function.body)])
        if not sites:
            raise OracleError("Independent edge-vector case has no source assertions")
        arrays = literal_arrays(syntax, *function.body)
        if ordinal >= len(arrays):
            raise OracleError("Missing independent reference array ordinal")
        begin, end, values = arrays[ordinal]
        result["vectors"].append(vector(
            reference, path, sources[path], syntax, "swift." + identity, category, role,
            begin, end, values, field=f"Data-literal[{ordinal}]",
            case={"suite": ".".join(function.scope), "method": function.name,
                  "line": syntax.tokens[function.keyword].line,
                  "declaration": syntax.raw(function.begin, function.end), "assertions": sites},
        ))
    validate_vectors(result, reference)
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true")
    mode.add_argument("--regenerate", action="store_true")
    parser.add_argument("--repo", type=Path, default=REPO)
    parser.add_argument("--source-sha", default=SOURCE_SHA)
    args = parser.parse_args(argv)
    try:
        result = generate(FrozenReference(args.repo, args.source_sha))
        write_or_check(repo_path(args.repo, JSON_PATH), json_bytes(result), check=args.check)
        write_or_check(repo_path(args.repo, TSV_PATH), tsv_bytes(result), check=args.check)
        print(json_bytes({"result": "checked" if args.check else "regenerated", "vectors": len(result["vectors"]),
                          "source_sha": SOURCE_SHA, "candidate_kotlin_executed": False}).decode(), end="")
        return 0
    except (PortError, OSError, ValueError, KeyError) as error:
        print(f"BLOCKED: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
