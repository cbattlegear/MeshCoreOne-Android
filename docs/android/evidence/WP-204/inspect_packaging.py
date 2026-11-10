"""WP-204 actual APK backup/config, dependency notices and test/secret exclusion; no device/hardware claim."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import unittest
import zipfile

OUT = Path(__file__).resolve().parent
ROOT = OUT.parents[3]
ANDROID = ROOT / "android"
APK = ANDROID / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
sys.path.insert(0, str(ROOT / "tools" / "android-port"))
from portlib.errors import PortError
REQUIRED_DOMAINS = {
    "root", "file", "database", "sharedpref", "external",
    "device_root", "device_file", "device_database", "device_sharedpref",
}
FORBIDDEN_DEX_MARKERS = (
    b"com/meshcoreone/android/core/datastore/TestKeystoreAccess",
    b"com/meshcoreone/android/core/datastore/StorageHarness",
    b"com/meshcoreone/android/core/datastore/RecordingReporter",
    b"com/meshcoreone/android/core/datastore/OriginalCase",
    b"com/meshcoreone/android/core/datastore/KeyGenerationSourceTest",
    b"WP204_FIXTURE_ONLY", b"WP204_TEST_ONLY_DO_NOT_LOG_PROVIDER_CAUSE",
    b"9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60",
)


def require(condition, description):
    if not condition:
        raise ValueError(description)


def xml_tree(aapt, name):
    result = subprocess.run(
        [str(aapt), "dump", "xmltree", str(APK), "--file", name],
        capture_output=True, text=True, encoding="utf8", check=True, timeout=30,
    )
    return result.stdout


def backup_configuration(manifest, resources, rules):
    android = re.escape("http://schemas.android.com/apk/res/android:")

    def attribute(name):
        values = re.findall(r"^\s*A: " + android + re.escape(name) + r"\(0x[0-9a-fA-F]+\)=(\S+)\s*$",
                            manifest, re.MULTILINE)
        require(len(values) == 1, "Missing/ambiguous actual Android manifest attribute: " + name)
        return values[0]

    require(attribute("allowBackup") == "false", "Actual APK does not disable automatic backup")
    extraction = attribute("dataExtractionRules")
    require(re.fullmatch(r"@0x[0-9a-fA-F]{8}", extraction) is not None, "Malformed actual data extraction resource")
    resource = re.findall(r"^\s*resource (0x[0-9a-fA-F]{8}) xml/scaffold_data_extraction_rules\s*$",
                          resources, re.MULTILINE)
    require(len(resource) == 1 and int(resource[0], 16) == int(extraction[1:], 16),
            "Manifest backup rule reference does not bind to the actual inspected compiled XML")
    found = {}
    section = None
    exclude = None
    for line in rules.splitlines():
        if "E: cloud-backup " in line:
            section = "cloud-backup"
            found[section] = []
        elif "E: device-transfer " in line:
            section = "device-transfer"
            found[section] = []
        elif "E: include " in line:
            raise ValueError("Actual APK has an unapproved automatic-backup include")
        elif "E: exclude " in line:
            require(section in found, "Malformed compiled extraction rule hierarchy")
            exclude = {}
            found[section].append(exclude)
        elif exclude is not None:
            match = re.search(r'\b(domain|path)="([^"]+)"', line)
            if match:
                exclude[match.group(1)] = match.group(2)
    require(set(found) == {"cloud-backup", "device-transfer"}, "Actual APK lacks both backup/transfer exclusions")
    for section, rows in found.items():
        require(len(rows) == len(REQUIRED_DOMAINS), "Unexpected compiled exclusion set: " + section)
        require({row.get("domain") for row in rows} == REQUIRED_DOMAINS and all(row.get("path") == "." for row in rows),
                "Actual compiled extraction rules do not exclude all storage domains")
    return extraction, found


def inspect():
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    require(sdk, "Declared SDK is required; this does not provision or install anything")
    aapt = Path(sdk) / "build-tools" / "37.0.0" / ("aapt2.exe" if os.name == "nt" else "aapt2")
    require(APK.is_file(), "Missing actual assembled debug APK")
    manifest = xml_tree(aapt, "AndroidManifest.xml")
    resources = subprocess.run(
        [str(aapt), "dump", "resources", str(APK)],
        capture_output=True, text=True, encoding="utf8", check=True, timeout=30,
    ).stdout
    rules = xml_tree(aapt, "res/xml/scaffold_data_extraction_rules.xml")
    extraction, found = backup_configuration(manifest, resources, rules)
    assets = ANDROID / "core" / "datastore" / "src" / "main" / "assets"
    notices = []
    publication = json.loads((OUT / "publisher-inputs.json").read_text(encoding="utf8"))
    expected_native = publication["actual_datastore_native_libraries"]
    require(len(expected_native) == 4, "Missing independent four-ABI native provenance")
    expected_notices = publication["generated_notice_assets"]
    require(len(expected_notices) == 5, "Missing actual version-bound dependency copyright/notice evidence")
    expected_module = {
        row["path"] for row in expected_notices if row["path"].startswith("android/core/datastore/")
    }
    require(len(expected_module) == 4 and
            any(row["path"] == "android/app/src/main/assets/licenses/DataStore-Protobuf-BSD-3-Clause.txt" for row in expected_notices),
            "Actual notice assets do not match the dependency notices listed in publisher-inputs.json")
    native = []
    sys.path.insert(0, str(ANDROID / "scaffold"))
    from apk_alignment import elf_load_alignment, inspect_alignment
    with zipfile.ZipFile(APK) as archive:
        require(archive.testzip() is None, "Actual APK archive CRC failure")
        files = sorted((assets / "licenses" / "WP-204").glob("*.txt"))
        require({file.relative_to(ROOT).as_posix() for file in files} == expected_module,
                "Missing/extra independently generated module notice inputs")
        for expected in expected_notices:
            file = ROOT.joinpath(*expected["path"].split("/"))
            entry = "assets/" + expected["path"].split("/src/main/assets/", 1)[1]
            raw = file.read_bytes()
            require(len(raw) == expected["bytes"] and
                    hashlib.sha256(raw).hexdigest() == expected["sha256"],
                    "Owned dependency notice differs from independent publication receipt")
            require(archive.read(entry) == raw, "Actual dependency notice missing/changed in APK")
            notices.append({"path": entry, "bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest()})
        production = False
        for name in archive.namelist():
            require(not name.endswith(("secrets.bin", "app.preferences_pb")), "A durable storage file was packaged in APK")
            if re.fullmatch(r"classes\d*\.dex", name):
                dex = archive.read(name)
                for marker in FORBIDDEN_DEX_MARKERS:
                    require(marker not in dex, "Test helper or known test-only secret material leaked into actual APK")
                production |= b"com/meshcoreone/android/core/datastore/FrameworkKeystoreKeyAccess" in dex
                require(b"com/meshcoreone/android/core/testing" not in dex, "Generic production test-helper leak")
        require(production, "Actual framework Keystore adapter is absent from debug APK")
        for provenance in expected_native:
            member = provenance["aar_path"].replace("jni/", "lib/", 1)
            data = archive.read(member)
            require(len(data) <= 8 * 1_048_576, "Oversized packaged DataStore native library")
            native.append({
                "apk_path": member, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest(),
                "elf_pt_load_alignment": elf_load_alignment(data),
                "publisher_aar_sha256": provenance["sha256"],
                "packaged_bytes_match_publisher": hashlib.sha256(data).hexdigest() == provenance["sha256"],
                "scope": "actual packaged native bytes; AGP may strip symbols, static ELF proof only",
            })
        require(
            {name for name in archive.namelist() if name.startswith("lib/") and name.endswith("/libdatastore_shared_counter.so")} ==
            {row["apk_path"] for row in native},
            "Actual packaged DataStore ABI set mismatch",
        )
    alignment = inspect_alignment(APK, Path(sdk))
    with APK.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    return {
        "schema_version": 1, "work_package": "WP-204",
        "apk": APK.relative_to(ROOT).as_posix(), "apk_sha256": digest, "apk_bytes": APK.stat().st_size,
        "allow_backup": False, "extraction_rule_resource": extraction[1:],
        "compiled_backup_exclusions": found, "credential_protected_no_backup_path_policy": "noBackupFilesDir/meshcoreone-datastore",
        "notice_assets": notices, "framework_keystore_adapter_packaged": True,
        "datastore_native_libraries": native, "apk_static_alignment": alignment,
        "physical_device_or_native_load_verified": False,
        "known_test_secret_material_packaged": False, "test_helpers_packaged": False, "durable_storage_files_packaged": False,
        "manifest_xml_sha256": hashlib.sha256(manifest.encode()).hexdigest(),
        "resource_table_sha256": hashlib.sha256(resources.encode()).hexdigest(),
        "extraction_xml_sha256": hashlib.sha256(rules.encode()).hexdigest(),
        "scope": "actual compiled debug APK/config/notice/test-marker inspection; not runtime plaintext/log forensics, "
                 "physical Android auto-backup/OEM transfer, secure hardware, credential upgrade or release signing",
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    try:
        if args.self_test:
            suite = unittest.defaultTestLoader.discover(str(OUT), pattern="test_packaging_parser.py")
            test_result = unittest.TextTestRunner(stream=sys.stdout, verbosity=1).run(suite)
            require(test_result.testsRun == 8 and test_result.wasSuccessful() and not test_result.skipped,
                    "Missing/failed/skipped packaging-parser assertions")
        result = inspect()
        if args.write:
            (OUT / "apk-storage-evidence.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf8")
        print(json.dumps(result, indent=2))
        return 0
    except (PortError, OSError, ValueError, KeyError, zipfile.BadZipFile, subprocess.SubprocessError) as failure:
        print(f"BLOCKED: {failure}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
