"""AndroidOnly: WP-203 Full frozen SwiftData restore/export on ephemeral macOS, with data-only handoff."""

import argparse
import hashlib
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from portlib.errors import PortError
from portlib.schema import load_json
from oracle.codec_harness import mac_environment, run as run_reference_codec
from oracle.reference import FrozenReference, OracleError, REPO, SOURCE_SHA, json_bytes, sha256, write_or_check

TEST_NAME = "testRealKotlinExportRestoresIntoSwiftDataAndSwiftExportsForRoom"
HARNESS = "docs/android/evidence/WP-203/WP203InteropTests.swift"
DOCC = '.package(url: "https://github.com/apple/swift-docc-plugin", from: "1.4.0")'
TASK_DESCRIPTION = [
    "python tools/android-port/oracle/codec_harness.py run --output <new runner-temp codec directory>",
    "swift test --package-path <new frozen MC1Services stage> --filter WP203InteropTests "
    "--parallel --num-workers 1 --disable-swift-testing --verbose --xunit-output <raw runner-temp Swift XML> "
    "--scratch-path <new runner-temp Swift build>",
]
DATA_NAMES = {
    "producer": ("kotlin-export.meshcoreone", "kotlin-export.json", "kotlin-room-proof.json"),
    "swift": ("swift-export.meshcoreone", "swift-export.json", "swift-room-proof.json", "swift-results.json",
              "swift-source-map.json", "swift-results.xml", "swift-restore-export.log"),
}
MAXIMUM_DATA_BYTES = 52_428_800


def digest_file(path):
    if path.is_symlink() or not path.is_file() or path.stat().st_size > MAXIMUM_DATA_BYTES:
        raise OracleError("Missing, linked or oversized backup data artifact: " + path.name)
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def record(root, name):
    path = root / name
    file_digest = digest_file(path)
    return {"path": name, "size": path.stat().st_size, "sha256": file_digest}


def validate_producer_data(root):
    records = [record(root, name) for name in DATA_NAMES["producer"]]
    exported = load_json(root / "kotlin-export.json")
    proof = load_json(root / "kotlin-room-proof.json")
    expected_proof = {
        "compressedSha256", "inserted", "messageId", "preferencesRestored", "producer",
        "radioId", "restoreVerified", "skipped",
    }
    if set(proof) != expected_proof:
        raise OracleError("Malformed Kotlin Room producer proof")
    compressed = next(item["sha256"] for item in records if item["path"] == "kotlin-export.meshcoreone")
    if (proof["compressedSha256"] != compressed or proof["inserted"] != 12 or proof["skipped"] != 0
            or proof["restoreVerified"] is not True or proof["preferencesRestored"] is not True
            or proof["producer"] != "actual-Room-Kotlin-export"
            or proof["messageId"] != "00000000-0000-0000-0000-000000000004"
            or proof["radioId"] != "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"):
        raise OracleError("Kotlin Room producer proof disagrees with actual backup bytes")
    manifest = exported.get("manifest")
    expected_manifest = {
        "blockedChannelSenderCount", "channelCount", "contactCount", "deviceCount",
        "discoveredNodeCount", "messageCount", "messageRepeatCount", "nodeStatusSnapshotCount",
        "reactionCount", "remoteNodeSessionCount", "roomMessageCount", "savedTracePathCount",
    }
    if (exported.get("version") != 1 or not isinstance(manifest, dict)
            or set(manifest) != expected_manifest or any(manifest[name] != 1 for name in expected_manifest)
            or not isinstance(exported.get("userDefaults"), dict)):
        raise OracleError("Kotlin export semantics are stale or incomplete")
    return {"stage": "producer", "data": records, "proof": proof}


