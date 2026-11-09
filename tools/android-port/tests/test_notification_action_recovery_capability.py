"""AndroidOnly: WP-000 Exact WP-215 rejected-action recovery resource admission."""

import copy
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from xml.sax.saxutils import escape

from fixtures import REPO, policy, test_manifest
from controller.capabilities import CapabilityEngine
from controller.errors import PortError
from controller.scope_amendment import (
    NOTIFICATION_ACTION_RECOVERY_COPY_CAPABILITY,
    ONBOARDING_PRESET_FIXTURE_CAPABILITY,
    routine_support_predecessor,
)


BASE = "5f4d9b92b2eb30d02f001567ef7e4f1dd6e80f66"
MAIN = "5f4d9b92b2eb30d02f001567ef7e4f1dd6e80f66"
PATH = "android/platform/notifications/src/main/res/values/notification_action_recovery.xml"
OPERATION = "add-or-update-notification-action-recovery-copy"
KEYS = ["notification_action_recovery_" + suffix for suffix in (
    "title", "rejected_reply", "rejected_mark_read", "saved", "memory_only",
    "not_retained", "storage_unavailable", "copy_failed", "discard_failed",
    "launch_failed", "original_target",
)]
MARKER = "<!-- AndroidOnly: WP-215 Explicit recovery of notification actions rejected before dispatcher admission. -->"


