"""AndroidOnly: WP-000 Regression coverage for #541 capability glob matching."""

import copy
from fnmatch import fnmatchcase
import json
import subprocess
import unittest

from fixtures import REPO
from controller.capabilities import CapabilityEngine
from controller.errors import PortError


TRUSTED_BASE = "99479711bfdb7c621765f09d671068ca8b8318ce"
ACCESSOR = (
    "android/core/l10n/src/main/kotlin/com/meshcoreone/android/core/l10n/"
    "generated/AppContactsStrings.kt"
)
RESOURCE = "android/app/src/main/res/values/platform_strings.xml"


class CapabilityMatchingTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.policy = json.loads(subprocess.check_output(
            ["git", "-C", str(REPO), "show", TRUSTED_BASE + ":docs/android/automation-policy.json"],
        ))

    def test_literal_subtrees_keep_root_descendants_and_segment_boundary(self):
        for pattern in ("android/app/**", "android/app//**", r"android\app\**"):
            for path in ("android/app", "android/app/Foo.kt", "android/app/nested/Foo.kt"):
                with self.subTest(pattern=pattern, path=path):
                    self.assertTrue(CapabilityEngine._matches(pattern, path))
            for path in ("android", "android/application/Foo.kt", "Android/app/Foo.kt", "other/android/app"):
                with self.subTest(pattern=pattern, path=path):
                    self.assertFalse(CapabilityEngine._matches(pattern, path))
        self.assertTrue(CapabilityEngine._matches(r"android\app\**", r"android\app\nested\Foo.kt"))

    def test_internal_wildcards_use_existing_fnmatch_semantics(self):
        for pattern in (
            "android/**/generated/**", "android/**/src/**/res/**",
            "android/core/*/generated/**", "android/feature/?/res/**",
            "android/[ac]pp/**", "android/[!x]pp/**",
            "tools/android-port/*convert*.py", "android/**/*.kt",
        ):
            for path in (
                ACCESSOR, RESOURCE, "android/core/l10n/generated/Foo.kt",
                "android/feature/a/res/values/x.xml", "android/app/res/value.xml",
                "android/cpp/nested/File.kt", "android/xpp/File.kt",
                "android/core/l10n/generated", "tools/android-port/convert_contacts.py",
                "android/app/Foo.kt", "android/app/Foo.xml", "other/generated/Foo.kt",
            ):
                with self.subTest(pattern=pattern, path=path):
                    self.assertEqual(CapabilityEngine._matches(pattern, path), fnmatchcase(path, pattern))
        self.assertTrue(CapabilityEngine._matches(r"android\**\generated\**", ACCESSOR.replace("/", "\\")))

    def test_real_generated_paths_admit_exact_operations_without_changing_obligations(self):
        before = copy.deepcopy(self.policy)
        engine = CapabilityEngine(self.policy)
        rule = next(r for r in self.policy["reservation_policy"]["capabilities"]
                    if r["id"] == "generated-resources")
        for path in (ACCESSOR, RESOURCE, "android/core/l10n/src/main/res/values-de/strings.xml"):
            for operation in ("update-generator-input", "run-generator", "replace-generated-output"):
                for wp in ("WP-302", "WP-005"):
                    with self.subTest(path=path, operation=operation, wp=wp):
                        admission = engine.admit(wp, [], path, operation)
                        self.assertEqual(admission.capability_id, "generated-resources")
                        self.assertEqual(admission.invariants, tuple(rule["invariants"]))
                        self.assertEqual(admission.validation, tuple(rule["validation"]))
        self.assertEqual(self.policy, before)

    def test_generated_neighbours_wrong_operations_and_unsafe_paths_stay_denied(self):
        engine = CapabilityEngine(self.policy)
        for path in (
            ACCESSOR.replace("/generated/", "/generated-other/"),
            RESOURCE.replace("/res/", "/resources/"),
            RESOURCE.replace("android/", "android-other/", 1),
            "MC1/generated/Foo.swift", "tools/android-port/capabilities.py",
        ):
            with self.subTest(path=path), self.assertRaises(PortError):
                engine.admit("WP-302", [], path, "replace-generated-output")
        for operation in ("modify", "delete", "create", "unknown"):
            with self.subTest(operation=operation), self.assertRaises(PortError):
                engine.admit("WP-302", [], ACCESSOR, operation)
        for path in (
            "../" + ACCESSOR, "/" + ACCESSOR,
            ACCESSOR.replace("/generated/", "/../generated/"),
            ACCESSOR.replace("/generated/", "//generated/"),
            ACCESSOR.replace("/generated/", "/./generated/"),
            ACCESSOR.replace("/generated/", "/.git/generated/"),
            ACCESSOR.replace("/generated/", "/CON/generated/"),
            ACCESSOR.replace("/", "\\"), ACCESSOR + "\n", ACCESSOR + "*",
        ):
            with self.subTest(path=path), self.assertRaises(PortError):
                engine.admit("WP-302", [], path, "replace-generated-output")

    def test_internal_glob_does_not_bypass_WP_operation_or_default_deny(self):
        policy = {"reservation_policy": {"capabilities": [{
            "id": "fixture-generated", "path_rules": ["android/**/generated/**"],
            "work_packages": ["WP-005"], "operations": ["run-generator"],
            "invariants": ["fixture-only"], "validation": ["fixture-only"],
        }]}}
        engine = CapabilityEngine(policy)
        self.assertEqual(engine.admit("WP-005", [], ACCESSOR, "run-generator").capability_id,
                         "fixture-generated")
        for wp, path, operation in (
            ("WP-302", ACCESSOR, "run-generator"),
            ("WP-005", ACCESSOR, "modify"),
            ("WP-005", RESOURCE, "run-generator"),
        ):
            with self.subTest(wp=wp, path=path, operation=operation), self.assertRaises(PortError):
                engine.admit(wp, [], path, operation)

    def test_manual_App_copy_uses_its_separate_non_generated_operation(self):
        admission = CapabilityEngine(self.policy).admit(
            "WP-302", [], RESOURCE, "add-direct-support-wiring",
        )
        self.assertEqual(admission.capability_id, "app-build-launcher")


if __name__ == "__main__":
    unittest.main()
