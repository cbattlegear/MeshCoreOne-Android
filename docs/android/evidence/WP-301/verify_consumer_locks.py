# AndroidOnly: WP-301 Verify its admitted consumer-lock baseline survives later work-package additions.
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path, PurePosixPath
import subprocess
import sys
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[4]
BASE = "2cf00464950e1fb9aae0dd913402eb3e12dc0044"
MODULES = {
    ":core:ui", ":core:maps", ":feature:onboarding", ":feature:chats", ":feature:nodes",
    ":feature:remotenodes", ":feature:map", ":feature:tools", ":feature:settings", ":platform:widgets",
}
CONFIGURATIONS = {
    "debugRuntimeClasspath", "releaseRuntimeClasspath", "debugUnitTestRuntimeClasspath",
    "debugAndroidTestRuntimeClasspath", "debugLintChecksClasspath", "releaseLintChecksClasspath",
    "debugUnitTestLintChecksClasspath", "debugAndroidTestLintChecksClasspath",
}
ADDITIONS = {
    "androidx.datastore:" + name + ":1.2.1" for name in (
        "datastore", "datastore-android", "datastore-core", "datastore-core-android",
        "datastore-core-okio", "datastore-core-okio-jvm", "datastore-preferences",
        "datastore-preferences-android", "datastore-preferences-core",
        "datastore-preferences-core-android", "datastore-preferences-external-protobuf",
        "datastore-preferences-proto",
    )
} | {
    "com.squareup.okio:okio:3.9.1", "com.squareup.okio:okio-jvm:3.9.1",
    "org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3",
    "org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.7.3",
}
REPLACEMENTS = {
    "androidx.activity:activity-compose:1.8.2": "androidx.activity:activity-compose:1.13.0",
    "androidx.activity:activity-ktx:1.8.2": "androidx.activity:activity-ktx:1.13.0",
    "androidx.activity:activity:1.8.2": "androidx.activity:activity:1.13.0",
    "androidx.core:core-ktx:1.16.0": "androidx.core:core-ktx:1.18.0",
    "androidx.core:core:1.16.0": "androidx.core:core:1.18.0",
    "androidx.lifecycle:lifecycle-common-jvm:2.8.7": "androidx.lifecycle:lifecycle-common-jvm:2.9.4",
    "androidx.lifecycle:lifecycle-common:2.8.7": "androidx.lifecycle:lifecycle-common:2.9.4",
    "androidx.lifecycle:lifecycle-runtime-android:2.8.7": "androidx.lifecycle:lifecycle-runtime-android:2.9.4",
    "androidx.lifecycle:lifecycle-runtime-compose-android:2.8.7":
        "androidx.lifecycle:lifecycle-runtime-compose-android:2.9.4",
    "androidx.lifecycle:lifecycle-runtime-compose:2.8.7":
        "androidx.lifecycle:lifecycle-runtime-compose:2.9.4",
    "androidx.lifecycle:lifecycle-runtime-ktx-android:2.8.7":
        "androidx.lifecycle:lifecycle-runtime-ktx-android:2.9.4",
    "androidx.lifecycle:lifecycle-runtime-ktx:2.8.7": "androidx.lifecycle:lifecycle-runtime-ktx:2.9.4",
    "androidx.lifecycle:lifecycle-runtime:2.8.7": "androidx.lifecycle:lifecycle-runtime:2.9.4",
    "androidx.tracing:tracing:1.2.0": "androidx.tracing:tracing:1.3.0",
    "com.google.guava:listenablefuture:1.0":
        "com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava",
}


def require(condition, message):
    if not condition:
        raise ValueError("WP-301 consumer locks: " + message)


