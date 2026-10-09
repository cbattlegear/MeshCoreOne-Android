import argparse
import json
import re
from pathlib import Path

from controller.errors import PortError
from controller.capabilities import CapabilityEngine
from controller.model import load_manifest
from controller.paths import permits
from controller.paths import git_path
from controller.schema import digest, load_json
from controller.verification_config import content_scope_predecessor, VERIFICATION_MANIFEST_SHA256

HEADER = re.compile(r"^// PortedFrom: (.+)@([0-9a-f]{40})$", re.MULTILINE)
ANDROID_ONLY = re.compile(r"^// AndroidOnly: (WP-\d{3}) (.+)$", re.MULTILINE)
GENERATED = re.compile(r"^// GeneratedFrom: (.+)$", re.MULTILINE)

WP_302_SCOPE_PATHS = (
    "android/app/src/main/kotlin/com/meshcoreone/android/MainActivity.kt",
    "android/app/src/test/kotlin/com/meshcoreone/android/app/navigation/",
)
WP_302_SCOPE_PROOF = "docs/android/evidence/WP-302/navigation-scope-admission.json"
WP_302_SCOPE_APPROVAL = {
    "schema_version": 1,
    "repository": "cbattlegear/MeshCoreOne-Android",
    "work_package": "WP-302",
    "coordinator_session": "bcb17a74-5fa6-47d0-a4be-b6b595e20559",
    "source_sha": "db14559b39d32322b06477c6ae676112f583db50",
    "receiver_base_sha": "67857474ef3d3012ab73c19a4943ec1334c20676",
    "manifest_sha256": VERIFICATION_MANIFEST_SHA256,
    "write_paths": list(WP_302_SCOPE_PATHS),
    "authority": "User-directed approved necessary WP-302 launcher and unit-navigation support",
    "acceptance": "Ownership/traceability admission only; no source, parity, human or merge acceptance",
}

SUPPORT_SCOPE_RECEIPT = "support-scope.json"
SUPPORT_SCOPE_FIELDS = {
    "schema_version", "repository", "work_package", "source_sha", "base_sha",
    "session_id", "paths",
}


def capability_support_admitted(manifest, wp_id, relative):
    receipt_path = manifest.repo / "docs" / "android" / "evidence" / wp_id / SUPPORT_SCOPE_RECEIPT
    if not receipt_path.is_file():
        return False
    receipt = load_json(receipt_path, 64 * 1024)
    if not isinstance(receipt, dict) or set(receipt) != SUPPORT_SCOPE_FIELDS:
        raise PortError(f"Malformed capability support receipt: {receipt_path.relative_to(manifest.repo)}")
    if (receipt["schema_version"] != 1 or receipt["repository"] != "cbattlegear/MeshCoreOne-Android"
            or receipt["work_package"] != wp_id
            or receipt["source_sha"] != manifest.data["reference"]["commit"]
            or not isinstance(receipt["base_sha"], str) or not re.fullmatch(r"[0-9a-f]{40}", receipt["base_sha"])
            or not isinstance(receipt["session_id"], str) or not receipt["session_id"]):
        raise PortError(f"Stale capability support receipt: {receipt_path.relative_to(manifest.repo)}")
    paths = receipt["paths"]
    if not isinstance(paths, list) or not paths:
        raise PortError(f"Empty capability support receipt: {receipt_path.relative_to(manifest.repo)}")
    matching = []
    for row in paths:
        if not isinstance(row, dict) or set(row) != {"path", "operation", "capability"}:
            raise PortError(f"Malformed capability support path: {receipt_path.relative_to(manifest.repo)}")
        if row["path"] == relative:
            matching.append(row)
    if not matching:
        return False
    policy = load_json(manifest.repo / "docs" / "android" / "automation-policy.json", 512 * 1024)
    engine = CapabilityEngine(policy)
    for row in matching:
        admission = engine.admit(wp_id, manifest.wp(wp_id)["write_paths"], row["path"], row["operation"])
        if admission.capability_id != row["capability"] or admission.capability_id == "wp-owned":
            raise PortError(f"Incorrect capability support binding: {relative}")
    return True


def navigation_scope_admitted(manifest, relative):
    if not (relative == WP_302_SCOPE_PATHS[0] or relative.startswith(WP_302_SCOPE_PATHS[1])):
        return False
    predecessor = content_scope_predecessor(manifest)
    proof = load_json(manifest.repo / WP_302_SCOPE_PROOF, 16 * 1024)
    if predecessor.sha256 != VERIFICATION_MANIFEST_SHA256 or digest(proof) != digest(WP_302_SCOPE_APPROVAL):
        raise PortError("Missing/stale exact WP-302 launcher/unit-navigation scope approval")
    return True


def port_map(manifest):
    directory = manifest.repo / "android"
    if not directory.exists():
        return []
    known = {e["path"] for e in manifest.data["inventory"]}
    results = []
    for path in sorted(directory.rglob("*.kt")):
        relative = path.relative_to(manifest.repo).as_posix()
        if any(part in ("build", ".gradle") for part in path.relative_to(directory).parts):
            continue
        content = path.read_text(encoding="utf-8")
        sources = HEADER.findall(content)
        android_only = ANDROID_ONLY.findall(content)
        generated = GENERATED.findall(content)
        if not sources and not android_only and not generated:
            raise PortError(f"Missing traceability header: {relative}")
        if sources and (android_only or generated):
            raise PortError(f"Conflicting traceability dispositions: {relative}")
        if len(set(sources)) != len(sources):
            raise PortError(f"Duplicate source header: {relative}")
        for source, sha in sources:
            if source not in known or sha != manifest.data["reference"]["commit"]:
                raise PortError(f"Unknown/stale source provenance: {relative}")
        for wp_id, reason in android_only:
            declared = permits(manifest.wp(wp_id)["write_paths"], relative)
            nav_allowed = wp_id == "WP-302" and not declared and navigation_scope_admitted(manifest, relative)
            capability_allowed = not declared and not nav_allowed and capability_support_admitted(manifest, wp_id, relative)
            if not reason.strip() or not (declared or nav_allowed or capability_allowed):
                raise PortError(f"Invalid Android-only owner/write path: {relative}")
        for declaration in generated:
            generator, separator, declared_inputs = declaration.partition("; inputs: ")
            if not separator or not declared_inputs.strip():
                raise PortError(f"Generated output must name generator and pinned inputs: {relative}")
            git_path(generator)
            if not generator.startswith("tools/android-port/") or not (manifest.repo / Path(generator)).is_file():
                raise PortError(f"Unknown generated-output generator: {relative}")
            for value in declared_inputs.split(", "):
                source, separator, sha = value.rpartition("@")
                if not separator or source not in known or sha != manifest.data["reference"]["commit"]:
                    raise PortError(f"Unknown/stale generated input: {relative}")
        results.append({
            "implementation": relative,
            "sources": [source for source, _ in sources],
            "android_only": [wp_id for wp_id, _ in android_only],
            "generated_inputs": generated,
            "feature_acceptance": "not established by headers",
        })
    return results


def main():
    parser = argparse.ArgumentParser(description="Derive many-to-many provenance without editing shared progress")
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[2])
    args = parser.parse_args()
    try:
        print(json.dumps(port_map(load_manifest(args.repo)), indent=2))
    except (PortError, OSError) as error:
        parser.exit(2, f"BLOCKED: {error}\n")


if __name__ == "__main__":
    main()
