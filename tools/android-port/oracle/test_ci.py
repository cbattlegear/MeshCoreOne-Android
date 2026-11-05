"""AndroidOnly: WP-004 Fail-closed workflow, helper-discovery and JUnit contract tests."""

import copy
import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from xml.etree import ElementTree

from controller.ci_environment import candidate_environment
from controller.errors import PortError
from controller.workflows import parse_yaml, validate_boundary, validate_candidate
from oracle.foundation_ci import EXPECTED_TESTS, PACKAGE, verify_junit
from oracle.identity import evidence_identity
from oracle.reference import OracleError, REPO, SOURCE_SHA
from oracle.run_tests import discover


def reports(directory):
    for name, methods in EXPECTED_TESTS.items():
        root = ElementTree.Element("testsuite", name=PACKAGE + name, tests=str(len(methods)),
                                   failures="0", errors="0", skipped="0")
        for method in sorted(methods):
            ElementTree.SubElement(root, "testcase", name=method, classname=PACKAGE + name)
        ElementTree.ElementTree(root).write(directory / ("TEST-" + PACKAGE + name + ".xml"))


class HelperDiscoveryTests(unittest.TestCase):
    def test_all_six_actual_new_suites_are_required(self):
        with tempfile.TemporaryDirectory() as root:
            reports(Path(root))
            result = verify_junit(Path(root))
            self.assertEqual(result["discovered"], 35)
            self.assertEqual(result["passed"], 35)
            self.assertEqual(result["skipped"], 0)
            self.assertEqual(len(result["suites"]), 6)

    def test_missing_empty_malformed_and_unknown_xml_fail(self):
        with tempfile.TemporaryDirectory() as root:
            directory = Path(root)
            with self.assertRaises(OracleError):
                verify_junit(directory)
            path = directory / "TEST-empty.xml"
            for content in ("<invalid", "<testsuite name='unknown' tests='0'/>"):
                path.write_text(content)
                with self.subTest(content=content), self.assertRaises(OracleError):
                    verify_junit(directory)

    def test_failed_skipped_duplicate_zero_and_forged_counts_fail(self):
        for kind in ("failed", "skipped", "duplicate", "missing-case", "count", "missing-suite"):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as root:
                directory = Path(root)
                reports(directory)
                path = sorted(directory.glob("TEST-*.xml"))[0]
                tree = ElementTree.parse(path)
                suite = tree.getroot()
                case = suite.find("testcase")
                if kind in ("failed", "skipped"):
                    ElementTree.SubElement(case, "failure" if kind == "failed" else "skipped")
                elif kind == "duplicate":
                    suite.append(copy.deepcopy(case))
                elif kind == "missing-case":
                    suite.remove(case)
                elif kind == "count":
                    suite.set("tests", "999")
                if kind == "missing-suite":
                    path.unlink()
                else:
                    tree.write(path)
                with self.assertRaises(OracleError):
                    verify_junit(directory)

    def test_missing_or_zero_python_suites_fail(self):
        with tempfile.TemporaryDirectory() as root:
            with self.assertRaises(OracleError):
                discover(Path(root))
            path = Path(root) / "test_empty.py"
            path.write_text("import unittest\n")
            with self.assertRaises(OracleError):
                discover(Path(root), "test_empty.py")

    def test_candidate_gradle_reuses_the_existing_credential_allowlist(self):
        import os
        state = {"schema_version": 1, "host": "windows" if os.name == "nt" else "linux",
                 "java_home": str(REPO), "android_home": str(REPO), "private_root": str(REPO),
                 "provenance": {"scope": "unit fixture only"}}
        result = candidate_environment(state, inherited={"PATH": "path", "GH_TOKEN": "secret", "COPILOT_TOKEN": "secret"})
        self.assertNotIn("GH_TOKEN", result)
        self.assertNotIn("COPILOT_TOKEN", result)
        self.assertIn("GRADLE_USER_HOME", result)

    def test_hosted_identity_preserves_real_binding_without_claiming_a_gate(self):
        unit_identity = {
            "binding": {
                "repository": "cbattlegear/MeshCoreOne-Android", "work_package": "WP-003",
                "base_sha": "a" * 40, "head_sha": "b" * 40, "source_sha": SOURCE_SHA,
                "manifest_sha256": "c" * 64, "policy_revision": "d" * 64,
            },
            "run_id": 13, "run_attempt": 2,
        }
        with patch("oracle.identity.execution_identity", return_value=unit_identity):
            result = evidence_identity()
        self.assertEqual(result["binding"]["work_package"], "WP-004")
        self.assertEqual(result["binding"]["head_sha"], "b" * 40)
        self.assertEqual(result["run_id"], 13)
        self.assertIn("not an authenticated protected publisher", result["scope"])
        self.assertEqual(unit_identity["binding"]["work_package"], "WP-003")

    def test_local_identity_has_no_invented_hosted_run(self):
        with patch("oracle.identity.execution_identity", return_value=None):
            result = evidence_identity()
        self.assertIsNone(result["run_id"])
        self.assertIsNone(result["run_attempt"])
        self.assertEqual(result["binding"]["source_sha"], SOURCE_SHA)
        self.assertIn("no hosted run authority", result["scope"])


