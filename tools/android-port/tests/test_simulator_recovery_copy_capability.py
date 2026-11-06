"""AndroidOnly: WP-000 Exact WP-217 simulator recovery source-copy admission."""

import copy
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from xml.sax.saxutils import escape

from fixtures import REPO, policy, test_manifest
from controller.capabilities import CapabilityEngine
from controller.errors import PortError
from controller.scope_amendment import (
    SIMULATOR_PREFERENCE_RECOVERY_COPY_CAPABILITY,
    routine_support_predecessor,
)


BASE = "0d10b838a181cd3bd34af1a0200435149dbc6bb0"
ROOT = "android/app/src/main/res/"
LOCALES = ("values", "values-de", "values-es", "values-fr", "values-it", "values-ko",
           "values-nl", "values-pl", "values-pt", "values-ru", "values-uk", "values-b+zh+Hans")
PATHS = [ROOT + locale + "/simulator_preference_recovery.xml" for locale in LOCALES]
OPERATION = "add-or-update-simulator-preference-recovery-copy"
KEYS = ["simulator_preference_recovery_" + suffix for suffix in (
    "title", "explanation", "current", "recovered", "apply", "applying", "retry",
    "not_set", "empty_text", "no_regions", "flood_scope", "repeat_frequency",
    "repeat_bandwidth", "repeat_spreading_factor", "repeat_coding_rate",
    "last_contact_sync", "ocv_preset", "radio_preset", "custom_ocv", "known_regions",
)]


