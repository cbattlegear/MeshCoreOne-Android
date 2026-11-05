"""AndroidOnly: WP-004 Fail-closed workflow, helper-discovery and JUnit contract tests."""

import ast
import copy
import hashlib
import json
import re
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
        "name": "Retain refreshed Room v2 schema and provenance",
        "if": "${{ always() && steps.room_schema.outcome == 'success' }}",
        "uses": ARTIFACT_ACTION,
        "with": {
            "name": "room-v2-refresh-" + CANDIDATE + "-" + RUN,
            "path": ("${{ runner.temp }}/room-schema-refresh/2.json\n"
                     "${{ runner.temp }}/room-schema-refresh/provenance.json\n"),
            "if-no-files-found": "error",
            "retention-days": "7",
        },
    }
    # Exact refresh diagnostic from workflow blob c2b57bc2258311c5b317c254cec701ef542fe2ac.
    # Binds tracked-predecessor/KSP/provenance/failure checks, not artifact-looking paths.
    ROOM_STEPS_SHA256 = "2c921599a595c287375f99b784b4bb8714bfe24cd34ad4e98e51bbd21bb460cf"
    ROOM_V3_IDS = ("room_v3_before", "room_v3_ksp", "room_v3_collect", "room_v3_upload")
    # Exact root-scoped v3 workflow blob 44ab2115df7b9603dfa22f7e91ba235a3873e362.
    ROOM_V3_STEPS_SHA256 = "320938d69a307e3aa4ca67549121c48efe401e01429cf5363c9bdebecb4515b3"
    ROOM_V3_UPLOAD = {
        "id": "room_v3_upload",
        "name": "Retain diagnostic Room v3 schema and provenance",
        "if": "${{ always() && steps.room_v3_collect.outcome == 'success' }}",
        "uses": ARTIFACT_ACTION,
        "with": {
            "name": "room-v3-export-${{ github.sha }}-" + RUN,
            "path": ("${{ runner.temp }}/room-v3-export/artifact/3.json\n"
                     "${{ runner.temp }}/room-v3-export/artifact/provenance.json\n"),
            "if-no-files-found": "error",
            "retention-days": "7",
        },
    }

    ROOM_V4_IDS = ("room_v4_before", "room_v4_ksp", "room_v4_collect", "room_v4_upload")
    # Exact root-scoped v4 workflow blob 2c7aef19e579907db19f8ab72f2e35c721522f72.
    ROOM_V4_STEPS_SHA256 = "398dcbecae9f83eb73f4d43131d66b43de9982688a2e73d33262ec247991f01b"
    ROOM_V4_UPLOAD = {
        "id": "room_v4_upload",
        "name": "Retain diagnostic Room v4 schema and provenance",
        "if": "${{ always() && steps.room_v4_collect.outcome == 'success' }}",
        "uses": ARTIFACT_ACTION,
        "with": {
            "name": "room-v4-export-${{ github.sha }}-" + RUN,
            "path": ("${{ runner.temp }}/room-v4-export/artifact/4.json\n"
                     "${{ runner.temp }}/room-v4-export/artifact/provenance.json\n"),
            "if-no-files-found": "error",
            "retention-days": "7",
        },
    }

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
            if step.get("id") in ("room_refresh_before", "room_schema", "room_upload")
            or step.get("name") == "Report Room schema refresh outcome"
        ]
        room_v3 = [step for step in steps if step.get("id") in self.ROOM_V3_IDS]
        room_v4 = [step for step in steps if step.get("id") in self.ROOM_V4_IDS]
        self.assertLessEqual(sum(bool(chain) for chain in (room, room_v3, room_v4)), 1,
                             "Schema export diagnostics cannot be combined")
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
            self.assertFalse(room_v3, "The v2 refresh and v3 export are separate diagnostics")
            self.assertEqual(len(room), 4, "A partial schema diagnostic cannot authorize an upload")
            self.assertEqual(room[2], self.ROOM_UPLOAD)
            encoded = json.dumps(room, sort_keys=True, separators=(",", ":")).encode("utf-8")
            self.assertEqual(hashlib.sha256(encoded).hexdigest(), self.ROOM_STEPS_SHA256)
            gradle = next(step for step in steps if step.get("id") == "gradle")
            ordered = [room[0], gradle, *expected, *room[1:]]
            positions = [steps.index(step) for step in ordered]
            self.assertEqual(positions, sorted(set(positions)))
            expected.append(self.ROOM_UPLOAD)
        if room_v3:
            self.assertEqual([step.get("id") for step in room_v3], list(self.ROOM_V3_IDS))
            self.assertEqual(room_v3[-1], self.ROOM_V3_UPLOAD)
            self.assert_room_v3_chain(room_v3)
            gradle = next(step for step in steps if step.get("id") == "gradle")
            ordered = [*room_v3[:2], gradle, *expected, *room_v3[2:]]
            positions = [steps.index(step) for step in ordered]
            self.assertEqual(positions, sorted(set(positions)))
            expected.append(self.ROOM_V3_UPLOAD)
        if room_v4:
            self.assertEqual([step.get("id") for step in room_v4], list(self.ROOM_V4_IDS))
            self.assertEqual(room_v4[-1], self.ROOM_V4_UPLOAD)
            self.assert_room_v4_chain(room_v4)
            gradle = next(step for step in steps if step.get("id") == "gradle")
            ordered = [*room_v4[:2], gradle, *expected, *room_v4[2:]]
            positions = [steps.index(step) for step in ordered]
            self.assertEqual(positions, sorted(set(positions)))
            expected.append(self.ROOM_V4_UPLOAD)
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
            if step.get("id") == "room_schema":
                self.assert_room_heading_controls(step["run"])
            if step.get("id") in ("room_refresh_before", "room_schema", "room_upload") or (
                step.get("name") == "Report Room schema refresh outcome"
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

    def assert_room_heading_controls(self, run):
        source = run.split("@'\n", 1)[1].split("\n'@ | python", 1)[0]
        nodes = ast.parse(source).body
        start = next(index for index, node in enumerate(nodes)
                     if isinstance(node, ast.Assign) and isinstance(node.targets[0], ast.Name)
                     and node.targets[0].id == "task")
        # Exercise the actual collector predicate without executing file/identity collection.
        end = next(index for index in range(start, len(nodes)) if isinstance(nodes[index], ast.If))
        selected = nodes[start:end + 1]
        self.assertIsInstance(selected[1], ast.Assign)
        self.assertEqual(selected[1].targets[0].id, "observations")
        self.assertIsInstance(selected[-1], ast.If)
        program = compile(ast.Module(body=selected, type_ignores=[]), "<collector-heading-check>", "exec")
        canonical = "> Task :core:database:kspDebugKotlin"
        for count in (1, 2, 3):
            with self.subTest(canonical_heading_displays=count):
                scope = {"lines": [canonical] * count, "re": re}
                exec(program, scope)
                self.assertEqual(scope["observations"], [canonical] * count)
        invalid = [[], ["> Task :core:database:kspReleaseKotlin"]]
        for suffix in ("FROM-CACHE", "SKIPPED", "FAILED", "NO-SOURCE", "UP-TO-DATE", "arbitrary"):
            suffixed = canonical + " " + suffix
            invalid.extend(([suffixed], [canonical, suffixed], [suffixed, canonical],
                            [canonical, suffixed, canonical]))
        for lines in invalid:
            with self.subTest(rejected_heading_displays=lines), self.assertRaises(RuntimeError):
                exec(program, {"lines": lines, "re": re})

    def test_baseline_without_room_diagnostic_remains_valid(self):
        value = copy.deepcopy(self.workflow)
        value["jobs"]["build"]["steps"] = [
            step for step in value["jobs"]["build"]["steps"]
            if step.get("id") not in ("room_refresh_before", "room_schema", "room_upload")
            and step.get("id") not in self.ROOM_V3_IDS
            and step.get("id") not in self.ROOM_V4_IDS
            and step.get("name") != "Report Room schema refresh outcome"
        ]
        self.assert_artifact_boundary(value)

    def assert_room_v3_chain(self, steps):
        encoded = json.dumps(steps, sort_keys=True, separators=(",", ":")).encode("utf-8")
        self.assertEqual(hashlib.sha256(encoded).hexdigest(), self.ROOM_V3_STEPS_SHA256)
        for step in steps[:3]:
            self.assertTrue(step["run"].startswith("@'\n"))
            self.assertTrue(step["run"].endswith(
                "'@ | python -\nif ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }\n"
            ))
        for step in steps[1:3]:
            self.assert_room_v3_execution_controls(step["run"])


    def assert_room_v4_schema_controls(self, run):
        source = run.split("@'\n", 1)[1].split("\n'@ | python", 1)[0]
        nodes = ast.parse(source).body
        functions = [node for node in nodes if isinstance(node, ast.FunctionDef)
                     and node.name in ("unlinked", "digest", "parse_json", "unique_fields",
                                       "preserve_predecessors", "read_v4")]
        self.assertEqual(len(functions), 6)
        declared = next(node.value for node in nodes if isinstance(node, ast.Assign)
                        and isinstance(node.targets[0], ast.Name)
                        and node.targets[0].id == "predecessors")
        expected = ast.literal_eval(declared)
        self.assertEqual(set(expected), {"1", "2", "3"})
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            samples = {}
            for version, value in expected.items():
                data = json.dumps({"unit_only_version": version}).encode()
                path = root / value["path"]
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(data)
                value["sha256"] = hashlib.sha256(data).hexdigest()
                samples[version] = data
            drift = {}

            def git(*args):
                path = args[1].removeprefix("HEAD:") if args[0] == "rev-parse" else args[-1]
                version = next(key for key, value in expected.items() if value["path"] == path)
                if args[0] == "rev-parse":
                    return "unexpected" if drift.get(version) == "blob" else expected[version]["blob"]
                self.assertEqual(args[:3], ("status", "--porcelain", "--"))
                return " M " + path if drift.get(version) == "dirty" else ""

            scope = {"repo": root, "predecessors": expected, "hashlib": hashlib, "json": json,
                     "git": git, "v4": expected["3"]["path"].replace("/3.json", "/4.json")}
            exec(compile(ast.Module(body=functions, type_ignores=[]),
                         "<actual-v4-schema-guards>", "exec"), scope)
            preserve = scope["preserve_predecessors"]
            preserve()
            for version, value in expected.items():
                path = root / value["path"]
                for kind in ("missing", "content", "blob", "dirty"):
                    with self.subTest(predecessor=version, mutation=kind):
                        if kind == "missing":
                            path.unlink()
                        elif kind == "content":
                            path.write_bytes(b"changed")
                        else:
                            drift[version] = kind
                        with self.assertRaises((RuntimeError, FileNotFoundError)):
                            preserve()
                        path.write_bytes(samples[version])
                        drift.clear()
            target = root / scope["v4"]
            read = scope["read_v4"]
            with self.assertRaises(RuntimeError):
                read(1)
            valid = b'{"database":{"version":4}}'
            target.write_bytes(valid)
            self.assertEqual(read(1), valid)
            with self.assertRaises(RuntimeError):
                read(target.stat().st_mtime_ns + 1)
            for value in (3, 5, "4", True, None):
                target.write_text(json.dumps({"database": {"version": value}}), encoding="utf-8")
                with self.subTest(rejected_version=value), self.assertRaises(RuntimeError):
                    read(1)
            for data in (b"", b'{"database":{"version":4,"version":4}}'):
                target.write_bytes(data)
                with self.assertRaises(RuntimeError):
                    read(1)

    def assert_room_v4_chain(self, steps):
        encoded = json.dumps(steps, sort_keys=True, separators=(",", ":")).encode("utf-8")
        self.assertEqual(hashlib.sha256(encoded).hexdigest(), self.ROOM_V4_STEPS_SHA256)
        for step in steps[:3]:
            self.assertTrue(step["run"].startswith("@'\n"))
            self.assertTrue(step["run"].endswith(
                "'@ | python -\nif ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }\n"
            ))
        for step in steps[:3]:
            self.assert_room_v4_schema_controls(step["run"])
        for step in steps[1:3]:
            self.assert_room_v3_execution_controls(step["run"])

    def assert_room_v3_execution_controls(self, run):
        source = run.split("@'\n", 1)[1].split("\n'@ | python", 1)[0]
        nodes = ast.parse(source).body
        validator = next(node for node in nodes if isinstance(node, ast.FunctionDef)
                         and node.name == "validate_execution")
        repo = REPO / "unit-fixture-root"
        scope = {"task": ":core:database:kspDebugKotlin", "repo": repo}
        exec(compile(ast.Module(body=[validator], type_ignores=[]), "<actual-ksp-validator>", "exec"),
             scope)
        validate = scope["validate_execution"]
        bound = {"diagnostic_sha": "a" * 40, "run_id": "123", "run_attempt": "2"}
        rows = [
            {"task": scope["task"], "task_type": "com.google.devtools.ksp.gradle.KspTaskJvm",
             "phase": phase, "build_root": str((repo / "android").resolve()),
             "project_directory": str((repo / "android" / "core" / "database").resolve()), **bound}
            for phase in ("before", "action-start", "action-end", "after")
        ]
        rows[-1].update(executed=True, did_work=True, skipped=False, up_to_date=False,
                        no_source=False, skip_message=None, failure=None)
        validate(rows, bound)
        for kind in ("missing", "duplicate", "reordered", "wrong-task", "wrong-type", "wrong-sha",
                     "wrong-run", "wrong-attempt", "not-executed", "no-work", "skipped",
                     "up-to-date", "no-source", "cache-message", "failed", "wrong-root",
                     "wrong-directory"):
            with self.subTest(ksp_receipt=kind):
                changed = copy.deepcopy(rows)
                if kind == "missing":
                    changed.pop(1)
                elif kind == "duplicate":
                    changed.insert(2, copy.deepcopy(changed[1]))
                elif kind == "reordered":
                    changed[1], changed[2] = changed[2], changed[1]
                elif kind in ("wrong-task", "wrong-type", "wrong-sha", "wrong-run", "wrong-attempt",
                              "wrong-root", "wrong-directory"):
                    key = {"wrong-task": "task", "wrong-type": "task_type", "wrong-sha": "diagnostic_sha",
                           "wrong-run": "run_id", "wrong-attempt": "run_attempt",
                           "wrong-root": "build_root", "wrong-directory": "project_directory"}[kind]
                    changed[1][key] = "unexpected"
                else:
                    key, value = {
                        "not-executed": ("executed", False), "no-work": ("did_work", False),
                        "skipped": ("skipped", True), "up-to-date": ("up_to_date", True),
                        "no-source": ("no_source", True), "cache-message": ("skip_message", "FROM-CACHE"),
                        "failed": ("failure", "GradleException"),
                    }[kind]
                    changed[-1][key] = value
                with self.assertRaises(RuntimeError):
                    validate(changed, bound)

    def test_room_v3_rejects_partial_mutated_or_widened_diagnostics(self):
        partial = copy.deepcopy(self.workflow)
        partial["jobs"]["build"]["steps"].append(copy.deepcopy(self.ROOM_V3_UPLOAD))
        with self.assertRaises(AssertionError):
            self.assert_artifact_boundary(partial)
        for index, step in enumerate(self.workflow["jobs"]["build"]["steps"]):
            if step.get("id") not in self.ROOM_V3_IDS:
                continue
            for kind in ("missing", "duplicate", "ignored", "condition", "body", "identity",
                         "missing-provenance", "glob", "pin", "missing-error"):
                with self.subTest(step=step["id"], kind=kind):
                    value = copy.deepcopy(self.workflow)
                    changed = value["jobs"]["build"]["steps"]
                    target = changed[index]
                    if kind == "missing":
                        changed.pop(index)
                    elif kind == "duplicate":
                        changed.insert(index, copy.deepcopy(target))
                    elif kind == "ignored":
                        target["continue-on-error"] = "true"
                    elif kind == "condition":
                        target["if"] = "${{ always() }}"
                    elif "run" in target:
                        target["run"] = 'Write-Output "success"'
                    elif kind == "identity":
                        target["with"]["name"] = "unbound-room-v3"
                    elif kind == "missing-provenance":
                        target["with"]["path"] = "${{ runner.temp }}/room-v3-export/artifact/3.json"
                    elif kind == "glob":
                        target["with"]["path"] = "${{ runner.temp }}/room-v3-export/**"
                    elif kind == "pin":
                        target["uses"] = "actions/upload-artifact@v6"
                    elif kind == "missing-error":
                        del target["with"]["if-no-files-found"]
                    else:
                        target["with"]["path"] += "${{ runner.temp }}/room-v3-export/compiler.json\n"
                    with self.assertRaises((AssertionError, ValueError)):
                        self.assert_artifact_boundary(value)

    def test_room_v4_rejects_partial_mutated_or_widened_diagnostics(self):
        partial = copy.deepcopy(self.workflow)
        partial["jobs"]["build"]["steps"].append(copy.deepcopy(self.ROOM_V4_UPLOAD))
        with self.assertRaises(AssertionError):
            self.assert_artifact_boundary(partial)
        for index, step in enumerate(self.workflow["jobs"]["build"]["steps"]):
            if step.get("id") not in self.ROOM_V4_IDS:
                continue
            for kind in ("missing", "duplicate", "ignored", "condition", "body", "identity",
                         "missing-provenance", "glob", "pin", "missing-error"):
                with self.subTest(step=step["id"], kind=kind):
                    value = copy.deepcopy(self.workflow)
                    changed = value["jobs"]["build"]["steps"]
                    target = changed[index]
                    if kind == "missing":
                        changed.pop(index)
                    elif kind == "duplicate":
                        changed.insert(index, copy.deepcopy(target))
                    elif kind == "ignored":
                        target["continue-on-error"] = "true"
                    elif kind == "condition":
                        target["if"] = "${{ always() }}"
                    elif "run" in target:
                        target["run"] = 'Write-Output "success"'
                    elif kind == "identity":
                        target["with"]["name"] = "unbound-room-v4"
                    elif kind == "missing-provenance":
                        target["with"]["path"] = "${{ runner.temp }}/room-v4-export/artifact/4.json"
                    elif kind == "glob":
                        target["with"]["path"] = "${{ runner.temp }}/room-v4-export/**"
                    elif kind == "pin":
                        target["uses"] = "actions/upload-artifact@v6"
                    elif kind == "missing-error":
                        del target["with"]["if-no-files-found"]
                    else:
                        target["with"]["path"] += "${{ runner.temp }}/room-v4-export/compiler.json\n"
                    with self.assertRaises((AssertionError, ValueError)):
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