def stage_package(reference, destination):
    if destination.exists() or not destination.is_absolute():
        raise OracleError("A new absolute source staging directory is required")
    names = {
        path for path in reference.blobs
        if path.startswith(("MC1Services/Sources/", "MeshCore/Sources/")) and path.endswith(".swift")
    } | {"MC1Services/Package.swift", "MeshCore/Package.swift", "LICENSE", "MeshCore/LICENSE"}
    sources = reference.read_many(names)
    destination.mkdir(parents=True)
    records = []
    for name in sorted(names):
        original = sources[name].encode("utf8")
        output = original
        adaptations = []
        if name == "MeshCore/Package.swift":
            text = original.decode("utf8")
            if text.count(DOCC) != 1:
                raise OracleError("Pinned documentation-only SwiftPM dependency fragment changed")
            text = text.replace(DOCC, "")
            output = text.encode("utf8")
            adaptations.append({
                "purpose": "Omit unused documentation build plugin; no external unpinned fetch or product-source change",
                "original_fragment_sha256": sha256(DOCC.encode("utf8")),
                "replacement": "empty package dependency list",
            })
        elif name == "MC1Services/Package.swift":
            # Keep the original local MeshCore production dependency. The isolated XCTest
            # harness replaces only the test-target path, not original test expectations.
            text = original.decode("utf8")
            target = '.testTarget(\n      name: "MC1ServicesTests",\n      dependencies: ['
            if text.count(target) != 1:
                raise OracleError("Pinned MC1Services test-target declaration changed")
            old_end = '.product(name: "MeshCoreTestSupport", package: "MeshCore")\n      ]\n    )'
            new_end = '.product(name: "MeshCoreTestSupport", package: "MeshCore")\n      ],\n      path: "InteropTests"\n    )'
            if text.count(old_end) != 1:
                raise OracleError("Pinned test target dependency ending changed")
            output = text.replace(old_end, new_end).encode("utf8")
            adaptations.append({
                "purpose": "Isolated owned XCTest interop target; 171 frozen Swift declarations remain specification, not claimed executed",
                "original_fragment_sha256": sha256(old_end.encode("utf8")),
                "replacement_sha256": sha256(new_end.encode("utf8")),
            })
        target_path = destination.joinpath(*name.split("/"))
        write_or_check(target_path, output, check=False)
        records.append({**reference.provenance(name, sources[name]),
                        "staged_sha256": sha256(output), "adaptations": adaptations})
    # The original manifest references this local test-support product. Its exact source
    # is staged without executing the unrelated original MeshCore test target.
    support = {path for path in reference.blobs if path.startswith("MeshCore/Tests/MeshCoreTestSupport/") and path.endswith(".swift")}
    support_sources = reference.read_many(support)
    for name in sorted(support):
        raw = support_sources[name].encode("utf8")
        write_or_check(destination.joinpath(*name.split("/")), raw, check=False)
        records.append({**reference.provenance(name, support_sources[name]), "staged_sha256": sha256(raw), "adaptations": []})
    # Package.swift still declares its original test target; an empty path is not valid.
    # Retain every frozen MeshCore test file rather than fabricate a placeholder test.
    mesh_tests = {path for path in reference.blobs if path.startswith("MeshCore/Tests/MeshCoreTests/") and path.endswith(".swift")}
    mesh_sources = reference.read_many(mesh_tests)
    for name in sorted(mesh_tests):
        raw = mesh_sources[name].encode("utf8")
        write_or_check(destination.joinpath(*name.split("/")), raw, check=False)
        records.append({**reference.provenance(name, mesh_sources[name]), "staged_sha256": sha256(raw), "adaptations": []})
    harness = REPO.joinpath(*HARNESS.split("/")).read_bytes().replace(b"\r\n", b"\n")
    write_or_check(destination / "MC1Services" / "InteropTests" / "WP203InteropTests.swift", harness, check=False)
    result = {
        "schema_version": 1, "source_sha": SOURCE_SHA, "manifest_sha256": reference.manifest_sha256,
        "generator": "tools/android-port/oracle/wp203_interop.py", "production_sources": records,
        "harness": {"path": HARNESS, "sha256": sha256(harness)},
        "scope": "real frozen production sources, local test-support and explicit temporary package/test-target adaptations",
    }
    return result


def verify_swift_xml(path, execution_log=None):
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 8 * 1024 * 1024:
        raise OracleError("Missing, unsafe or oversized actual Swift XML")
    with path.open("rb") as stream:
        raw = stream.read(8 * 1024 * 1024 + 1)
    normalized = raw.replace(b"\0", b"").upper()
    if len(raw) > 8 * 1024 * 1024 or b"<!DOCTYPE" in normalized or b"<!ENTITY" in normalized:
        raise OracleError("Unsafe or oversized actual Swift XML")
    try:
        root = ET.fromstring(raw)
    except ET.ParseError as cause:
        raise OracleError("Malformed actual Swift XML") from cause
    cases = root.findall(".//testcase") if root.tag != "testcase" else [root]
    if len(cases) != 1:
        raise OracleError("Actual Swift interop report must discover exactly one mandatory XCTest")
    case = cases[0]
    name = case.get("name", "").removesuffix("()")
    if name != TEST_NAME or not case.get("classname", "").endswith("WP203InteropTests"):
        raise OracleError("Swift XML is stale, unrelated or a proxy case")
    if any(case.find(kind) is not None for kind in ("failure", "error", "skipped")):
        raise OracleError("Actual SwiftData restore test failed or skipped")
    for suite in root.iter("testsuite"):
        direct = suite.findall("testcase")
        if direct:
            for key, expected in (("tests", len(direct)), ("failures", 0), ("errors", 0), ("skipped", 0)):
                if key == "skipped" and key not in suite.attrib and execution_log is not None:
                    continue
                if key not in suite.attrib or int(suite.attrib[key]) != expected:
                    raise OracleError("Swift XML count/outcome disagreement")
    result = {"test": name, "discovered": 1, "passed": 1, "failed": 0, "skipped": 0, "xml_sha256": sha256(raw)}
    if execution_log is not None:
        if execution_log.is_symlink() or not execution_log.is_file() or execution_log.stat().st_size > 52_428_800:
            raise OracleError("Missing/unsafe/oversized actual XCTest execution trace")
        log_raw = execution_log.read_bytes()
        log = log_raw.decode("utf8")
        passed = re.findall(r"Test Case '[^'\n]*\b" + re.escape(TEST_NAME) + r"[^'\n]*' passed", log)
        if len(passed) != 1 or not re.search(r"Executed 1 test, with 0 failures \(0 unexpected\)", log):
            raise OracleError("Actual XCTest trace lacks the mandatory unskipped passed test")
        if re.search(r"Test Case '[^'\n]*\b" + re.escape(TEST_NAME) + r"[^'\n]*' skipped", log):
            raise OracleError("Actual XCTest test was skipped")
        result["execution_log_sha256"] = sha256(log_raw)
    return result


