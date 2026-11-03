"""AndroidOnly: WP-203 Read-only-token native backup producer/consumer; never a gate publisher."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from controller.ci import execute, execution_identity, preflight
from controller.ci_environment import candidate_environment, verify_wrapper
from controller.ci_evidence import suite_case_ids, suite_counts
from controller.errors import PortError
from controller.gates import Binding
from controller.module_junit import report_record
from controller.schema import load_json
from oracle.reference import OracleError, REPO, SOURCE_SHA, json_bytes

TASKS = (
    ":core:data:verifyBackupTests",
    ":core:data:verifyPersistenceRepositoryTests",
    ":core:database:testDebugUnitTest",
    "validateModuleGraph",
)
LOCK_TASK = (":core:data:dependencies", "--write-locks")
DATA_NAMES = {
    "producer": ("kotlin-export.meshcoreone", "kotlin-export.json", "kotlin-room-proof.json"),
    "consumer": ("kotlin-restored.json", "swift-to-kotlin-room-proof.json"),
    "swift": ("swift-export.meshcoreone", "swift-export.json", "swift-room-proof.json", "swift-results.json", "swift-source-map.json",
              "swift-results.xml", "swift-restore-export.log"),
}
MAXIMUM_DATA_BYTES = 52_428_800
SWIFT_HANDOFF_DATA = ("swift-export.meshcoreone", "swift-export.json", "swift-room-proof.json")


def identity():
    value = execution_identity()
    if value is None:
        raise OracleError("WP-203 pipeline requires actual hosted event/run identity")
    binding = dict(value["binding"])
    binding["work_package"] = "WP-203"
    Binding.parse(binding)
    return {"binding": binding, "run_id": value["run_id"], "run_attempt": value["run_attempt"]}


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


def swift_data_handoff(root, bound):
    return {
        "schema_version": 1, "identity": bound, "source_sha": SOURCE_SHA,
        "data": [record(root, name) for name in SWIFT_HANDOFF_DATA],
    }


def validate_swift_handoff(root, bound):
    path = root / "swift-handoff.json"
    digest_file(path)
    handoff = load_json(path)
    if (not isinstance(handoff, dict) or type(handoff.get("schema_version")) is not int
            or handoff != swift_data_handoff(root, bound)):
        raise OracleError("Swift data handoff differs from the exact head/base/source/run/attempt or bytes")
    proof = load_json(root / "swift-room-proof.json")
    if (not isinstance(proof, dict) or proof.get("source_sha") != SOURCE_SHA
            or proof.get("producer") != "actual-frozen-SwiftData-restore-and-AppBackupService-export"
            or type(proof.get("inserted")) is not int or proof["inserted"] != 12
            or type(proof.get("skipped")) is not int or proof["skipped"] != 0
            or type(proof.get("reimport_inserted")) is not int or proof["reimport_inserted"] != 0
            or proof.get("restore_semantics_compared") is not True
            or proof.get("preferences_restored") is not True):
        raise OracleError("Swift data handoff lacks actual frozen SwiftData restore/export observations")
    return proof


def consume_swift_data(state, output, incoming):
    if state["host"] != "linux":
        raise OracleError("The declared interoperability native jobs use ephemeral Linux x64")
    if output.exists() or not output.is_absolute():
        raise OracleError("A new absolute native output directory is required")
    bound = identity()
    validate_swift_handoff(incoming, bound)
    output.mkdir(parents=True)
    preflight(state, output)
    verify_wrapper()
    environment = candidate_environment(state)
    command = [
        str(REPO / "android" / "gradlew"), "-p", str(REPO / "android"), *TASKS,
        "--no-daemon", "--console=plain", "--dependency-verification", "strict",
        "--max-workers=1", "-Pkotlin.compiler.execution.strategy=in-process", "-PscaffoldTestHeap=512m",
        "--project-cache-dir", str(Path(state["private_root"]) / "wp203-consumer-project"),
        "-Pwp203InteropInputDir=" + str(incoming.resolve()),
        "--no-build-cache", "--rerun-tasks", "--quiet",
    ]
    execute(command, environment, output / "native-consumer.log", stream_output=True)
    reports = REPO / "android" / "core" / "data" / "build" / "test-results" / "testDebugUnitTest"
    discovery = suite_counts(reports, 1)
    database_discovery = suite_counts(
        REPO / "android" / "core" / "database" / "build" / "test-results" / "testDebugUnitTest", 1,
    )
    required = {
        ("com.meshcoreone.android.core.data.backup.BackupInteropTest", method)
        for method in (
            "actualRoomKotlinExportProducesAllFamiliesForSwift",
            "actualSwiftExportRestoresIntoRealRoomAndMatchesSourceSemantics",
        )
    }
    if not required.issubset({(case["class"], case["method"]) for case in suite_case_ids(reports)}):
        raise OracleError("Actual native runner omitted the mandatory backup interoperability assertions")
    produced = REPO / "android" / "core" / "data" / "build" / "reports" / "wp203" / "interop"
    digest_file(produced / "swift-to-kotlin-room-proof.json")
    proof = load_json(produced / "swift-to-kotlin-room-proof.json")
    if (not isinstance(proof, dict) or proof.get("consumer") != "actual-Swift-export-Room-restore"
            or proof.get("compressedSha256") != digest_file(incoming / "swift-export.meshcoreone")
            or type(proof.get("inserted")) is not int or proof["inserted"] != 12
            or type(proof.get("reimportInserted")) is not int or proof["reimportInserted"] != 0
            or proof.get("sourceSemanticsCompared") is not True or proof.get("preferencesRestored") is not True):
        raise OracleError("Actual Swift-to-Room restore observations disagree with the handed-off bytes")
    return {
        "stage": "consumer", "identity": bound, "result": "passed",
        "discovery": {"core/data": discovery, "core/database": database_discovery}, "restore": proof,
    }



def validate_bundle(root, stage, expected_identity):
    if stage == "producer" and not (root / "wp203-evidence.json").exists():
        return validate_producer_data(root)
    evidence = load_json(root / "wp203-evidence.json")
    if set(evidence) != {"schema_version", "stage", "identity", "source_sha", "tasks", "data", "junit"}:
        raise OracleError("Malformed backup stage evidence")
    if evidence["schema_version"] != 1 or evidence["stage"] != stage or evidence["source_sha"] != SOURCE_SHA:
        raise OracleError("Stale/unknown backup stage")
    if evidence["identity"] != expected_identity:
        raise OracleError("Cross-direction input is not the same head/base/run/attempt")
    if evidence["data"] != [record(root, name) for name in DATA_NAMES[stage]]:
        raise OracleError("Backup data hashes/sizes do not match actual files")
    if stage != "swift":
        if evidence["tasks"] != list(TASKS):
            raise OracleError("Native backup input does not declare the real mandatory tasks")
        actual = report_record(root, root / "junit")
        if evidence["junit"] != actual:
            raise OracleError("Native backup XML disagrees with actual discovery and bytes")
    else:
        from oracle.wp203_interop import TASK_DESCRIPTION, verify_swift_xml
        report = load_json(root / "swift-results.json")
        actual = verify_swift_xml(root / "swift-results.xml", root / "swift-restore-export.log")
        if report != actual or evidence["junit"] != actual or evidence["tasks"] != TASK_DESCRIPTION:
            raise OracleError("Missing actual SwiftData restore assertion evidence")
    return evidence


def retain_native_xml(output, bound):
    source = REPO / "android" / "core" / "data" / "build" / "test-results" / "testDebugUnitTest"
    destination = output / "junit"
    destination.mkdir(exist_ok=True)
    retained = []
    for path in sorted(source.glob("TEST-*.xml")):
        if path.is_symlink() or not path.is_file() or path.stat().st_size > 8 * 1024 * 1024:
            raise OracleError("Unsafe or oversized raw native report retention")
        target = destination / path.name
        shutil.copyfile(path, target)
        if digest_file(path) != digest_file(target):
            raise OracleError("Raw native XML changed during retention")
        retained.append(record(destination, path.name))
    (output / "raw-native-retention.json").write_bytes(json_bytes({
        "identity": bound, "reports": retained,
        "scope": "verbatim raw XML retained even after runner failure; not a passing outcome",
    }))
    return destination


def native(stage, state, output, incoming=None):
    if state["host"] != "linux":
        raise OracleError("The declared interoperability native jobs use ephemeral Linux x64")
    if output.exists() or not output.is_absolute():
        raise OracleError("A new absolute native evidence directory is required")
    output.mkdir(parents=True)
    bound = identity()
    if stage == "consumer":
        if incoming is None:
            raise OracleError("Actual Swift artifact directory is required")
        validate_bundle(incoming, "swift", bound)
    preflight(state, output)
    verify_wrapper()
    environment = candidate_environment(state)
    wrapper = REPO / "android" / "gradlew"
    options = [
        "--no-daemon", "--console=plain", "--dependency-verification", "strict",
        "--max-workers=1", "-Pkotlin.compiler.execution.strategy=in-process", "-PscaffoldTestHeap=512m",
        "--project-cache-dir", str(Path(state["private_root"]) / "wp203-project-root"), "--quiet",
    ]
    if stage == "producer":
        execute([str(wrapper), "-p", str(REPO / "android"), *LOCK_TASK, *options], environment, output / "owner-lock-generation.log")
        owner_lock = REPO / "android" / "core" / "data" / "gradle.lockfile"
        shutil.copyfile(owner_lock, output / "data-gradle.lockfile")
        changed = subprocess.check_output(
            ["git", "--no-pager", "-C", str(REPO), "diff", "--name-only", "--", "android"], text=True).splitlines()
        if any(path != "android/core/data/gradle.lockfile" for path in changed):
            raise OracleError("Owner lock generation changed an unadmitted Android input")
        (output / "owner-lock-generation.json").write_bytes(json_bytes({
            "identity": bound, "tasks": list(LOCK_TASK), "artifact": record(output, "data-gradle.lockfile"),
            "changed": changed, "scope": "mechanical admitted module-local dependency state, not passing native assertions",
        }))
        if changed:
            raise OracleError("Generated owner-local lock must be committed before exact-head native acceptance")
    command = [str(wrapper), "-p", str(REPO / "android"), *TASKS, *options, "--no-build-cache", "--rerun-tasks"]
    if incoming:
        command.append("-Pwp203InteropInputDir=" + str(incoming.resolve()))
    try:
        execute(command, environment, output / ("native-" + stage + ".log"))
    finally:
        retain_native_xml(output, bound)
    actual_reports = report_record(output, output / "junit")
    produced = REPO / "android" / "core" / "data" / "build" / "reports" / "wp203" / "interop"
    for name in DATA_NAMES[stage]:
        digest_file(produced / name)
        shutil.copyfile(produced / name, output / name)
    collector = REPO / "docs" / "android" / "evidence" / "WP-203" / "collect_evidence.py"
    execute([sys.executable, str(collector), "--output", str(output / "native-source-evidence.json")], None, output / "source-reader.log")
    source = load_json(output / "native-source-evidence.json")
    if source["binding_state"] != "exact-committed-head" or len(source["original_cases"]) != 171:
        raise OracleError("Native original-case evidence is not exact committed-head proof")
    evidence = {
        "schema_version": 1, "stage": stage, "identity": bound, "source_sha": SOURCE_SHA, "tasks": list(TASKS),
        "data": [record(output, name) for name in DATA_NAMES[stage]], "junit": actual_reports,
    }
    (output / "wp203-evidence.json").write_bytes(json_bytes(evidence))
    validate_bundle(output, stage, bound)
    return evidence


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("stage", choices=("producer", "consumer"))
    parser.add_argument("--state", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--input", type=Path)
    args = parser.parse_args(argv)
    try:
        if args.stage == "consumer":
            if args.input is None:
                raise OracleError("Actual Swift data handoff directory is required")
            result = consume_swift_data(load_json(args.state), args.output, args.input)
        else:
            result = native(args.stage, load_json(args.state), args.output, args.input)
            result = {"stage": result["stage"], "result": "passed", "identity": result["identity"]}
        print(json_bytes(result).decode(), end="")
        return 0
    except (PortError, OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        print("BLOCKED: " + str(error), file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
