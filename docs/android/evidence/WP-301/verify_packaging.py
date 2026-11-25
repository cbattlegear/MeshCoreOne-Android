"""WP-301 exact palette/app notices and actual APK boundaries; not legal/device/signing acceptance."""

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import plistlib
import subprocess
import sys
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[4]
MODULE = ROOT / "android" / "core" / "designsystem"
PIN = json.loads((ROOT / "docs" / "android" / "port-manifest.json").read_text(encoding="utf-8"))["reference"]["commit"]


def frozen(path):
    return subprocess.check_output(["git", "-C", str(ROOT), "show", PIN + ":" + path])


def notices():
    result = {"assets/licenses/WP-301/GPL-3.0-app.txt": frozen("LICENSE")}
    for name in ("Catppuccin", "Nord", "Solarized"):
        source = "MC1/Settings.bundle/Packages/" + name + ".plist"
        entries = plistlib.loads(frozen(source))["PreferenceSpecifiers"]
        text = [value["FooterText"] for value in entries if "FooterText" in value]
        if len(text) != 1 or "Permission is hereby granted" not in text[0]:
            raise ValueError("Missing original palette notice: " + source)
        result["assets/licenses/WP-301/" + name + "-MIT.txt"] = text[0].encode("utf8")
    return result


def normalize(path, expected, write):
    actual = path.read_bytes()
    if actual == expected:
        return
    if actual.replace(b"\r\n", b"\n") != expected:
        raise ValueError("Notice content changed, not an admitted Git line-ending adaptation: " + path.name)
    if not write:
        raise ValueError("Notice bytes are CRLF; run the owned --normalize pre-build hook")
    path.write_bytes(expected)
    if path.read_bytes() != expected:
        raise ValueError("Notice byte normalization did not persist")


def verify_notices(write=False):
    result = []
    for path, expected in notices().items():
        target = MODULE / "src" / "main" / Path(path)
        normalize(target, expected, write)
        result.append({"path": path, "bytes": len(expected), "sha256": hashlib.sha256(expected).hexdigest()})
    return result


def inspect(apk):
    spec = importlib.util.spec_from_file_location("wp301_scaffold_apk_reader", ROOT / "android" / "scaffold" / "inspect_apk.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    expected_apk = ROOT / "android" / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
    if apk.resolve() != expected_apk.resolve():
        raise ValueError("Root APK identity reader must inspect the actual current debug artifact")
    base = module.inspect_apk()
    expected = notices()
    with zipfile.ZipFile(apk) as archive:
        if archive.testzip() is not None:
            raise ValueError("APK CRC mismatch")
        for path, data in expected.items():
            if archive.read(path) != data:
                raise ValueError("Actual APK notice absent/changed: " + path)
        mapping = json.loads(archive.read("assets/theme-source-map.json"))
        if mapping["reference_commit"] != PIN or len(mapping["primary_owned_inputs"]) != 89 or len(mapping["color_assets"]) != 44:
            raise ValueError("Actual APK palette/source map is missing or stale")
        fixture_markers = (b"ThemeComposeTest", b"ThemeServiceTest", b"AndroidAppearance37Test", b"theme-source-inputs.json")
        for name in archive.namelist():
            if name.endswith(".dex"):
                data = archive.read(name)
                if any(marker in data for marker in fixture_markers):
                    raise ValueError("Native test fixture/class leaked into production dex")
        if any(name.endswith("theme-source-inputs.json") or "theme-platform-sdks" in name for name in archive.namelist()):
            raise ValueError("Raw test oracle or simulated SDK leaked into the APK")
    return {**base, "theme_notices": verify_notices(), "theme_primary_inputs": 89, "named_theme_colors": 44,
            "theme_test_fixtures_packaged": False}


class NoticeReaderTest(unittest.TestCase):
    def test_only_byte_equivalent_crlf_is_normalized(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / "notice.txt"
            path.write_bytes(b"MIT\r\nCopyright\r\n")
            normalize(path, b"MIT\nCopyright\n", True)
            self.assertEqual(b"MIT\nCopyright\n", path.read_bytes())

    def test_changed_content_is_not_silently_repaired(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / "notice.txt"
            path.write_bytes(b"wrong license\r\n")
            with self.assertRaises(ValueError):
                normalize(path, b"MIT\n", True)
            self.assertEqual(b"wrong license\r\n", path.read_bytes())

    def test_check_mode_does_not_write(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / "notice.txt"
            path.write_bytes(b"MIT\r\n")
            with self.assertRaises(ValueError):
                normalize(path, b"MIT\n", False)
            self.assertEqual(b"MIT\r\n", path.read_bytes())

    def test_all_four_notices_come_from_actual_frozen_inputs(self):
        values = notices()
        self.assertEqual(4, len(values))
        self.assertEqual(frozen("LICENSE"), values["assets/licenses/WP-301/GPL-3.0-app.txt"])
        self.assertIn(b"Copyright (c) 2021 Catppuccin", values["assets/licenses/WP-301/Catppuccin-MIT.txt"])
        self.assertIn(b"Ethan Schoonover", values["assets/licenses/WP-301/Solarized-MIT.txt"])
        self.assertIn(b"Sven Greb", values["assets/licenses/WP-301/Nord-MIT.txt"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--normalize", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--apk", type=Path)
    args = parser.parse_args()
    try:
        if args.self_test:
            suite = unittest.defaultTestLoader.loadTestsFromTestCase(NoticeReaderTest)
            result = unittest.TextTestRunner(verbosity=1).run(suite)
            if not result.wasSuccessful() or result.testsRun == 0 or result.skipped:
                raise ValueError("Missing/failed/skipped notice-reader assertions")
        results = verify_notices(args.normalize)
        value = inspect(args.apk) if args.apk else {"result": "passed", "scope": "exact frozen notice bytes only", "notices": results}
        print(json.dumps(value, sort_keys=True))
        return 0
    except (OSError, ValueError, KeyError, zipfile.BadZipFile, subprocess.SubprocessError) as error:
        print("BLOCKED: " + str(error), file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