class FoundationWorkflowTests(unittest.TestCase):
    ARTIFACT_ACTION = "actions/upload-artifact@b7c566a772e6b6bfb58ed0dc250532a479d7789f"
    CANDIDATE = "${{ github.event.pull_request.head.sha || github.sha }}"
    RUN = "${{ github.run_id }}-${{ github.run_attempt }}"
    ROOM_UPLOAD = {
        "id": "room_upload",
        "name": "Retain fresh Room v2 export and provenance",
        "if": "${{ always() && steps.room_schema.outcome == 'success' }}",
        "uses": ARTIFACT_ACTION,
        "with": {
            "name": "room-v2-export-" + CANDIDATE + "-" + RUN,
            "path": ("${{ runner.temp }}/room-schema-export/2.json\n"
                     "${{ runner.temp }}/room-schema-export/provenance.json\n"),
            "if-no-files-found": "error",
            "retention-days": "7",
        },
    }
    # Exact four-step diagnostic from workflow blob a1813d57b30ff309ea1f07d92a1830ccb2d89692.
    # Binds executable absence/KSP/provenance/failure checks, not just artifact-looking paths.
    ROOM_STEPS_SHA256 = "3544d121a7d313a4ea61591fd899f8ef9d930935fb966d85af96ba40989431d0"

    @classmethod
    def setUpClass(cls):
        cls.text = (REPO / ".github" / "workflows" / "android-ci.yml").read_text(encoding="utf-8")
        cls.workflow = parse_yaml(cls.text)

    def test_pinned_read_only_ephemeral_candidate_boundaries(self):
        validate_boundary(self.workflow, self.text)
        self.assertEqual(self.workflow["permissions"], {"contents": "read"})
        self.assertEqual(self.workflow["jobs"]["build"]["runs-on"], "ubuntu-24.04")
        for name in ("external-oracle", "backup-oracle"):
            self.assertEqual(self.workflow["jobs"][name]["runs-on"], "macos-26-intel")
        for job in self.workflow["jobs"].values():
            self.assertFalse(job.get("environment"))
            self.assertFalse(job.get("services"))
            self.assertFalse(job.get("container"))

    def test_regular_pr_main_and_merge_group_events_have_no_path_skips(self):
        self.assertEqual(set(self.workflow["on"]), {"pull_request", "merge_group", "push", "workflow_dispatch"})
        self.assertEqual(self.workflow["on"]["push"]["branches"], ["main"])
        for event in ("pull_request", "merge_group"):
            self.assertFalse(set(self.workflow["on"][event]) & {"paths", "paths-ignore", "branches", "branches-ignore"})

    def test_actual_new_assertion_and_reference_commands_are_mandatory(self):
        runs = "\n".join(step.get("run", "") for job in self.workflow["jobs"].values() for step in job["steps"])
        for command in ("test_inventory.py --check", "extract_vectors.py --check",
                        "oracle/run_tests.py", "controller/ci.py provision",
                        "controller/ci.py run --stage scaffold", "oracle/codec_harness.py run"):
            self.assertIn(command, runs)
        validate_candidate(self.workflow, self.text)
        self.assertEqual(runs.count("controller/ci.py run --stage scaffold"), 1)

    def test_scope_selector_and_strict_runner_cannot_be_removed_duplicated_or_bypassed(self):
        for job in ("scope", "controller"):
            for mutation in ("missing", "duplicate", "conditional", "ignored", "synthetic", "unbound"):
                with self.subTest(job=job, mutation=mutation):
                    value = copy.deepcopy(self.workflow)
                    steps = value["jobs"][job]["steps"]
                    step = next(step for step in steps if "controller/test_runner.py" in step.get("run", ""))
                    if mutation == "missing":
                        steps.remove(step)
                    elif mutation == "duplicate":
                        steps.append(copy.deepcopy(step))
                    elif mutation == "conditional":
                        step["if"] = "${{ always() }}"
                    elif mutation == "ignored":
                        step["continue-on-error"] = "true"
                    elif mutation == "synthetic":
                        step["run"] = step["run"].replace(
                            "python tools/android-port/controller/test_runner.py",
                            "echo tools/android-port/controller/test_runner.py",
                        )
                    else:
                        step["run"] = step["run"].replace(
                            '--candidate-event-path "${{ github.event_path }}"', "",
                        )
                    with self.assertRaises(PortError):
                        validate_candidate(value, self.text)

    def test_auxiliary_conditions_are_job_level_and_preserve_complete_chains(self):
        expected = {"external-oracle": "external-oracle", "backup-oracle": "backup"}
        for name, scope in expected.items():
            with self.subTest(job=name):
                job = self.workflow["jobs"][name]
                self.assertEqual(
                    job["if"],
                    ("${{ needs.scope.outputs['external-oracle'] == 'true' }}"
                     if scope == "external-oracle"
                     else "${{ needs.scope.outputs." + scope + " == 'true' }}"),
                )
                self.assertIn("scope", job["needs"])
        self.assertEqual(self.workflow["jobs"]["build"]["needs"], ["scope"])
        self.assertEqual(self.workflow["jobs"]["external-oracle"]["needs"], ["scope"])
        self.assertEqual(self.workflow["jobs"]["backup-oracle"]["needs"], ["scope", "build"])

    def test_privilege_and_action_pin_mutations_fail(self):
        for kind in ("token", "action", "checkout"):
            value = copy.deepcopy(self.workflow)
            if kind == "token":
                value["permissions"]["contents"] = "write"
            elif kind == "action":
                value["jobs"]["build"]["steps"][0]["uses"] = "actions/checkout@v4"
            else:
                value["jobs"]["build"]["steps"][0]["with"]["persist-credentials"] = "true"
            with self.subTest(kind=kind), self.assertRaises(PortError):
                validate_boundary(value, self.text)

    def test_only_actual_backup_bytes_cross_the_job_boundary(self):
        self.assert_artifact_boundary(self.workflow)

    def assert_artifact_boundary(self, workflow):
        steps = workflow["jobs"]["build"]["steps"]
        uploads = [step for step in steps if step.get("uses", "").startswith("actions/upload-artifact@")]
        room = [
            step for step in steps
            if step.get("id") in ("room_absence", "room_schema", "room_upload")
            or step.get("name") == "Report Room schema diagnostic outcome"
        ]
        expected = [
            {
                "name": "Upload inspected debug APK for human testing",
                "if": "${{ success() && steps.gradle.outputs.full_scaffold == 'true' }}",
                "uses": self.ARTIFACT_ACTION,
                "with": {
                    "name": "debug-apk-" + self.CANDIDATE + "-" + self.RUN,
                    "path": "android/app/build/outputs/apk/debug/app-debug.apk",
                    "if-no-files-found": "error",
                    "retention-days": "7",
                },
            },
            {
                "name": "Transfer actual backup bytes to the platform oracle",
                "if": "${{ needs.scope.outputs.backup == 'true' }}",
                "uses": self.ARTIFACT_ACTION,
                "with": {
                    "name": "backup-input-" + self.RUN,
                    "path": "android/core/data/build/reports/wp203/interop/",
                    "if-no-files-found": "error",
                    "retention-days": "1",
                },
            },
        ]
        if room:
            self.assertEqual(len(room), 4, "A partial schema diagnostic cannot authorize an upload")
            self.assertEqual(room[2], self.ROOM_UPLOAD)
            encoded = json.dumps(room, sort_keys=True, separators=(",", ":")).encode("utf-8")
            self.assertEqual(hashlib.sha256(encoded).hexdigest(), self.ROOM_STEPS_SHA256)
            gradle = next(step for step in steps if step.get("id") == "gradle")
            ordered = [room[0], gradle, *expected, *room[1:]]
            positions = [steps.index(step) for step in ordered]
            self.assertEqual(positions, sorted(set(positions)))
            expected.append(self.ROOM_UPLOAD)
        self.assertEqual(uploads, expected)
        all_uploads = [
            step for job in workflow["jobs"].values() for step in job["steps"]
            if step.get("uses", "").startswith("actions/upload-artifact@")
        ]
        self.assertEqual(all_uploads, expected)
        transfers = [
            step for job in workflow["jobs"].values() for step in job["steps"]
            if step.get("uses", "").startswith("actions/download-artifact@")
        ]
        self.assertEqual(transfers, [{
            "name": "Download actual Kotlin-produced backup bytes",
            "uses": "actions/download-artifact@37930b1c2abaa49bbe596cd826c3c89aef350131",
            "with": {
                "name": "backup-input-" + self.RUN,
                "path": "${{ runner.temp }}/kotlin-input",
            },
        }])

    def test_artifact_boundary_rejects_missing_changed_and_unexpected_uploads(self):
        for kind in ("missing-apk", "missing-backup", "extra", "other-job", "reordered",
                     "apk-path", "backup-path", "condition", "pin", "ignored", "missing-error"):
            with self.subTest(kind=kind):
                value = copy.deepcopy(self.workflow)
                steps = value["jobs"]["build"]["steps"]
                uploads = [step for step in steps if step.get("uses", "").startswith("actions/upload-artifact@")]
                apk, backup = uploads[:2]
                if kind.startswith("missing-"):
                    if kind == "missing-error":
                        del backup["with"]["if-no-files-found"]
                    else:
                        steps.remove(apk if kind == "missing-apk" else backup)
                elif kind in ("extra", "other-job"):
                    job = "build" if kind == "extra" else "backup-oracle"
                    value["jobs"][job]["steps"].append(copy.deepcopy(apk))
                elif kind == "reordered":
                    left, right = steps.index(apk), steps.index(backup)
                    steps[left], steps[right] = steps[right], steps[left]
                elif kind in ("apk-path", "backup-path"):
                    (apk if kind == "apk-path" else backup)["with"]["path"] += "*"
                elif kind == "condition":
                    backup["if"] = "${{ always() }}"
                elif kind == "pin":
                    apk["uses"] = "actions/upload-artifact@v6"
                else:
                    backup["continue-on-error"] = "true"
                with self.assertRaises((AssertionError, ValueError)):
                    self.assert_artifact_boundary(value)

    def test_room_diagnostic_rejects_partial_chain_and_mutated_steps(self):
        partial = copy.deepcopy(self.workflow)
        partial["jobs"]["build"]["steps"].append(copy.deepcopy(self.ROOM_UPLOAD))
        with self.assertRaises(AssertionError):
            self.assert_artifact_boundary(partial)
        steps = self.workflow["jobs"]["build"]["steps"]
        for index, step in enumerate(steps):
            if step.get("id") in ("room_absence", "room_schema", "room_upload") or (
                step.get("name") == "Report Room schema diagnostic outcome"
            ):
                for kind in ("missing", "ignored", "condition", "body"):
                    with self.subTest(step=step["name"], kind=kind):
                        value = copy.deepcopy(self.workflow)
                        changed = value["jobs"]["build"]["steps"]
                        if kind == "missing":
                            changed.pop(index)
                        elif kind == "ignored":
                            changed[index]["continue-on-error"] = "true"
                        elif kind == "condition":
                            changed[index]["if"] = "${{ false }}"
                        elif "run" in step:
                            changed[index]["run"] = 'Write-Output "success"'
                        else:
                            changed[index]["with"]["path"] = "${{ runner.temp }}/room-schema-export/*"
                        with self.assertRaises((AssertionError, ValueError)):
                            self.assert_artifact_boundary(value)

    def test_baseline_without_room_diagnostic_remains_valid(self):
        value = copy.deepcopy(self.workflow)
        value["jobs"]["build"]["steps"] = [
            step for step in value["jobs"]["build"]["steps"]
            if step.get("id") not in ("room_absence", "room_schema", "room_upload")
            and step.get("name") != "Report Room schema diagnostic outcome"
        ]
        self.assert_artifact_boundary(value)

    def test_room_diagnostic_upload_has_no_wider_artifact_or_failure_allowance(self):
        for kind in ("extra-file", "missing-provenance", "glob", "identity", "pin", "missing-error",
                     "retention", "ignored", "condition"):
            with self.subTest(kind=kind):
                value = copy.deepcopy(self.workflow)
                steps = value["jobs"]["build"]["steps"]
                upload = next((step for step in steps if step.get("id") == "room_upload"), None)
                if upload is None:
                    upload = copy.deepcopy(self.ROOM_UPLOAD)
                    steps.append(upload)
                if kind == "extra-file":
                    upload["with"]["path"] += "${{ runner.temp }}/room-schema-export/absence.json\n"
                elif kind == "missing-provenance":
                    upload["with"]["path"] = "${{ runner.temp }}/room-schema-export/2.json\n"
                elif kind == "glob":
                    upload["with"]["path"] = "${{ runner.temp }}/room-schema-export/*"
                elif kind == "identity":
                    upload["with"]["name"] = "room-v2-export"
                elif kind == "pin":
                    upload["uses"] = "actions/upload-artifact@v6"
                elif kind == "missing-error":
                    del upload["with"]["if-no-files-found"]
                elif kind == "retention":
                    upload["with"]["retention-days"] = "90"
                elif kind == "ignored":
                    upload["continue-on-error"] = "true"
                else:
                    upload["if"] = "${{ always() }}"
                with self.assertRaises(AssertionError):
                    self.assert_artifact_boundary(value)