def parse_lock(text):
    result, coordinates, empty = {}, set(), set()
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split("=")
        require(len(parts) == 2, "malformed lock record")
        coordinate, names = parts
        components = coordinate.split(":")
        require(coordinate == "empty" or (len(components) == 3 and all(components)
            and not any(character.isspace() for character in coordinate)), "malformed component")
        require(coordinate not in coordinates, "duplicate component record")
        coordinates.add(coordinate)
        configurations = names.split(",")
        require(all(configurations) and len(set(configurations)) == len(configurations), "invalid configuration list")
        for name in configurations:
            require(name == name.strip(), "invalid configuration whitespace")
            components = result.setdefault(name, set())
            if coordinate == "empty":
                require(not components, "empty configuration contains a component")
                empty.add(name)
            else:
                require(name not in empty, "component configuration declared empty")
                components.add(coordinate)
    require(result, "empty lock state")
    return result


def verify_delta(before, after, admitted):
    require(set(admitted) == CONFIGURATIONS, "missing or extra admitted configuration")
    require(all(len(value) == 16 and set(value) == ADDITIONS for value in admitted.values()), "changed admitted additions")
    for name, original in before.items():
        require(name in after, "generated lock removed existing configuration")
        missing = original - after[name]
        require(all(REPLACEMENTS.get(component) in after[name] for component in missing),
            "generated lock removed or changed existing state")
    for name, additions in admitted.items():
        require(name in before and set(additions) <= after[name], "admitted addition is absent from generated lock")


def git(*arguments):
    return subprocess.check_output(["git", "-C", str(ROOT), *arguments])


def check():
    request = json.loads(Path(__file__).with_name("dependency-amendment-request.json").read_text(encoding="utf8"))
    require(request["initial_base"] == BASE and request["source_graph_rows"] == 12704, "changed admission basis")
    modules = request["modules"]
    require(len(modules) == 10 and {record["module"] for record in modules} == MODULES, "changed module scope")
    metadata = ET.parse(ROOT / "android" / "gradle" / "verification-metadata.xml")
    verified = {
        ":".join(component.attrib[key] for key in ("group", "name", "version"))
        for component in metadata.iter() if component.tag.split("}")[-1] == "component"
        and any(child.tag.split("}")[-1] in {"sha256", "sha512"} for child in component.iter())
    }
    require(ADDITIONS <= verified, "added artifact lacks existing checksum admission")
    records = []
    for module in modules:
        expected_path = "android/gradle/dependency-locks/" + module["module"].removeprefix(":").replace(":", "-") + ".lockfile"
        require(module["path"] == expected_path, "changed lock path")
        original = git("show", BASE + ":" + expected_path)
        require(git("rev-parse", BASE + ":" + expected_path).decode().strip() == module["initial_blob"], "changed original blob")
        configurations = module["configurations"]
        require(len(configurations) == 8, "missing or duplicate admission records")
        require(all(not item["removed_vs_initial"] for item in configurations), "removal is not admitted")
        admitted = {item["configuration"]: item["added_vs_initial"] for item in configurations}
        current = ROOT.joinpath(*PurePosixPath(expected_path).parts).read_bytes()
        verify_delta(parse_lock(original.decode("utf8")), parse_lock(current.decode("utf8")), admitted)
        records.append({
            "path": expected_path, "original_blob": module["initial_blob"],
            "current_sha256": hashlib.sha256(current).hexdigest(), "configurations": 8, "added_components": 16,
        })
    return {"result": "passed", "modules": 10, "configurations": 80, "added_components": 16, "locks": records}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    try:
        require(args.check or args.self_test, "verification mode required")
        if args.self_test:
            path = Path(__file__).with_name("test_consumer_locks.py")
            require(path.is_file(), "missing lock-reader test suite")
            spec = importlib.util.spec_from_file_location("wp301_consumer_lock_tests", path)
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
            suite = unittest.defaultTestLoader.loadTestsFromModule(module)
            require(suite.countTestCases() > 0, "zero lock-reader tests")
            tested = unittest.TextTestRunner(verbosity=1).run(suite)
            require(tested.wasSuccessful() and not tested.skipped, "failed/skipped lock-reader tests")
        if args.check:
            print(json.dumps(check(), sort_keys=True))
        return 0
    except (ValueError, OSError, KeyError, ET.ParseError, subprocess.CalledProcessError) as error:
        print("BLOCKED: " + str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
