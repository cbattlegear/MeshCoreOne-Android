"""AndroidOnly: WP-000 Bounded WP-303 notification-enrollment resource admission."""

import copy
import json
import subprocess
import unittest
from xml.sax.saxutils import escape

from fixtures import REPO, policy, test_manifest
from controller.capabilities import CapabilityEngine
from controller.errors import PortError
from controller.scope_amendment import (
    NOTIFICATION_ENROLLMENT_COPY_CAPABILITY,
    routine_support_predecessor,
)


BASE = "1a8b406a67d44006f14ac043ac468b6adf07a9d7"
PATH = "android/app/src/main/res/values/notification_enrollment.xml"
OPERATION = "add-or-update-notification-enrollment-copy"
KEYS = (
    "notification_enrollment_action",
    "notification_enrollment_title",
    "notification_enrollment_warning",
    "notification_enrollment_confirm",
    "notification_enrollment_cancel",
    "notification_enrollment_resume",
    "notification_enrollment_held",
    "notification_enrollment_interrupted",
    "notification_enrollment_blocked",
    "notification_enrollment_bound",
)


class NotificationEnrollmentResourceCapabilityTests(unittest.TestCase):
    def setUp(self):
        self.policy = policy()
        self.engine = CapabilityEngine(self.policy)
        self.manifest = test_manifest("WP-303")
        self.rule = NOTIFICATION_ENROLLMENT_COPY_CAPABILITY

    def test_exact_path_operation_work_package_and_key_contract(self):
        self.assertEqual(self.rule["path_rules"], [PATH])
        self.assertEqual(self.rule["work_packages"], ["WP-303"])
        self.assertEqual(self.rule["operations"], [OPERATION])
        self.assertEqual(self.rule["keys"], list(KEYS))
        self.assertEqual(self.policy["reservation_policy"]["capabilities"][-1], self.rule)
        admission = self.engine.admit("WP-303", [], PATH, OPERATION)
        self.assertEqual(admission.capability_id, self.rule["id"])
        self.assertEqual(admission.invariants, tuple(self.rule["invariants"]))
        self.assertEqual(admission.validation, tuple(self.rule["validation"]))

    def test_other_work_packages_wrong_operations_siblings_and_traversal_are_denied(self):
        for wp in self.manifest.work_packages:
            if wp != "WP-303":
                with self.subTest(wp=wp), self.assertRaises(PortError):
                    self.engine.admit(wp, [], PATH, OPERATION)
        for operation in (
            "create", "modify", "delete", "test", "generate",
            "modify-related-consumer", "add-or-update-related-test", "unknown",
        ):
            with self.subTest(operation=operation), self.assertRaises(PortError):
                self.engine.admit("WP-303", [], PATH, operation)
        for path in (
            "android/app/src/main/res/values/strings.xml",
            "android/app/src/main/res/values/notification_enrollment.xml.backup",
            "android/app/src/main/res/values-de/notification_enrollment.xml",
            "android/app/src/main/res/values/../values/notification_enrollment.xml",
            "../" + PATH,
            "/" + PATH,
            "android/feature/settings/src/main/res/values/notification_enrollment.xml",
            "android/app/src/main/res/values/other_notification_enrollment.xml",
        ):
            with self.subTest(path=path), self.assertRaises(PortError):
                self.engine.admit("WP-303", [], path, OPERATION)

    def test_assignment_admission_is_not_historical_owner_or_lease_authority(self):
        invariants = " ".join(self.rule["invariants"])
        for required in (
            "trusted-main-landing", "legitimate-assignment", "path-operation-admission",
            "resolved-overlap", "no-new-lease-or-historical-owner-reattestation",
        ):
            with self.subTest(required=required):
                self.assertIn(required, invariants)
        self.assertNotIn("fresh-same-owner-admission", invariants)
        admitted = self.engine.admit("WP-303", [], PATH, OPERATION)
        self.assertEqual(admitted.capability_id, self.rule["id"])
        text = (REPO / "docs/android/AUTOMATION.md").read_text(encoding="utf-8")
        self.assertIn("owner need not re-attest or rebind", text)
        self.assertIn("Real execution, runtime and protected gates remain in force", text)

    def test_previous_capabilities_and_policy_are_unchanged(self):
        base_policy = json.loads(subprocess.check_output(
            ["git", "-C", str(REPO), "show", f"{BASE}:docs/android/automation-policy.json"],
        ))
        current = json.loads((REPO / "docs/android/automation-policy.json").read_text(encoding="utf-8"))
        added = current["reservation_policy"]["capabilities"].pop()
        self.assertEqual(added, self.rule)
        self.assertEqual(current, base_policy)
        current_text = (REPO / "docs/android/automation-policy.json").read_text(encoding="utf-8")
        original_text = subprocess.check_output(
            ["git", "-C", str(REPO), "show", f"{BASE}:docs/android/automation-policy.json"],
            encoding="utf-8",
        )
        marker = '      },\n      {\n        "id": "' + self.rule["id"] + '"'
        start = current_text.index(marker)
        end = current_text.index("    ]\n  },", start)
        self.assertEqual(current_text[:start] + "      }\n" + current_text[end:], original_text)
        full_current = json.loads(
            (REPO / "docs/android/automation-policy.json").read_text(encoding="utf-8")
        )
        self.assertEqual(routine_support_predecessor(full_current),
                         routine_support_predecessor(base_policy))
        for field, value in (
            ("path_rules", [PATH + ".backup"]),
            ("work_packages", ["WP-303", "WP-302"]),
            ("operations", [OPERATION, "modify"]),
            ("keys", list(KEYS[:-1])),
            ("invariants", self.rule["invariants"][:-1]),
            ("validation", self.rule["validation"][:-1]),
        ):
            changed = copy.deepcopy(self.policy)
            changed["reservation_policy"]["capabilities"][-1][field] = value
            with self.subTest(field=field), self.assertRaises(PortError):
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
    def resource_xml(values):
        entries = "".join(
            f'<string name="{key}">{escape(value)}</string>'
            for key, value in values.items()
        )
        return f"<resources>{entries}</resources>".encode()

    def values(self, suffix="base"):
        return {key: f"{key} {suffix}" for key in KEYS}

    def validate(self, base, candidate, blobs, operations=None):
        path_operations = operations or {PATH: OPERATION}
        CapabilityEngine.validate_support_changes(
            path_operations, base, candidate, read_blob=blobs.__getitem__,
        )

    def test_immutable_resource_reader_accepts_create_and_key_update(self):
        created = self.values()
        candidate = {PATH: "candidate"}
        blobs = {"candidate": self.resource_xml(created)}
        self.validate({}, candidate, blobs)

        updated = dict(created)
        updated["notification_enrollment_warning"] = "warning revised"
        blobs.update(base=self.resource_xml(created), candidate=self.resource_xml(updated))
        self.validate({PATH: "base"}, candidate, blobs)

    def test_unrelated_key_deletion_and_wrong_path_or_missing_reader_are_rejected(self):
        values = self.values()
        candidate = {PATH: "candidate"}
        blobs = {"candidate": self.resource_xml(values), "base": self.resource_xml(values)}
        with self.assertRaises(PortError):
            CapabilityEngine.validate_support_changes(
                {PATH: OPERATION}, {}, candidate, read_blob=None,
            )
        with self.assertRaises(PortError):
            self.validate({PATH: "base"}, {}, blobs)
        for bad in (
            {**values, "unrelated_notice": "should not be admitted"},
            {key: value for key, value in values.items() if key != KEYS[0]},
        ):
            blobs["candidate"] = self.resource_xml(bad)
            with self.subTest(keys=tuple(bad)), self.assertRaises(PortError):
                self.validate({}, candidate, blobs)
        with self.assertRaises(PortError):
            self.validate(
                {}, {PATH + ".backup": "candidate"}, blobs,
                {PATH + ".backup": OPERATION},
            )

    def test_malformed_duplicate_empty_nonstring_and_dtd_resources_fail_closed(self):
        candidate = {PATH: "candidate"}
        valid = self.resource_xml(self.values()).decode()
        invalid = (
            b"<resources>",
            b"\xff",
            b"<resources/>",
            b"<resources>stray text</resources>",
            b"<!DOCTYPE resources [<!ENTITY x 'expanded'>]><resources/>",
            valid.replace("</resources>", '<string name="unrelated">x</string></resources>').encode(),
            valid.replace("</resources>", '<string name="notification_enrollment_action">x</string></resources>').encode(),
            valid.replace("<string name=\"notification_enrollment_action\">notification_enrollment_action base</string>",
                          "<string name=\"notification_enrollment_action\"> </string>").encode(),
            valid.replace("<resources>", "<resources><plurals name=\"other\">").replace(
                "</resources>", "</plurals></resources>"
            ).encode(),
            valid.replace("<resources>", "<resources extra=\"x\">").encode(),
            valid.replace(' name="notification_enrollment_action"', ' name="notification_enrollment_action" translatable="false"').encode(),
        )
        for xml in invalid:
            with self.subTest(xml=xml[:100]), self.assertRaises(PortError):
                self.validate({}, candidate, {"candidate": xml})

    def test_existing_unrelated_entries_and_notices_cannot_be_changed_or_removed(self):
        valid = self.resource_xml(self.values())
        for marker in (b"<!-- Copyright notice -->", b"<?localization preserve?>"):
            for position in ("before", "inside", "after"):
                if position == "before":
                    original = marker + valid
                elif position == "after":
                    original = valid + marker
                else:
                    original = valid.replace(b"<resources>", b"<resources>" + marker)
                blobs = {"base": original, "candidate": original}
                with self.subTest(marker=marker, position=position):
                    self.validate({PATH: "base"}, {PATH: "candidate"}, blobs)
                    for changed in (valid, original.replace(b"preserve", b"modified"),
                                    original.replace(b"Copyright", b"Changed")):
                        if changed != original:
                            blobs["candidate"] = changed
                            with self.assertRaisesRegex(PortError, "unrelated resource"):
                                self.validate({PATH: "base"}, {PATH: "candidate"}, blobs)
        extra = valid.replace(b"</resources>", b'<string name="unrelated">Keep</string></resources>')
        with self.assertRaises(PortError):
            self.validate({PATH: "base"}, {PATH: "candidate"}, {"base": extra, "candidate": valid})


if __name__ == "__main__":
    unittest.main()