def execute(command, environment, log):
    log.parent.mkdir(parents=True, exist_ok=True)
    with log.open("w", encoding="utf8") as stream:
        completed = subprocess.run(command, env=environment, stdout=stream, stderr=subprocess.STDOUT,
                                   check=False, timeout=1500)
    if completed.returncode:
        raise OracleError("Real Swift command failed: " + log.read_text(encoding="utf8", errors="replace")[-12000:])


def validate_swift_bundle(root):
    evidence = load_json(root / "wp203-evidence.json")
    if set(evidence) != {"schema_version", "stage", "source_sha", "tasks", "data", "junit"}:
        raise OracleError("Malformed backup stage evidence")
    if evidence["schema_version"] != 1 or evidence["stage"] != "swift" or evidence["source_sha"] != SOURCE_SHA:
        raise OracleError("Stale/unknown backup stage")
    if evidence["data"] != [record(root, name) for name in DATA_NAMES["swift"]]:
        raise OracleError("Backup data hashes/sizes do not match actual files")
    actual = verify_swift_xml(root / "swift-results.xml", root / "swift-restore-export.log")
    if load_json(root / "swift-results.json") != actual or evidence["junit"] != actual or evidence["tasks"] != TASK_DESCRIPTION:
        raise OracleError("Missing actual SwiftData restore assertion evidence")
    return evidence


def run(incoming, output, stage_root):
    if platform.system() != "Darwin":
        raise OracleError("Full source SwiftData interoperability requires isolated macOS; Windows staging is not execution")
    if output.exists() or not output.is_absolute():
        raise OracleError("New absolute Swift evidence output required")
    validate_producer_data(incoming)
    output.mkdir(parents=True)
    source_map = stage_package(FrozenReference(), stage_root)
    write_or_check(output / "swift-source-map.json", json_bytes(source_map), check=False)
    reference = run_reference_codec(output / "reference-codec")
    if reference["result"] != "passed":
        raise OracleError("Actual existing frozen codec oracle did not pass")
    environment = mac_environment()
    compiler = shutil.which("swift", path=environment.get("PATH"))
    if compiler is None:
        raise OracleError("Actual SwiftPM toolchain is missing")
    execute([compiler, "--version"], environment, output / "swift-version.log")
    version = (output / "swift-version.log").read_text(encoding="utf8")
    match = re.search(r"Swift version (\d+)\.(\d+)", version)
    if not match or tuple(map(int, match.groups())) < (6, 2):
        raise OracleError("Frozen sources require Swift 6.2+")
    environment["WP203_KOTLIN_INPUT"] = str(incoming / "kotlin-export.meshcoreone")
    environment["WP203_SWIFT_OUTPUT"] = str(output)
    xml = output / "swift-results.xml"
    command = [compiler, "test", "--package-path", str(stage_root / "MC1Services"), "--filter", "WP203InteropTests",
               "--parallel", "--num-workers", "1", "--disable-swift-testing", "--verbose",
               "--xunit-output", str(xml), "--scratch-path", str(stage_root / "swift-build")]
    execution_log = output / "swift-restore-export.log"
    execute(command, environment, execution_log)
    result = verify_swift_xml(xml, execution_log)
    write_or_check(output / "swift-results.json", json_bytes(result), check=False)
    proof = load_json(output / "swift-room-proof.json")
    if (proof.get("source_sha") != SOURCE_SHA or proof.get("inserted") != 12 or proof.get("skipped") != 0
            or proof.get("reimport_inserted") != 0 or proof.get("restore_semantics_compared") is not True
            or proof.get("preferences_restored") is not True or len(proof.get("source_arrays", [])) != 12):
        raise OracleError("Actual source SwiftData restore/count/preference proof disagrees")
    evidence = {
        "schema_version": 1, "stage": "swift", "source_sha": SOURCE_SHA,
        "tasks": TASK_DESCRIPTION, "data": [record(output, name) for name in DATA_NAMES["swift"]], "junit": result,
    }
    write_or_check(output / "wp203-evidence.json", json_bytes(evidence), check=False)
    validate_swift_bundle(output)
    return evidence


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--stage-root", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        result = run(args.input, args.output, args.stage_root)
        print(json_bytes({"stage": result["stage"], "result": "passed"}).decode(), end="")
        return 0
    except (PortError, OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        print("BLOCKED: " + str(error), file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
