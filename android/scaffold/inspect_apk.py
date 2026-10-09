"""Android-only WP-002 inspect the actual debug APK, not a device/install/release claim."""

import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import zipfile


ANDROID = Path(__file__).resolve().parents[1]


def inspect_apk():
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        raise ValueError("ANDROID_HOME/ANDROID_SDK_ROOT is required")
    apk = ANDROID / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
    aapt = Path(sdk) / "build-tools" / "37.0.0" / ("aapt2.exe" if os.name == "nt" else "aapt2")
    badging = subprocess.run(
        [str(aapt), "dump", "badging", str(apk)], capture_output=True, text=True, check=True, timeout=30,
    ).stdout
    package = re.search(r"^package: name='([^']+)'", badging, re.MULTILINE)
    minimum = re.search(r"^(?:minSdkVersion|sdkVersion):'(\d+)'", badging, re.MULTILINE)
    target = re.search(r"^targetSdkVersion:'(\d+)'", badging, re.MULTILINE)
    if package is None or package.group(1) != "com.meshcoreone.android.debug":
        raise ValueError("APK package is not the required debug identity")
    if minimum is None or int(minimum.group(1)) != 31 or target is None or int(target.group(1)) != 37:
        raise ValueError("APK min/target SDK is not exactly 31/37")
    if "launchable-activity: name='com.meshcoreone.android.MainActivity'" not in badging:
        raise ValueError("Actual APK launcher is missing")
    permissions = re.findall(r"^uses-permission: name='([^']+)'", badging, re.MULTILINE)
    # WP-303 adds optional coarse location before WP-206 connectivity permissions (manifest order).
    expected = [
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.CAMERA",
        "android.permission.BLUETOOTH_CONNECT",
        "android.permission.BLUETOOTH_SCAN",
        "android.permission.REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE",
        "android.permission.FOREGROUND_SERVICE",
        "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.INTERNET",
        "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.CHANGE_NETWORK_STATE",
        "android.permission.ACCESS_LOCAL_NETWORK",
        "com.meshcoreone.android.debug.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
    ]
    if len(permissions) != len(expected) or set(permissions) != set(expected):
        raise ValueError(f"Unexpected merged permissions: {permissions}")
    if "uses-permission: name='android.permission.BLUETOOTH_SCAN' usesPermissionFlags='neverForLocation'" not in badging:
        raise ValueError("BLUETOOTH_SCAN must be declared neverForLocation")
    with zipfile.ZipFile(apk) as archive:
        if archive.testzip() is not None:
            raise ValueError("APK archive CRC validation failed")
        notices = ["assets/licenses/GPL-3.0.txt", "assets/licenses/MeshCore-MIT.txt", "assets/licenses/Apache-2.0.txt"]
        for notice in notices:
            source = ANDROID / "app" / "src" / "main" / Path(notice)
            if archive.read(notice) != source.read_bytes():
                raise ValueError(f"Pinned notice absent/changed in APK: {notice}")
        for name in archive.namelist():
            if re.fullmatch(r"classes\d*\.dex", name):
                dex = archive.read(name)
                if b"com/meshcoreone/android/scaffold/roomverification" in dex:
                    raise ValueError("Verification fixture leaked into the APK")
                if b"com/meshcoreone/android/core/testing" in dex:
                    raise ValueError("Test helpers leaked into the APK")
        native_libraries = [name for name in archive.namelist() if name.startswith("lib/") and name.endswith(".so")]
    with apk.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    return {
        "scope": "actual local debug APK; no physical device, upgrade, release signature or feature parity claim",
        "artifact": "android/app/build/outputs/apk/debug/app-debug.apk",
        "sha256": digest,
        "size_bytes": apk.stat().st_size,
        "package": package.group(1),
        "min_sdk": int(minimum.group(1)),
        "target_sdk": int(target.group(1)),
        "permissions": permissions,
        "pinned_notices": notices,
        "verification_fixture_packaged": False,
        "native_libraries": native_libraries,
        "native_16kb_compatibility_verified": False,
    }


if __name__ == "__main__":
    try:
        print(json.dumps(inspect_apk(), indent=2))
    except (OSError, ValueError, KeyError, zipfile.BadZipFile, subprocess.SubprocessError) as error:
        print(f"BLOCKED: {error}", file=sys.stderr)
        sys.exit(2)