class SimulatorRecoveryCopyCapabilityTests(unittest.TestCase):
    def setUp(self):
        self.policy = policy()
        self.engine = CapabilityEngine(self.policy)
        self.rule = SIMULATOR_PREFERENCE_RECOVERY_COPY_CAPABILITY
        self.manifest = test_manifest("WP-000")

    def test_exact_owner_operation_paths_keys_and_current_main_denial(self):
        self.assertEqual(self.rule["path_rules"], PATHS)
        self.assertEqual(self.rule["keys"], KEYS)
        self.assertEqual(self.rule["operations"], [OPERATION])
        self.assertEqual(self.rule["work_packages"], ["WP-217"])
        self.assertEqual(self.policy["reservation_policy"]["capabilities"][-1], self.rule)
        previous = CapabilityEngine(self.base_policy())
        for path in PATHS:
            admission = self.engine.admit("WP-217", self.manifest.wp("WP-217")["write_paths"],
                                          path, OPERATION)
            self.assertEqual(admission.capability_id, self.rule["id"])
            self.assertEqual(admission.invariants, tuple(self.rule["invariants"]))
            with self.assertRaises(PortError):
                previous.admit("WP-217", self.manifest.wp("WP-217")["write_paths"], path, OPERATION)
        for wp in self.manifest.work_packages:
            if wp == "WP-217":
                continue
            for path in PATHS:
                with self.subTest(wp=wp, path=path), self.assertRaises(PortError):
                    self.engine.admit(wp, self.manifest.wp(wp)["write_paths"], path, OPERATION)

    def test_wrong_operations_siblings_generated_reference_and_traversal_denied(self):
        for path in PATHS:
            for operation in ("create", "modify", "delete", "test", "generate",
                              "modify-related-consumer", "add-or-update-related-test",
                              "add-or-update-notification-enrollment-copy",
                              "add-or-update-contact-import-recovery-copy"):
                with self.subTest(path=path, op=operation), self.assertRaises(PortError):
                    self.engine.admit("WP-217", [], path, operation)
        for path in (
            ROOT + "values/strings.xml", PATHS[0] + ".backup",
            ROOT + "values-ja/simulator_preference_recovery.xml",
            ROOT + "values-en/simulator_preference_recovery.xml",
            ROOT + "values-pt-rBR/simulator_preference_recovery.xml",
            PATHS[0].replace("/values/", "/values/../values/"),
            PATHS[0].replace("android/app/", "android//app/"),
            "/" + PATHS[0], "../" + PATHS[0],
            PATHS[0].replace("android/app/", "android/feature/settings/"),
            "android/core/l10n/src/main/res/values/l10n_strings.xml",
            "MC1/Localizable.xcstrings", "android/app/build.gradle.kts",
        ):
            with self.subTest(path=path), self.assertRaises(PortError):
                self.engine.admit("WP-217", [], path, OPERATION)

    @staticmethod
    def base_policy():
        return json.loads(subprocess.check_output(
            ["git", "-C", str(REPO), "show", f"{BASE}:docs/android/automation-policy.json"]
        ))

    def test_complete_predecessor_raw_and_object_and_ordinary_admissions_unchanged(self):
        previous = self.base_policy()
        raw = (REPO / "docs/android/automation-policy.json").read_text(encoding="utf-8")
        full_current = json.loads(raw)
        current = copy.deepcopy(full_current)
        self.assertEqual(current["reservation_policy"]["capabilities"].pop(), self.rule)
        self.assertEqual(current, previous)
        self.assertEqual(routine_support_predecessor(full_current), routine_support_predecessor(previous))
        marker = '      },\n      {\n        "id": "' + self.rule["id"] + '"'
        start = raw.index(marker)
        end = raw.index("    ]\n  },", start)
        self.assertEqual(raw[:start] + "      }\n" + raw[end:], subprocess.check_output(
            ["git", "-C", str(REPO), "show", f"{BASE}:docs/android/automation-policy.json"],
            encoding="utf-8",
        ))
        old_engine = CapabilityEngine(previous)
        for wp in self.manifest.work_packages:
            assigned = self.manifest.wp(wp)["write_paths"]
            for path in PATHS:
                for op in ("create", "modify", "delete", "generate", "modify-related-consumer"):
                    try:
                        old = old_engine.admit(wp, assigned, path, op)
                    except PortError:
                        with self.assertRaises(PortError):
                            self.engine.admit(wp, assigned, path, op)
                    else:
                        self.assertEqual(self.engine.admit(wp, assigned, path, op), old)

    def test_mutated_rule_and_arbitrary_key_metadata_fail_closed(self):
        for field, value in (
            ("path_rules", [ROOT + "**"]), ("keys", KEYS[:-1]),
            ("keys", KEYS + [KEYS[0]]), ("work_packages", ["WP-217", "WP-303"]),
            ("operations", [OPERATION, "modify"]), ("invariants", self.rule["invariants"][:-1]),
            ("validation", []), ("extra", True),
        ):
            changed = copy.deepcopy(self.policy)
            changed["reservation_policy"]["capabilities"][-1][field] = value
            with self.subTest(field=field, value=value), self.assertRaises(PortError):
                routine_support_predecessor(changed)
        changed = copy.deepcopy(self.policy)
        changed["reservation_policy"]["capabilities"][-1]["id"] = "reviewed-support-other-copy"
        with self.assertRaises(PortError):
            routine_support_predecessor(changed)
        for mode in ("duplicate", "reordered"):
            changed = copy.deepcopy(self.policy)
            rules = changed["reservation_policy"]["capabilities"]
            if mode == "duplicate":
                rules.append(copy.deepcopy(self.rule))
            else:
                rules.insert(0, rules.pop())
            with self.subTest(mode=mode), self.assertRaises(PortError):
                routine_support_predecessor(changed)

    @staticmethod
    def xml(values, marker=""):
        return ("<resources>" + marker + "".join(
            f'<string name="{key}">{escape(value)}</string>' for key, value in values.items()
        ) + "</resources>").encode()

    def fixture(self):
        base, candidate, blobs = {}, {}, {}
        for index, path in enumerate(PATHS):
            base[path], candidate[path] = f"old-{index}", f"new-{index}"
            # Deliberately synthetic: these are structural fixtures, not translations.
            blobs[base[path]] = self.xml({key: f"Old fixture {index}" for key in KEYS})
            blobs[candidate[path]] = self.xml({key: f"New fixture {index}" for key in KEYS})
        return base, candidate, blobs

    def validate(self, base, candidate, blobs, operations=None):
        CapabilityEngine.validate_support_changes(
            dict.fromkeys(PATHS, OPERATION) if operations is None else operations,
            base, candidate, read_blob=blobs.__getitem__,
        )

    def test_complete_twelve_locale_create_update_and_single_locale_update(self):
        base, candidate, blobs = self.fixture()
        self.validate({}, candidate, blobs)
        self.validate(base, candidate, blobs)
        one = dict(base)
        one[PATHS[0]] = candidate[PATHS[0]]
        self.validate(base, one, blobs, {PATHS[0]: OPERATION})
        for path in candidate:
            blobs[candidate[path]] = blobs[candidate[path]].replace(b"><", b">\n  <")
        self.validate(base, candidate, blobs)

    def test_every_locale_and_every_key_is_required(self):
        for path in PATHS:
            base, candidate, blobs = self.fixture()
            del candidate[path]
            with self.subTest(path=path), self.assertRaises(PortError):
                self.validate(base, candidate, blobs)
        for path in PATHS:
            for key in KEYS:
                base, candidate, blobs = self.fixture()
                blobs[candidate[path]] = self.xml({k: "Fixture" for k in KEYS if k != key})
                with self.subTest(path=path, key=key), self.assertRaises(PortError):
                    self.validate(base, candidate, blobs)

    def test_malformed_duplicate_extra_empty_nonstring_and_android_escapes_fail(self):
        for xml in (
            b"\xff", b"<resources>", b"<wrong/>", b"<resources>stray</resources>",
            b'<!DOCTYPE resources [<!ENTITY x "expansion">]><resources/>',
            self.xml(dict.fromkeys(KEYS, " ")), self.xml(dict.fromkeys(KEYS, r"\n\t")),
            self.xml(dict.fromkeys(KEYS, '""')), self.xml(dict.fromkeys(KEYS, r"\uZZZZ")),
            self.xml(dict.fromkeys(KEYS, '"Unclosed')),
            self.xml(dict.fromkeys(KEYS, "Text")).replace(
                b"</resources>", b'<string name="unrelated">Extra</string></resources>'),
            self.xml(dict.fromkeys(KEYS, "Text")).replace(
                b"</resources>", f'<string name="{KEYS[0]}">Duplicate</string></resources>'.encode()),
            self.xml(dict.fromkeys(KEYS, "Text")).replace(
                b"<resources>", b'<resources translatable="false">'),
            self.xml(dict.fromkeys(KEYS, "Text")).replace(
                b'">Text</string>', b'" translatable="false">Text</string>', 1),
            self.xml(dict.fromkeys(KEYS, "Text")).replace(b">Text<", b"><b>Text</b><", 1),
            self.xml(dict.fromkeys(KEYS, "Text")).replace(b"<string ", b"<plurals ", 1),
        ):
            base, candidate, blobs = self.fixture()
            blobs[candidate[PATHS[0]]] = xml
            with self.subTest(xml=xml), self.assertRaises(PortError):
                self.validate(base, candidate, blobs)

    def test_every_non_english_key_rejects_normalized_english_fallback(self):
        for index, path in enumerate(PATHS[1:], 1):
            for key in KEYS:
                base, candidate, blobs = self.fixture()
                values = dict.fromkeys(KEYS, f"New fixture {index}")
                values[key] = "New fixture 0"
                blobs[candidate[path]] = self.xml(values)
                with self.subTest(path=path, key=key), self.assertRaisesRegex(PortError, "English fallback"):
                    self.validate(base, candidate, blobs)
        for disguised in (
            " New fixture 0 ", "\nNew   fixture\t0\n", '"New fixture 0"',
            r"New\nfixture\t0", r"\u004eew fixture 0",
        ):
            base, candidate, blobs = self.fixture()
            values = dict.fromkeys(KEYS, "New fixture 11")
            values[KEYS[-1]] = disguised
            blobs[candidate[PATHS[-1]]] = self.xml(values)
            with self.subTest(disguised=disguised), self.assertRaisesRegex(PortError, "English fallback"):
                self.validate(base, candidate, blobs)

    def test_unrelated_notices_and_invalid_base_cannot_be_changed(self):
        for marker in (b"<!-- Copyright notice -->", b"<?localization preserve?>"):
            for position in ("before", "inside", "after"):
                base, candidate, blobs = self.fixture()
                path = PATHS[0]

                def insert(xml):
                    if position == "before":
                        return marker + xml
                    if position == "after":
                        return xml + marker
                    return xml.replace(b"<resources>", b"<resources>" + marker)

                blobs[base[path]] = insert(blobs[base[path]])
                with self.subTest(marker=marker, position=position), self.assertRaises(PortError):
                    self.validate(base, candidate, blobs)
                blobs[candidate[path]] = insert(blobs[candidate[path]])
                self.validate(base, candidate, blobs)
        base, candidate, blobs = self.fixture()
        blobs[base[PATHS[0]]] = b"<resources/>"
        with self.assertRaises(PortError):
            self.validate(base, candidate, blobs)

    def test_exact_operation_each_changed_locale_and_immutable_reader_required(self):
        base, candidate, blobs = self.fixture()
        with self.assertRaises(PortError):
            CapabilityEngine.validate_support_changes(dict.fromkeys(PATHS, OPERATION), base, candidate)
        with self.assertRaises(PortError):
            self.validate(base, candidate, blobs, {PATHS[0] + ".backup": OPERATION})
        with self.assertRaises(PortError):
            self.validate(base, candidate, blobs, {PATHS[0]: OPERATION})
        with self.assertRaises(PortError):
            self.validate(base, candidate, blobs, {
                path: OPERATION if index else "modify-related-consumer"
                for index, path in enumerate(PATHS)
            })

    def test_staging_uses_actual_git_blobs_and_trusted_not_proposed_policy(self):
        from controller.model import git, tree
        from controller.staging import stage_review

        entries = tree(REPO, BASE)
        policy_blob = entries["docs/android/automation-policy.json"]

        def validate(operations, base, candidate, *, read_blob):
            self.assertEqual(operations, {})
            self.assertEqual(base, entries)
            self.assertEqual(candidate, entries)
            self.assertEqual(read_blob(policy_blob), git(REPO, "cat-file", "blob", policy_blob))
            trusted = CapabilityEngine(json.loads(read_blob(policy_blob)))
            for path in PATHS:
                with self.assertRaises(PortError):
                    trusted.admit("WP-217", [], path, OPERATION)
            raise PortError("stopped after real immutable staging")

        with patch.object(CapabilityEngine, "validate_support_changes", side_effect=validate) as check:
            with self.assertRaisesRegex(PortError, "real immutable staging"):
                stage_review(REPO, "WP-000", BASE, BASE, path_operations={})
            check.assert_called_once()

    def test_real_git_staging_accepts_only_fixture_trusted_rule_and_rejects_bad_copy(self):
        from controller.staging import stage_review

        with tempfile.TemporaryDirectory(prefix="simulator-copy-staging-") as directory:
            repo = Path(directory) / "fixture"
            subprocess.run(
                ["git", "clone", "--no-checkout", "--shared", "--quiet", str(REPO), str(repo)],
                check=True, capture_output=True,
            )
            env = dict(os.environ, GIT_INDEX_FILE=str(Path(directory) / "fixture.index"),
                       GIT_AUTHOR_NAME="Fixture", GIT_AUTHOR_EMAIL="fixture@example.invalid",
                       GIT_COMMITTER_NAME="Fixture", GIT_COMMITTER_EMAIL="fixture@example.invalid")

            def git(*args, data=None):
                result = subprocess.run(
                    ["git", "-C", str(repo), *args], input=data, env=env,
                    capture_output=True,
                )
                self.assertEqual(result.returncode, 0, result.stderr.decode())
                return result.stdout.decode().strip()

            def commit(parent, contents):
                git("read-tree", parent)
                for path, content in contents.items():
                    blob = git("hash-object", "-w", "--stdin", data=content)
                    git("update-index", "--add", "--cacheinfo", f"100644,{blob},{path}")
                return git("commit-tree", git("write-tree"), "-p", parent, "-m", "synthetic fixture")

            policy_path = "docs/android/automation-policy.json"
            proposed = (REPO / policy_path).read_bytes()
            resources = {path: self.xml(dict.fromkeys(KEYS, f"Structural fixture {index}"))
                         for index, path in enumerate(PATHS)}
            operations = dict.fromkeys(PATHS, OPERATION)
            untrusted_candidate = commit(BASE, {policy_path: proposed, **resources})
            with self.assertRaisesRegex(PortError, "no trusted path\\+operation rule"):
                stage_review(repo, "WP-217", BASE, untrusted_candidate,
                             path_operations={policy_path: "modify", **operations})
            # This synthetic base exercises a future landed rule, not live authorization.
            fixture_base = commit(BASE, {policy_path: proposed})
            candidate = commit(fixture_base, resources)
            staged = stage_review(repo, "WP-217", fixture_base, candidate, path_operations=operations)
            self.assertEqual(staged["changed_paths"], sorted(PATHS))
            self.assertFalse(staged["authoritative"])
            self.assertEqual({item["path"] for item in staged["files"]
                              if item["side"] == "candidate-data"}, set(PATHS))
            invalid = commit(candidate, {PATHS[-1]: b"<resources/>"})
            with self.assertRaisesRegex(PortError, "all twenty keys"):
                stage_review(repo, "WP-217", fixture_base, invalid, path_operations=operations)
            with self.assertRaisesRegex(PortError, "every changed path"):
                stage_review(repo, "WP-217", fixture_base, candidate,
                             path_operations={PATHS[0]: OPERATION})

    def test_obligations_do_not_claim_translation_or_runtime_acceptance(self):
        text = " ".join(self.rule["invariants"] + self.rule["validation"])
        for required in ("values-pt-is-pt-PT", "genuine-translations", "existing-catalog-bytes",
                         "cancel-keeps-both", "no-new-lease-or-historical-owner-reattestation",
                         "trusted-main-landing", "mounted-Compose", "positive-hosted-discovery",
                         "not-translation-quality", "no-runtime-installation"):
            self.assertIn(required, text)
