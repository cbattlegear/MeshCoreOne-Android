"""WP-204 bounded independent publisher artifact/POM/license evidence; never changes Gradle admission."""

import argparse
from datetime import datetime, timezone
import hashlib
import io
import json
from pathlib import Path
import re
import struct
import subprocess
import sys
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

OUT = Path(__file__).resolve().parent
ROOT = OUT.parents[3]
DECLARATION = OUT / "dependency-admission.json"
OUTPUT = OUT / "publisher-inputs.json"
PUBLISHERS = {
    "androidx.datastore": "https://dl.google.com/dl/android/maven2/",
    "com.squareup.okio": "https://repo.maven.apache.org/maven2/",
    "org.jetbrains.kotlinx": "https://repo.maven.apache.org/maven2/",
}
MAX_METADATA_BYTES = 256 * 1024
MAX_BINARY_BYTES = 8 * 1024 * 1024
MAX_NOTICE_BYTES = 256 * 1024
NAMESPACE = {"pom": "http://maven.apache.org/POM/4.0.0"}
NOTICE_ASSETS = ROOT / "android" / "core" / "datastore" / "src" / "main" / "assets" / "licenses" / "WP-204"
APP_PROTOBUF_NOTICE = ROOT / "android" / "app" / "src" / "main" / "assets" / "licenses" / "DataStore-Protobuf-BSD-3-Clause.txt"
OKIO_SOURCE_SHA256 = "6fe4b3204ad884cdaf52be1e87933dba3a6f7deb135cc44e1181b7f49a3d5414"
OKIO_LICENSE_URL = "https://raw.githubusercontent.com/lysine-dev/okio/8b870e8eaacecb1c1ceffbbb47246112604a1f92/LICENSE.txt"
OKIO_LICENSE_SHA256 = "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30"
PROTOBUF_LICENSE_URL = "https://raw.githubusercontent.com/protocolbuffers/protobuf/9fff46d7327c699ef970769d5c9fd0e44df08fc7/LICENSE"
PROTOBUF_LICENSE_SHA256 = "6e5e117324afd944dcf67f36cf329843bc1a92229a8cd9bb573d7a83130fea7d"
PROTOBUF_RUNTIME_CLASS = "androidx/datastore/preferences/protobuf/RuntimeVersion.class"
EXPECTED_NATIVE_ABIS = {"arm64-v8a", "armeabi-v7a", "x86", "x86_64"}
sys.path.insert(0, str(ROOT / "tools" / "android-port"))
from portlib.errors import PortError
NOTICE_PINS = {
    "datastore-core-android-1.2.1.aar": (
        "META-INF/androidx/datastore/datastore-core/LICENSE.txt",
        "AndroidX-DataStore-LICENSE.txt", "809fa1ed21450f59827d1e9aec720bbc4b687434fa22283c6cb5dd82a47ab9c0",
    ),
    "datastore-preferences-external-protobuf-1.2.1.jar": (
        "META-INF/androidx/datastore/datastore-preferences-external-protobuf/LICENSE.txt",
        "DataStore-Protobuf-BSD-3-Clause.txt", "a01b712fbb30f80b86851468b6fa58e80eab68f69f325e88bbc7ddf47cf1b063",
    ),
}


class PublicationFailure(ValueError):
    pass


def require(condition, description):
    if not condition:
        raise PublicationFailure(description)


def fetch(url, maximum):
    require(any(url.startswith(root) for root in PUBLISHERS.values()) or url in (OKIO_LICENSE_URL, PROTOBUF_LICENSE_URL),
            "Undeclared publisher")
    request = urllib.request.Request(url, headers={"User-Agent": "MeshCoreOne-WP204-publication-evidence"})
    with urllib.request.urlopen(request, timeout=60) as response:
        require(any(response.geturl().startswith(root) for root in PUBLISHERS.values()) or
                response.geturl() in (OKIO_LICENSE_URL, PROTOBUF_LICENSE_URL),
                "Unexpected publisher redirect")
        raw = response.read(maximum + 1)
    require(0 < len(raw) <= maximum, "Missing/oversized publication")
    return raw


