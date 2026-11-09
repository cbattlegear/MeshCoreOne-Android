"""AndroidOnly: WP-000 Exact WP-305 admission for the WP-502 onboarding fixture handoff."""

import copy
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from fixtures import REPO, policy, test_manifest
from controller.capabilities import CapabilityEngine
from controller.errors import PortError
from controller.scope_amendment import (
    ONBOARDING_PRESET_FIXTURE_CAPABILITY,
    routine_support_predecessor,
)


BASE = "2be1194f31dfdb2e1d90f988f5975ee7c179fe8e"
PATH = "android/ui-tests/src/test/kotlin/com/meshcoreone/android/uitests/OnboardingRegionPresetComposeTest.kt"
OPERATION = "add-or-update-related-test"
POLICY_PATH = "docs/android/automation-policy.json"


class OnboardingFixtureCapabilityTests(unittest.TestCase):
    def setUp(self):
        self.policy = policy()
        self.rule = ONBOARDING_PRESET_FIXTURE_CAPABILITY
        self.engine = CapabilityEngine(self.policy)
        self.manifest = test_manifest("WP-305")

    def original(self):
        return subprocess.check_output(["git", "-C", str(REPO), "show", f"{BASE}:{POLICY_PATH}"])

    def test_exact_contract_and_actual_predecessor_refusal(self):
        self.assertEqual(self.rule["work_packages"], ["WP-305"])
        self.assertEqual(self.rule["path_rules"], [PATH])
        self.assertEqual(self.rule["operations"], [OPERATION])
        self.assertEqual(self.policy["reservation_policy"]["capabilities"][-1], self.rule)
        admission = self.engine.admit("WP-305", self.manifest.wp("WP-305")["write_paths"], PATH, OPERATION)
        self.assertEqual(admission.capability_id, self.rule["id"])
        self.assertEqual(admission.invariants, tuple(self.rule["invariants"]))
        self.assertEqual(admission.validation, tuple(self.rule["validation"]))
        with self.assertRaisesRegex(PortError, "no trusted path\\+operation rule"):
            CapabilityEngine(json.loads(self.original())).admit(
                "WP-305", self.manifest.wp("WP-305")["write_paths"], PATH, OPERATION,
            )

    def test_other_wp_wrong_operations_and_primary_owner_unchanged(self):
        previous = CapabilityEngine(json.loads(self.original()))
        for wp in self.manifest.work_packages:
            assigned = self.manifest.wp(wp)["write_paths"]
            for operation in (OPERATION, "modify", "create", "test", "delete", "generate",
                              "repair-regional-label-localization", "modify-related-consumer", "unknown"):
                with self.subTest(wp=wp, operation=operation):
                    if (wp, operation) == ("WP-305", OPERATION):
                        continue
                    try:
                        old = previous.admit(wp, assigned, PATH, operation)
                    except PortError:
                        with self.assertRaises(PortError):
                            self.engine.admit(wp, assigned, PATH, operation)
                    else:
                        self.assertEqual(self.engine.admit(wp, assigned, PATH, operation), old)
        self.assertEqual(self.engine.admit(
            "WP-502", self.manifest.wp("WP-502")["write_paths"], PATH, "modify",
        ).capability_id, "wp-owned")

    def test_neighbors_source_sets_production_build_resources_and_traversal_denied(self):
        for path in (
            PATH.rsplit("/", 1)[0], PATH + ".backup", PATH + "/Other.kt",
            PATH.replace("OnboardingRegionPresetComposeTest", "OtherComposeTest"),
            PATH.replace("OnboardingRegionPresetComposeTest", "OnboardingRegionPresetComposeTestExtra"),
            PATH.replace("/test/", "/androidTest/"), PATH.replace("/test/", "/main/"),
            PATH.replace("/uitests/", "/uitests/../uitests/"), "../" + PATH, "/" + PATH,
            PATH.replace("android/ui-tests/", "android//ui-tests/"),
            "android/ui-tests/build.gradle.kts", "android/ui-tests/src/main/AndroidManifest.xml",
            "android/ui-tests/src/main/res/values/strings.xml", "MC1/OnboardingState.swift",
        ):
            with self.subTest(path=path), self.assertRaises(PortError):
                self.engine.admit("WP-305", [], path, OPERATION)

    def test_complete_raw_object_inverse_and_roundtrip_preserve_all_prior_rules(self):
        raw = (REPO / POLICY_PATH).read_text(encoding="utf-8")
        current = json.loads(raw)
        previous = copy.deepcopy(current)
        self.assertEqual(previous["reservation_policy"]["capabilities"].pop(), self.rule)
        original_raw = self.original()
        self.assertEqual(previous, json.loads(original_raw))
        marker = '      },\n      {\n        "id": "' + self.rule["id"] + '"'
        start = raw.index(marker)
        end = raw.index("    ]\n  },", start)
        self.assertEqual((raw[:start] + "      }\n" + raw[end:]).encode(), original_raw)
        previous["reservation_policy"]["capabilities"].append(copy.deepcopy(self.rule))
        self.assertEqual(previous, current)
        self.assertEqual(routine_support_predecessor(current),
                         routine_support_predecessor(json.loads(original_raw)))

    def test_canonical_mutations_duplicates_and_historical_block_reordering_denied(self):
        for field, value in (
            ("work_packages", ["WP-305", "WP-502"]), ("work_packages", ["WP-502"]),
            ("path_rules", ["android/ui-tests/**"]), ("path_rules", [PATH + ".backup"]),
            ("operations", [OPERATION, "modify"]), ("operations", ["modify"]),
            ("invariants", self.rule["invariants"][:-1]), ("validation", []), ("extra", True),
        ):
            changed = copy.deepcopy(self.policy)
            changed["reservation_policy"]["capabilities"][-1][field] = value
            with self.subTest(field=field), self.assertRaises(PortError):
                routine_support_predecessor(changed)
        for duplicate in (True, False):
            changed = copy.deepcopy(self.policy)
            rules = changed["reservation_policy"]["capabilities"]
            if duplicate:
                rules.append(copy.deepcopy(self.rule))
            else:
                rules.insert(0, rules.pop())
            with self.assertRaises(PortError):
                routine_support_predecessor(changed)

    def test_actual_cli_and_immutable_staging_require_trusted_landing_and_reject_deletion(self):
        from controller.staging import stage_review

        with tempfile.TemporaryDirectory(prefix="onboarding-admission-") as directory:
            repo = Path(directory) / "fixture"
            subprocess.run(["git", "clone", "--no-checkout", "--shared", "--quiet", str(REPO), str(repo)],
                           check=True, capture_output=True)
            env = dict(os.environ, GIT_INDEX_FILE=str(Path(directory) / "index"),
                       ANDROID_PORT_LEDGER=str(Path(directory) / "private.sqlite3"),
                       GIT_AUTHOR_NAME="Fixture", GIT_AUTHOR_EMAIL="fixture@example.invalid",
                       GIT_COMMITTER_NAME="Fixture", GIT_COMMITTER_EMAIL="fixture@example.invalid")

            def git(*args, data=None):
                result = subprocess.run(["git", "-C", str(repo), *args], input=data, capture_output=True, env=env)
                self.assertEqual(result.returncode, 0, result.stderr.decode())
                return result.stdout.decode().strip()

            def commit(parent, contents):
                git("read-tree", parent)
                for path, content in contents.items():
                    if content is None:
                        git("update-index", "--force-remove", path)
                    else:
                        blob = git("hash-object", "-w", "--stdin", data=content)
                        git("update-index", "--add", "--cacheinfo", f"100644,{blob},{path}")
                return git("commit-tree", git("write-tree"), "-p", parent, "-m", "synthetic admission")

            proposed = (REPO / POLICY_PATH).read_bytes()
            before = subprocess.check_output(["git", "-C", str(REPO), "show", f"{BASE}:{PATH}"])
            after = before + b"\n// Synthetic staging fixture; not product behavior evidence.\n"
            candidate = commit(BASE, {POLICY_PATH: proposed, PATH: after})
            git("update-ref", "refs/heads/fixture", candidate)
            git("symbolic-ref", "HEAD", "refs/heads/fixture")
            git("update-ref", "refs/remotes/origin/main", BASE)
            git("symbolic-ref", "refs/remotes/origin/HEAD", "refs/remotes/origin/main")

            def admit():
                return subprocess.run([
                    sys.executable, str(REPO / "tools/android-port/local/reserve.py"),
                    "--repo", str(repo), "admit", "--wp", "WP-305",
                    "--path", PATH, "--operation", OPERATION,
                ], env=env, capture_output=True, text=True)

            refused = admit()
            self.assertNotEqual(refused.returncode, 0)
            self.assertIn("no trusted path+operation rule", refused.stderr + refused.stdout)
            with self.assertRaises(PortError):
                stage_review(repo, "WP-305", BASE, candidate,
                             path_operations={POLICY_PATH: "modify", PATH: OPERATION})
            trusted = commit(BASE, {POLICY_PATH: proposed})
            candidate = commit(trusted, {PATH: after})
            git("update-ref", "refs/remotes/origin/main", trusted)
            admitted = admit()
            self.assertEqual(admitted.returncode, 0, admitted.stderr + admitted.stdout)
            self.assertEqual(json.loads(admitted.stdout)["capability"], self.rule["id"])
            staged = stage_review(repo, "WP-305", trusted, candidate, path_operations={PATH: OPERATION})
            self.assertEqual(staged["changed_paths"], [PATH])
            self.assertFalse(staged["authoritative"])
            deleted = commit(trusted, {PATH: None})
            with self.assertRaisesRegex(PortError, "cannot delete a test"):
                stage_review(repo, "WP-305", trusted, deleted, path_operations={PATH: OPERATION})