class NotificationActionRecoveryCapabilityTests(unittest.TestCase):
    def setUp(self):
        self.policy = policy()
        self.rule = NOTIFICATION_ACTION_RECOVERY_COPY_CAPABILITY
        self.engine = CapabilityEngine(self.policy)
        self.manifest = test_manifest("WP-215")

    @staticmethod
    def values():
        return {key: ("Original target: %1$s" if key.endswith("_original_target") else "Recovery copy")
                for key in KEYS}

    @staticmethod
    def xml(values=None):
        values = NotificationActionRecoveryCapabilityTests.values() if values is None else values
        return (MARKER + "<resources>" + "".join(
            f'<string name="{key}">{escape(value)}</string>' for key, value in values.items()
        ) + "</resources>").encode()

    def validate(self, candidate, base=None):
        blobs = {"after": candidate, "before": base}
        CapabilityEngine.validate_support_changes(
            {PATH: OPERATION}, {} if base is None else {PATH: "before"}, {PATH: "after"},
            read_blob=blobs.__getitem__,
        )

    def test_exact_contract_and_actual_main_refusal(self):
        self.assertEqual(self.rule["path_rules"], [PATH])
        self.assertEqual(self.rule["operations"], [OPERATION])
        self.assertEqual(self.rule["work_packages"], ["WP-215"])
        self.assertEqual(self.rule["keys"], KEYS)
        self.assertEqual(next(rule for rule in self.policy["reservation_policy"]["capabilities"]
                              if rule["id"] == self.rule["id"]), self.rule)
        self.assertEqual(self.engine.admit("WP-215", [], PATH, OPERATION).capability_id, self.rule["id"])
        trusted = json.loads(subprocess.check_output(
            ["git", "-C", str(REPO), "show", f"{MAIN}:docs/android/automation-policy.json"]
        ))
        with self.assertRaises(PortError):
            CapabilityEngine(trusted).admit("WP-215", self.manifest.wp("WP-215")["write_paths"],
                                          PATH, OPERATION)

    def test_other_owners_operations_paths_locales_and_traversal_denied(self):
        for wp in self.manifest.work_packages:
            if wp != "WP-215":
                with self.subTest(wp=wp), self.assertRaises(PortError):
                    self.engine.admit(wp, self.manifest.wp(wp)["write_paths"], PATH, OPERATION)
        for operation in ("create", "modify", "delete", "generate", "test",
                          "add-or-update-notification-enrollment-copy", "unknown"):
            with self.subTest(operation=operation), self.assertRaises(PortError):
                self.engine.admit("WP-215", [], PATH, operation)
        for path in ("android/app/src/main/res/values/notification_action_recovery.xml",
                     PATH + ".backup", PATH.replace("values/", "values-de/"),
                     PATH.replace("notification_action_recovery.xml", "strings.xml"),
                     PATH.replace("notification_action_recovery.xml", "notification_enrollment.xml"),
                     PATH.replace("values/", "values/../values/"), "../" + PATH, "/" + PATH,
                     "MC1/Localizable.xcstrings", "android/app/src/main/res/values/other.xml"):
            with self.subTest(path=path), self.assertRaises(PortError):
                self.engine.admit("WP-215", [], path, OPERATION)

    def test_predecessor_object_raw_projection_and_prior_admissions_unchanged(self):
        original_raw = subprocess.check_output(
            ["git", "-C", str(REPO), "show", f"{BASE}:docs/android/automation-policy.json"]
        )
        original = json.loads(original_raw)
        raw = (REPO / "docs/android/automation-policy.json").read_text(encoding="utf-8")
        current = json.loads(raw)
        self.assertEqual(current["reservation_policy"]["capabilities"].pop(),
                         ONBOARDING_PRESET_FIXTURE_CAPABILITY)
        self.assertEqual(current["reservation_policy"]["capabilities"].pop(), self.rule)
        self.assertEqual(current, original)
        self.assertEqual(routine_support_predecessor(json.loads(raw)), routine_support_predecessor(original))
        marker = '      },\n      {\n        "id": "' + self.rule["id"] + '"'
        start = raw.index(marker)
        end = raw.index("    ]\n  },", start)
        self.assertEqual((raw[:start] + "      }\n" + raw[end:]).encode(), original_raw)
        previous = CapabilityEngine(original)
        for wp in self.manifest.work_packages:
            assigned = self.manifest.wp(wp)["write_paths"]
            for operation in ("create", "modify", "delete", "test", "generate",
                              "modify-related-consumer", "add-or-update-notification-enrollment-copy"):
                try:
                    old = previous.admit(wp, assigned, PATH, operation)
                except PortError:
                    with self.assertRaises(PortError):
                        self.engine.admit(wp, assigned, PATH, operation)
                else:
                    self.assertEqual(self.engine.admit(wp, assigned, PATH, operation), old)

    def test_canonical_rule_mutations_duplicates_and_reordering_denied(self):
        for field, value in (("path_rules", [PATH + ".backup"]), ("operations", [OPERATION, "modify"]),
                             ("work_packages", ["WP-215", "WP-401"]), ("keys", KEYS[:-1]),
                             ("invariants", self.rule["invariants"][:-1]), ("validation", [])):
            changed = copy.deepcopy(self.policy)
            selected = next(rule for rule in changed["reservation_policy"]["capabilities"]
                            if rule["id"] == self.rule["id"])
            selected[field] = value
            with self.subTest(field=field), self.assertRaises(PortError):
                routine_support_predecessor(changed)
        for duplicate in (True, False):
            changed = copy.deepcopy(self.policy)
            rules = changed["reservation_policy"]["capabilities"]
            if duplicate:
                rules.append(copy.deepcopy(self.rule))
            else:
                index = next(i for i, rule in enumerate(rules) if rule["id"] == self.rule["id"])
                rules.insert(0, rules.pop(index))
            with self.assertRaises(PortError):
                routine_support_predecessor(changed)

    def test_create_update_utf8_declaration_and_notice_preservation(self):
        original = self.xml()
        self.validate(original)
        self.validate(b'<?xml version="1.0" encoding="UTF-8"?>' + original)
        changed = self.values()
        changed[KEYS[0]] = "Updated recovery title"
        self.validate(self.xml(changed), original)
        for notice in (b"<!-- Copyright notice -->", b"<?recovery preserve?>"):
            before = original.replace(b"<resources>", b"<resources>" + notice)
            after = self.xml(changed).replace(b"<resources>", b"<resources>" + notice)
            self.validate(after, before)
            with self.assertRaises(PortError):
                self.validate(self.xml(changed), before)
        with self.assertRaises(PortError):
            self.validate(original.replace(b"WP-215", b"WP-401"))

    def test_resource_shape_and_encoding_negatives(self):
        valid = self.xml()
        for raw in (
            b"<resources>", b"\xff", b"<resources/>", b"<resources>stray text</resources>",
            b"<!DOCTYPE resources [<!ENTITY x 'expanded'>]>" + valid,
            valid + b"\x00", valid.decode().encode("utf-16le"), valid.decode().encode("utf-16be"),
            b'<?xml version="1.0" encoding="UTF-16"?>' + valid,
            b'<?xml version="1.0" encoding="ISO-8859-1"?>' + valid,
            valid.replace(b"<resources>", b'<resources extra="x">'),
            valid.replace(b"<resources>", b'<resources><plurals name="other"/>'),
            valid.replace(b"</resources>", b'<string name="unrelated">no</string></resources>'),
            valid.replace(b"</resources>", b'<string name="' + KEYS[0].encode() + b'">dup</string></resources>'),
            valid.replace(b'name="' + KEYS[0].encode() + b'"',
                          b'name="' + KEYS[0].encode() + b'" translatable="false"'),
        ):
            with self.subTest(raw=raw[:90]), self.assertRaises(PortError):
                self.validate(raw)
        for bad in ({k: v for k, v in self.values().items() if k != KEYS[0]},
                    {**self.values(), KEYS[0]: " "}):
            with self.assertRaises(PortError):
                self.validate(self.xml(bad))
        for operations, candidate, reader in (
            ({PATH: OPERATION}, {}, lambda _: valid),
            ({PATH: OPERATION}, {PATH: "blob"}, None),
            ({PATH + ".backup": OPERATION}, {PATH + ".backup": "blob"}, lambda _: valid),
        ):
            with self.assertRaises(PortError):
                CapabilityEngine.validate_support_changes(operations, {}, candidate, read_blob=reader)

    def test_exact_placeholder_contract_after_xml_and_android_decoding(self):
        target = KEYS[-1]
        for value in ("No target", "%1$s %1$s", "%s", "%1$d", "%1$S", "%%1$s",
                      "%1$s %2$s", "%1$s %n", "%1%%$s", "%1$%%s"):
            with self.subTest(value=value), self.assertRaises(PortError):
                self.validate(self.xml({**self.values(), target: value}))
        for value in ("%s", "%1$s", r"\u00251$s", "%n"):
            with self.subTest(value=value), self.assertRaises(PortError):
                self.validate(self.xml({**self.values(), KEYS[0]: value}))
        self.validate(self.xml({**self.values(), target: r"Original target: \u00251$s"}))
        self.validate(self.xml({**self.values(), KEYS[0]: "Literal 100%%"}))
        self.validate(self.xml({**self.values(), target: "Literal %% target: %1$s %%"}))
        self.validate(self.xml({**self.values(), target: "%%%1$s"}))
        self.validate(self.xml().replace(b"%1$s", b"&#37;1$s"))


    def test_authored_copy_aliases_apostrophes_and_escaped_positive_controls(self):
        for value in ("Can't recover", "Can&apos;t recover", "@string/other", "  @null",
                      "?attr/other", "@android:string/cancel", r"Can\\'t recover"):
            raw = self.xml({**self.values(), KEYS[0]: value})
            if value == "Can&apos;t recover":
                raw = raw.replace(b"&amp;apos;", b"&apos;")
            with self.subTest(value=value), self.assertRaises(PortError):
                self.validate(raw)
        for value in (r"Can\'t recover", '"Can\'t recover"', r"\@string/literal",
                      r"\?attr/literal", "Recovery copy", "Unicode caf\u00e9"):
            with self.subTest(value=value):
                self.validate(self.xml({**self.values(), KEYS[0]: value}))
        self.validate(self.xml({**self.values(), KEYS[-1]: r"Can\'t recover target %1$s"}))

    def test_simulator_authored_guard_outcomes_match_trusted_predecessor(self):
        namespace = {"__package__": "controller", "__name__": "controller.capabilities"}
        original = subprocess.check_output([
            "git", "-C", str(REPO), "show", f"{BASE}:tools/android-port/controller/capabilities.py",
        ])
        exec(compile(original, f"{BASE}:capabilities.py", "exec"), namespace)
        previous = namespace["CapabilityEngine"]
        for value in ("Can't recover", "@string/other", "  @null", "?attr/other",
                      r"Can\'t recover", '"Can\'t recover"', r"\@string/literal",
                      r"\?attr/literal", "CASE", r"Can\\'t recover", r"bad\uZZZZ"):
            with self.subTest(value=value):
                try:
                    expected = previous._simulator_copy_comparison(value)
                except PortError as error:
                    with self.assertRaises(PortError) as actual:
                        CapabilityEngine._simulator_copy_comparison(value)
                    self.assertEqual(str(actual.exception), str(error))
                else:
                    self.assertEqual(CapabilityEngine._simulator_copy_comparison(value), expected)

    def test_real_immutable_staging_and_candidate_cannot_authorize_itself(self):
        from controller.staging import stage_review

        with tempfile.TemporaryDirectory(prefix="action-copy-staging-") as directory:
            repo = Path(directory) / "fixture"
            subprocess.run(["git", "clone", "--no-checkout", "--shared", "--quiet", str(REPO), str(repo)],
                           check=True, capture_output=True)
            env = dict(os.environ, GIT_INDEX_FILE=str(Path(directory) / "index"),
                       GIT_AUTHOR_NAME="Fixture", GIT_AUTHOR_EMAIL="fixture@example.invalid",
                       GIT_COMMITTER_NAME="Fixture", GIT_COMMITTER_EMAIL="fixture@example.invalid")

            def git(*args, data=None):
                result = subprocess.run(["git", "-C", str(repo), *args], input=data, capture_output=True, env=env)
                self.assertEqual(result.returncode, 0, result.stderr.decode())
                return result.stdout.decode().strip()

            def commit(parent, contents):
                git("read-tree", parent)
                for path, content in contents.items():
                    blob = git("hash-object", "-w", "--stdin", data=content)
                    git("update-index", "--add", "--cacheinfo", f"100644,{blob},{path}")
                return git("commit-tree", git("write-tree"), "-p", parent, "-m", "synthetic recovery copy")

            policy_path = "docs/android/automation-policy.json"
            proposed = (REPO / policy_path).read_bytes()
            candidate = commit(MAIN, {PATH: self.xml()})
            with self.assertRaises(PortError):
                stage_review(repo, "WP-215", MAIN, candidate, path_operations={PATH: OPERATION})
            candidate = commit(MAIN, {policy_path: proposed, PATH: self.xml()})
            with self.assertRaises(PortError):
                stage_review(repo, "WP-215", MAIN, candidate,
                             path_operations={policy_path: "modify", PATH: OPERATION})
            trusted = commit(MAIN, {policy_path: proposed})
            candidate = commit(trusted, {PATH: self.xml()})
            staged = stage_review(repo, "WP-215", trusted, candidate, path_operations={PATH: OPERATION})
            self.assertEqual(staged["changed_paths"], [PATH])
            self.assertFalse(staged["authoritative"])
            with self.assertRaises(PortError):
                stage_review(repo, "WP-401", trusted, candidate, path_operations={PATH: OPERATION})
            for key, value in ((KEYS[-1], "%s"), (KEYS[0], "Can't recover"),
                               (KEYS[0], "@string/other"), (KEYS[0], "?attr/other")):
                invalid = commit(trusted, {PATH: self.xml({**self.values(), key: value})})
                with self.subTest(key=key, value=value), self.assertRaises(PortError):
                    stage_review(repo, "WP-215", trusted, invalid, path_operations={PATH: OPERATION})