def hashes(raw):
    return {"bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest(), "sha1": hashlib.sha1(raw).hexdigest()}


def published_metadata(url):
    raw = fetch(url, MAX_METADATA_BYTES)
    publisher_sha1 = fetch(url + ".sha1", 256).decode("ascii").strip()
    require(re.fullmatch(r"[0-9a-f]{40}", publisher_sha1) is not None, "Malformed publisher checksum")
    require(hashes(raw)["sha1"] == publisher_sha1, "Independent publisher checksum mismatch")
    return raw, {**hashes(raw), "url": url, "publisher_sha1_matched": True}


def pom_data(raw):
    require(b"<!DOCTYPE" not in raw.upper() and b"<!ENTITY" not in raw.upper(), "Unsafe publisher XML")
    root = ET.fromstring(raw)
    licenses = [
        {name: row.findtext("pom:" + name, namespaces=NAMESPACE) for name in ("name", "url", "distribution")}
        for row in root.findall("pom:licenses/pom:license", NAMESPACE)
    ]
    require(licenses, "Publisher POM has no applicable license declaration")
    dependencies = []
    for row in root.findall("pom:dependencies/pom:dependency", NAMESPACE):
        dependencies.append({
            name: row.findtext("pom:" + name, namespaces=NAMESPACE)
            for name in ("groupId", "artifactId", "version", "scope", "type", "optional")
        })
    return {
        "group": root.findtext("pom:groupId", namespaces=NAMESPACE),
        "name": root.findtext("pom:artifactId", namespaces=NAMESPACE),
        "version": root.findtext("pom:version", namespaces=NAMESPACE),
        "licenses": licenses,
        "dependencies": dependencies,
        "scm_url": root.findtext("pom:scm/pom:url", namespaces=NAMESPACE),
    }


def archived_notices(raw):
    notices = []

    def inspect(archive_raw, container):
        with zipfile.ZipFile(io.BytesIO(archive_raw)) as archive:
            for entry in archive.infolist():
                name = entry.filename
                if not entry.is_dir() and re.search(r"(?:^|/)(?:[^/]*[-_])?(?:LICENSE|NOTICE|COPYING|COPYRIGHT)[^/]*$", name, re.I):
                    require(entry.file_size <= MAX_NOTICE_BYTES, "Oversized bundled license/notice")
                    value = archive.read(entry)
                    require(len(value) == entry.file_size, "Bundled notice size mismatch")
                    notices.append({"archive": container, "path": name, **hashes(value)})
            if container == "artifact" and "classes.jar" in archive.namelist():
                entry = archive.getinfo("classes.jar")
                require(entry.file_size <= MAX_BINARY_BYTES, "Oversized nested AAR code archive")
                inspect(archive.read(entry), "artifact!classes.jar")

    inspect(raw, "artifact")
    return notices


def static_class_constants(raw):
    require(10 <= len(raw) <= MAX_METADATA_BYTES and raw[:4] == b"\xca\xfe\xba\xbe", "Malformed static version class")
    offset = 8

    def take(size):
        nonlocal offset
        require(0 <= size <= len(raw) - offset, "Truncated static class metadata")
        value = raw[offset:offset + size]
        offset += size
        return value

    def number(fmt, size):
        return struct.unpack(fmt, take(size))[0]

    count = number(">H", 2)
    pool = [None] * count
    index = 1
    while index < count:
        tag = number(">B", 1)
        if tag == 1:
            pool[index] = ("utf8", take(number(">H", 2)).decode("utf8"))
        elif tag == 3:
            pool[index] = ("int", number(">i", 4))
        elif tag == 8:
            pool[index] = ("string", number(">H", 2))
        elif tag in (4, 9, 10, 11, 12, 17, 18):
            take(4)
        elif tag in (5, 6):
            take(8)
            index += 1
        elif tag in (7, 16, 19, 20):
            take(2)
        elif tag == 15:
            take(3)
        else:
            raise PublicationFailure("Unsupported static class constant-pool tag")
        index += 1
    take(6)
    take(number(">H", 2) * 2)
    constants = {}
    for _ in range(number(">H", 2)):
        take(2)
        name = pool[number(">H", 2)]
        take(2)
        require(name is not None and name[0] == "utf8", "Malformed static version field")
        for _ in range(number(">H", 2)):
            attribute = pool[number(">H", 2)]
            size = number(">I", 4)
            if attribute == ("utf8", "ConstantValue"):
                require(size == 2, "Malformed static constant attribute")
                value = pool[number(">H", 2)]
                require(value is not None and value[0] in ("int", "string"), "Unsupported static version value")
                if value[0] == "string":
                    value = pool[value[1]]
                    require(value is not None and value[0] == "utf8", "Malformed static string constant")
                constants[name[1]] = value[1]
            else:
                take(size)
    expected = {"OSS_MAJOR": 4, "OSS_MINOR": 28, "OSS_PATCH": 2, "OSS_SUFFIX": ""}
    require(all(constants.get(key) == value for key, value in expected.items()), "Shaded protobuf runtime version drift")
    return expected


def native_libraries(raw):
    sys.path.insert(0, str(ROOT / "android" / "scaffold"))
    from apk_alignment import elf_load_alignment

    libraries = []
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        for entry in archive.infolist():
            if entry.filename.startswith("jni/") and entry.filename.endswith(".so"):
                require(entry.file_size <= MAX_BINARY_BYTES, "Oversized native input")
                data = archive.read(entry)
                libraries.append({
                    "aar_path": entry.filename, **hashes(data),
                    "elf_pt_load_alignment": elf_load_alignment(data),
                    "physical_device_or_native_load_verified": False,
                })
    require(
        {row["aar_path"] for row in libraries} ==
        {f"jni/{abi}/libdatastore_shared_counter.so" for abi in EXPECTED_NATIVE_ABIS},
        "Actual pinned DataStore native artifact set drift",
    )
    return libraries


def collect():
    declaration = json.loads(DECLARATION.read_text(encoding="utf8"))
    modules = declaration["independent_publisher_module_sha256"]
    binaries = declaration["independent_publisher_binary_sha256"]
    results = []
    actual_binaries = {}
    notices = {}
    protobuf_runtime = None
    datastore_native = None
    for coordinate, pinned_digest in modules.items():
        group, name, version = coordinate.split(":")
        require(group in PUBLISHERS, "Unknown declared component group")
        require(version == {
            "androidx.datastore": "1.2.1", "com.squareup.okio": "3.9.1", "org.jetbrains.kotlinx": "1.7.3",
        }[group], "Version drift")
        base = PUBLISHERS[group] + group.replace(".", "/") + "/" + name + "/" + version + "/"
        prefix = name + "-" + version
        module_raw, module_record = published_metadata(base + prefix + ".module")
        require(module_record["sha256"] == pinned_digest, "Frozen independent module pin mismatch: " + coordinate)
        module = json.loads(module_raw)
        pom_raw, pom_record = published_metadata(base + prefix + ".pom")
        pom = pom_data(pom_raw)
        require(pom["group"] == group and pom["name"] == name and pom["version"] == version, "POM coordinate mismatch")
        artifacts = {}
        selected_variants = []
        for variant in module["variants"]:
            attributes = variant["attributes"]
            if attributes.get("org.gradle.category") != "library" or attributes.get("org.gradle.usage") not in ("java-api", "java-runtime"):
                continue
            if attributes.get("org.gradle.jvm.environment") not in ("android", "standard-jvm", None):
                continue
            if attributes.get("org.jetbrains.kotlin.platform.type") not in ("jvm", None):
                continue
            selected_variants.append({
                "name": variant["name"], "attributes": attributes,
                "available_at": variant.get("available-at"), "dependencies": variant.get("dependencies", []),
                "dependency_constraints": variant.get("dependencyConstraints", []),
            })
            for artifact in variant.get("files", []):
                filename = artifact["url"]
                require("/" not in filename and "\\" not in filename and artifact["size"] <= MAX_BINARY_BYTES,
                        "Unsupported artifact path/size")
                require(filename in binaries, "Unexpected linked binary requires separate admission: " + filename)
                require(artifact["sha256"] == binaries[filename], "Frozen independent binary pin mismatch")
                artifacts[filename] = artifact
        verified = []
        for filename, artifact in artifacts.items():
            binary = fetch(base + filename, artifact["size"])
            actual = hashes(binary)
            require(actual["bytes"] == artifact["size"] and actual["sha256"] == artifact["sha256"] and actual["sha1"] == artifact["sha1"],
                    "Actual downloaded binary differs from publisher module: " + filename)
            publisher_sha1 = fetch(base + filename + ".sha1", 256).decode("ascii").strip()
            require(publisher_sha1 == actual["sha1"], "Binary independent publisher SHA-1 mismatch")
            record = {
                "gradle_artifact_name": artifact["name"], "publisher_filename": filename,
                "url": base + filename, **actual, "publisher_sha1_matched": True,
                "publisher_module_sha256_matched": True, "bundled_notices": archived_notices(binary),
            }
            verified.append(record)
            actual_binaries[filename] = actual["sha256"]
            if filename == "datastore-core-android-1.2.1.aar":
                datastore_native = native_libraries(binary)
                record["static_native_libraries"] = datastore_native
            elif filename == "datastore-preferences-external-protobuf-1.2.1.jar":
                with zipfile.ZipFile(io.BytesIO(binary)) as archive:
                    runtime = archive.read(PROTOBUF_RUNTIME_CLASS)
                protobuf_runtime = {
                    "jar_entry": PROTOBUF_RUNTIME_CLASS, **hashes(runtime),
                    "static_constant_value_fields": static_class_constants(runtime),
                    "artifact_code_executed": False,
                }
                record["static_protobuf_runtime_version"] = protobuf_runtime
            if filename in NOTICE_PINS:
                source_path, destination, expected_notice = NOTICE_PINS[filename]
                with zipfile.ZipFile(io.BytesIO(binary)) as archive:
                    notice = archive.read(source_path)
                require(hashes(notice)["sha256"] == expected_notice, "Frozen actual notice mismatch")
                notice_path = APP_PROTOBUF_NOTICE if filename == "datastore-preferences-external-protobuf-1.2.1.jar" else NOTICE_ASSETS / destination
                notices[notice_path] = notice
        results.append({
            "coordinate": coordinate, "module": module_record, "pom": {**pom_record, **pom},
            "android_jvm_variants": selected_variants, "verified_binaries": verified,
        })
    require(actual_binaries == binaries, "Every frozen linked binary must actually be downloaded and checked")
    require(protobuf_runtime is not None and datastore_native is not None, "Missing actual native/protobuf provenance")
    bom_url = PUBLISHERS["org.jetbrains.kotlinx"] + "org/jetbrains/kotlinx/kotlinx-serialization-bom/1.7.3/kotlinx-serialization-bom-1.7.3.pom"
    bom_raw, bom_metadata = published_metadata(bom_url)
    require(bom_metadata["sha256"] == declaration["existing_serialization_bom_pom_sha256"], "Observed serialization BOM drift")
    bom_root = ET.fromstring(bom_raw)
    bom_metadata["managed_dependencies"] = [
        {name: row.findtext("pom:" + name, namespaces=NAMESPACE) for name in ("groupId", "artifactId", "version")}
        for row in bom_root.findall("pom:dependencyManagement/pom:dependencies/pom:dependency", NAMESPACE)
    ]
    require(all(row["version"] == "1.7.3" for row in bom_metadata["managed_dependencies"]),
            "Observed serialization BOM has an unapproved version constraint")
    protobuf_license = fetch(PROTOBUF_LICENSE_URL, MAX_NOTICE_BYTES)
    require(len(protobuf_license) == 1732 and hashes(protobuf_license)["sha256"] == PROTOBUF_LICENSE_SHA256,
            "Version-bound protobuf copyright/license drift")
    require(b"Copyright 2008 Google Inc.  All rights reserved." in protobuf_license, "Missing upstream protobuf copyright")
    notices[NOTICE_ASSETS / "Protobuf-v28.2-Copyright-LICENSE.txt"] = protobuf_license
    source = fetch(
        PUBLISHERS["com.squareup.okio"] + "com/squareup/okio/okio-jvm/3.9.1/okio-jvm-3.9.1-sources.jar",
        179241,
    )
    require(len(source) == 179241 and hashes(source)["sha256"] == OKIO_SOURCE_SHA256, "Pinned Okio source archive mismatch")
    with zipfile.ZipFile(io.BytesIO(source)) as archive:
        copyrights = set()
        for entry in archive.infolist():
            if entry.filename.endswith(".kt"):
                require(entry.file_size <= MAX_METADATA_BYTES, "Oversized source license-header input")
                text = archive.read(entry).decode("utf8")
                copyrights.update({
                    line.strip(" /*\t") for line in text.splitlines()[:24] if "Copyright" in line
                })
    require(copyrights, "Missing actual Okio source copyright notices")
    license = fetch(OKIO_LICENSE_URL, MAX_NOTICE_BYTES)
    require(len(license) == 11358 and hashes(license)["sha256"] == OKIO_LICENSE_SHA256, "Pinned Okio license mismatch")
    notices[NOTICE_ASSETS / "Okio-Apache-2.0.txt"] = license
    notices[NOTICE_ASSETS / "Okio-Copyrights.txt"] = ("\n".join(sorted(copyrights)) + "\n").encode("utf8")
    evidence = {
        "schema_version": 1, "work_package": "WP-204", "lease": "autonomous-WP-204-dc15f1ba",
        "cli_app_session": "00cbcd6f-1b3b-4e40-8d44-2ce4216eb72e",
        "native_project_alias": "13956b48-f450-42b5-8c63-778fae11425d",
        "observed_head_sha": subprocess.check_output(["git", "-C", str(ROOT), "rev-parse", "HEAD"], text=True).strip(),
        "observed_at": datetime.now(timezone.utc).isoformat(),
        "scope": "actual independently corroborated publisher metadata, binary and license declarations; "
                 "not Gradle graph resolution, strict admission, legal approval, executed tests or hardware proof",
        "counts": {"components": len(results), "publisher_poms": len(results), "publisher_modules": len(results),
                   "actual_linked_binaries": len(actual_binaries)},
        "components": results,
        "existing_serialization_bom": bom_metadata,
        "source_license_inputs": {
            "okio_sources_jar_sha256": OKIO_SOURCE_SHA256, "okio_sources_jar_bytes": len(source),
            "okio_annotated_tag": "3.9.1",
            "okio_tag_object_sha": "72e78197060101af851bf914777cac02b5df1eb1",
            "okio_license_commit": "8b870e8eaacecb1c1ceffbbb47246112604a1f92",
            "okio_license_url": OKIO_LICENSE_URL, "okio_license_blob": "d645695673349e3947e8e5ae42332d0ac3164cd7",
            "okio_license_sha256": OKIO_LICENSE_SHA256,
            "okio_tag_signature_verified": False,
            "observed_repository_redirect": "The POM's square/okio GitHub origin redirected to lysine-dev/okio; "
                                            "the exact annotated tag/commit/license bytes were frozen, not silently advanced",
            "actual_okio_copyright_lines": sorted(copyrights),
            "protobuf_runtime_version": "4.28.2",
            "protobuf_runtime_version_evidence": protobuf_runtime,
            "protobuf_upstream_tag": "v28.2",
            "protobuf_upstream_tag_object": "e6ab258b7ca407ed1bad8a2f04971e72b16f5409",
            "protobuf_upstream_commit": "9fff46d7327c699ef970769d5c9fd0e44df08fc7",
            "protobuf_upstream_license_blob": "19b305b00060a774a9180fb916c14b49edb2008f",
            "protobuf_upstream_license_url": PROTOBUF_LICENSE_URL,
            "protobuf_upstream_license_sha256": PROTOBUF_LICENSE_SHA256,
            "protobuf_upstream_license_bytes": len(protobuf_license),
            "protobuf_upstream_tag_signature_verified": False,
            "protobuf_embedded_notice_limit": "The exact1434-byte publisher BSD notice omits its copyright header; "
                                              "retain it unchanged plus the exact1732-byte version-bound upstream Google copyright/license.",
        },
        "actual_datastore_native_libraries": datastore_native,
        "generated_notice_assets": [
            {"path": path.relative_to(ROOT).as_posix(), **hashes(raw)}
            for path, raw in sorted(notices.items())
        ],
        "remaining_obligations": [
            "Independent coordinator admission and exact transitive strict graph/lock configuration proof",
            "DataStore's relocated protobuf binary requires preservation of actual protobuf license/notice, not just an Apache POM label",
            "Linked Kotlin/coroutines/serialization versions remain governed by the unchanged existing catalog/locks",
            "No global provider replacement, GMS requirement, or network requirement in production persistence",
        ],
    }
    return evidence, notices


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true")
    parser.add_argument("--write-notices", action="store_true")
    parser.add_argument("--check-notices", action="store_true")
    arguments = parser.parse_args()
    try:
        evidence, notices = collect()
        if arguments.write:
            OUTPUT.write_text(json.dumps(evidence, indent=2, ensure_ascii=True) + "\n", encoding="utf8")
        if arguments.write_notices:
            for path, raw in notices.items():
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(raw)
        if arguments.check_notices or arguments.write_notices:
            for path, raw in notices.items():
                require(path.read_bytes() == raw, "Missing/changed actual dependency notice asset")
            require(set(NOTICE_ASSETS.iterdir()) == {path for path in notices if path.parent == NOTICE_ASSETS},
                    "Unexpected generated module notice output")
        print(json.dumps({"result": "publisher-inputs-verified", "counts": evidence["counts"],
                          "scope": evidence["scope"]}, indent=2))
        return 0
    except (PortError, PublicationFailure, OSError, ValueError, KeyError, IndexError, UnicodeError, struct.error, ET.ParseError, zipfile.BadZipFile,
            subprocess.SubprocessError, urllib.error.URLError) as failure:
        print(f"BLOCKED: {failure}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
