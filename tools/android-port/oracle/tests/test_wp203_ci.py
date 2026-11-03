"""AndroidOnly: WP-203 SHA/run/data/XML reader negatives; not real Android or Swift evidence."""

import json
from pathlib import Path
import sys
import tempfile
import unittest
from contextlib import ExitStack
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from oracle import wp203_ci as CI
from oracle.reference import OracleError, SOURCE_SHA, json_bytes


class BundleTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="wp203-bundle-")
        self.root = Path(self.temporary.name)
        self.identity = {"binding": {"head_sha": "a" * 40, "source_sha": SOURCE_SHA}, "run_id": 1, "run_attempt": 1}
        self.junit = {"counts": {"tests": 1, "passed": 1, "failed": 0, "errors": 0, "skipped": 0}, "reports": []}

    def tearDown(self):
        self.temporary.cleanup()

    def make_bundle(self):
        for name in CI.DATA_NAMES["producer"]:
            (self.root / name).write_bytes(b"controlled test data")
        evidence = {"schema_version": 1, "stage": "producer", "identity": self.identity, "source_sha": SOURCE_SHA,
                    "tasks": list(CI.TASKS), "data": [CI.record(self.root, name) for name in CI.DATA_NAMES["producer"]],
                    "junit": self.junit}
        (self.root / "wp203-evidence.json").write_bytes(json_bytes(evidence))
        return evidence

    def make_report_free_producer(self):
        (self.root / "kotlin-export.meshcoreone").write_bytes(b"actual compressed Kotlin backup bytes")
        manifest = {
            "blockedChannelSenderCount": 1, "channelCount": 1, "contactCount": 1, "deviceCount": 1,
            "discoveredNodeCount": 1, "messageCount": 1, "messageRepeatCount": 1,
            "nodeStatusSnapshotCount": 1, "reactionCount": 1, "remoteNodeSessionCount": 1,
            "roomMessageCount": 1, "savedTracePathCount": 1,
        }
        (self.root / "kotlin-export.json").write_bytes(json_bytes({
            "version": 1, "manifest": manifest, "userDefaults": {"selectedThemeID": "ember"},
        }))
        (self.root / "kotlin-room-proof.json").write_bytes(json_bytes({
            "compressedSha256": CI.digest_file(self.root / "kotlin-export.meshcoreone"),
            "inserted": 12,
            "messageId": "00000000-0000-0000-0000-000000000004",
            "preferencesRestored": True,
            "producer": "actual-Room-Kotlin-export",
            "radioId": "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE",
            "restoreVerified": True,
            "skipped": 0,
        }))

    def make_swift_handoff(self):
        (self.root / "swift-export.meshcoreone").write_bytes(b"controlled Swift bytes")
        (self.root / "swift-export.json").write_bytes(b"{}")
        proof = {
            "source_sha": SOURCE_SHA,
            "producer": "actual-frozen-SwiftData-restore-and-AppBackupService-export",
            "inserted": 12, "skipped": 0, "reimport_inserted": 0,
            "restore_semantics_compared": True, "preferences_restored": True,
        }
        (self.root / "swift-room-proof.json").write_bytes(json_bytes(proof))
        (self.root / "swift-handoff.json").write_bytes(json_bytes(CI.swift_data_handoff(self.root, self.identity)))
        return proof

    def test_swift_handoff_transfers_only_data_not_ci_reports(self):
        proof = self.make_swift_handoff()
        self.assertEqual(proof, CI.validate_swift_handoff(self.root, self.identity))
        value = json.loads((self.root / "swift-handoff.json").read_text())
        self.assertEqual({"schema_version", "identity", "source_sha", "data"}, set(value))
        self.assertEqual(list(CI.SWIFT_HANDOFF_DATA), [row["path"] for row in value["data"]])
        self.assertFalse(any(self.root.glob("*.xml")))
        self.assertNotIn("junit", value)

    def test_swift_handoff_rejects_stale_candidate_run_attempt_source_and_extra_results(self):
        self.make_swift_handoff()
        path = self.root / "swift-handoff.json"
        original = json.loads(path.read_text())
        for field, value in (("identity", {**self.identity, "run_id": 2}),
                             ("identity", {**self.identity, "run_attempt": 2}),
                             ("identity", {**self.identity, "binding": {"head_sha": "b" * 40}}),
                             ("source_sha", "c" * 40), ("junit", {"passed": 1})):
            with self.subTest(field=field, value=value):
                path.write_bytes(json_bytes({**original, field: value}))
                with self.assertRaisesRegex(OracleError, "exact head/base/source/run/attempt"):
                    CI.validate_swift_handoff(self.root, self.identity)

    def test_swift_handoff_rejects_tampered_data_and_false_restore_observations(self):
        for field, value in (("inserted", 0), ("skipped", 1), ("reimport_inserted", 1),
                             ("restore_semantics_compared", False), ("preferences_restored", False),
                             ("producer", "Kotlin-only"), ("source_sha", "b" * 40)):
            with self.subTest(field=field):
                proof = self.make_swift_handoff()
                (self.root / "swift-room-proof.json").write_bytes(json_bytes({**proof, field: value}))
                (self.root / "swift-handoff.json").write_bytes(json_bytes(CI.swift_data_handoff(self.root, self.identity)))
                with self.assertRaisesRegex(OracleError, "actual frozen SwiftData"):
                    CI.validate_swift_handoff(self.root, self.identity)
        self.make_swift_handoff()
        (self.root / "swift-export.meshcoreone").write_bytes(b"tampered")
        with self.assertRaisesRegex(OracleError, "bytes"):
            CI.validate_swift_handoff(self.root, self.identity)

    def test_consumer_runs_real_declared_tasks_with_swift_input_and_no_result_bundle(self):
        self.make_swift_handoff()
        produced = self.root / "android" / "core" / "data" / "build" / "reports" / "wp203" / "interop"
        produced.mkdir(parents=True)
        proof = {
            "consumer": "actual-Swift-export-Room-restore",
            "compressedSha256": CI.digest_file(self.root / "swift-export.meshcoreone"),
            "inserted": 12, "reimportInserted": 0, "sourceSemanticsCompared": True, "preferencesRestored": True,
        }
        (produced / "swift-to-kotlin-room-proof.json").write_bytes(json_bytes(proof))
        cases = [
            {"class": "com.meshcoreone.android.core.data.backup.BackupInteropTest", "method": method}
            for method in ("actualRoomKotlinExportProducesAllFamiliesForSwift",
                           "actualSwiftExportRestoresIntoRealRoomAndMatchesSourceSemantics")
        ]
        output = self.root / "consumer-output"
        with ExitStack() as stack:
            stack.enter_context(patch.object(CI, "REPO", self.root))
            stack.enter_context(patch.object(CI, "identity", return_value=self.identity))
            stack.enter_context(patch.object(CI, "preflight"))
            stack.enter_context(patch.object(CI, "verify_wrapper"))
            stack.enter_context(patch.object(CI, "candidate_environment", return_value={}))
            stack.enter_context(patch.object(CI, "suite_counts", return_value={"discovered": 2}))
            methods = stack.enter_context(patch.object(CI, "suite_case_ids", return_value=cases))
            execute = stack.enter_context(patch.object(CI, "execute"))
            result = CI.consume_swift_data({"host": "linux", "private_root": str(self.root)}, output, self.root)
            command = execute.call_args.args[0]
            for task in CI.TASKS:
                self.assertIn(task, command)
            self.assertIn("-Pwp203InteropInputDir=" + str(self.root.resolve()), command)
            self.assertIn("--rerun-tasks", command)
            self.assertIn("--no-build-cache", command)
            self.assertEqual(proof, result["restore"])
            self.assertEqual([], list(output.iterdir()))
            methods.return_value = cases[:1]
            with self.assertRaisesRegex(OracleError, "omitted the mandatory"):
                CI.consume_swift_data({"host": "linux", "private_root": str(self.root)},
                                      self.root / "omitted-output", self.root)
            methods.return_value = cases
            (produced / "swift-to-kotlin-room-proof.json").write_bytes(json_bytes({**proof, "reimportInserted": 1}))
            with self.assertRaisesRegex(OracleError, "restore observations disagree"):
                CI.consume_swift_data({"host": "linux", "private_root": str(self.root)},
                                      self.root / "bad-proof-output", self.root)

    def test_invalid_swift_handoff_never_starts_consumer_preflight_or_gradle(self):
        self.make_swift_handoff()
        (self.root / "swift-export.meshcoreone").unlink()
        with patch.object(CI, "identity", return_value=self.identity), patch.object(CI, "preflight") as preflight, \
                patch.object(CI, "execute") as execute:
            with self.assertRaisesRegex(OracleError, "Missing"):
                CI.consume_swift_data({"host": "linux"}, self.root / "output", self.root)
            preflight.assert_not_called()
            execute.assert_not_called()

    def test_report_free_producer_validates_actual_bytes_and_room_semantics(self):
        self.make_report_free_producer()
        result = CI.validate_bundle(self.root, "producer", self.identity)
        self.assertEqual("producer", result["stage"])
        self.assertEqual(3, len(result["data"]))
        self.assertNotIn("identity", result)

    def test_report_free_producer_rejects_tampered_bytes_or_room_proof(self):
        self.make_report_free_producer()
        (self.root / "kotlin-export.meshcoreone").write_bytes(b"tampered")
        with self.assertRaisesRegex(OracleError, "disagrees"):
            CI.validate_bundle(self.root, "producer", self.identity)

    def test_exact_data_hashes_and_identity_are_required(self):
        self.make_bundle()
        with patch.object(CI, "report_record", return_value=self.junit):
            self.assertEqual("producer", CI.validate_bundle(self.root, "producer", self.identity)["stage"])
            (self.root / "kotlin-export.meshcoreone").write_bytes(b"modified")
            with self.assertRaisesRegex(OracleError, "hashes/sizes"):
                CI.validate_bundle(self.root, "producer", self.identity)

    def test_stale_head_run_or_attempt_fails_before_consumption(self):
        self.make_bundle()
        for field, value in (("run_id", 2), ("run_attempt", 2), ("binding", {"head_sha": "b" * 40, "source_sha": SOURCE_SHA})):
            with self.subTest(field=field):
                expected = dict(self.identity, **{field: value})
                with self.assertRaisesRegex(OracleError, "same head/base/run/attempt"):
                    CI.validate_bundle(self.root, "producer", expected)

    def test_missing_extra_or_malformed_stage_fields_fail(self):
        evidence = self.make_bundle()
        for field in ("tasks", "junit", "data", "identity"):
            with self.subTest(field=field):
                changed = dict(evidence)
                del changed[field]
                (self.root / "wp203-evidence.json").write_bytes(json_bytes(changed))
                with self.assertRaisesRegex(OracleError, "Malformed"):
                    CI.validate_bundle(self.root, "producer", self.identity)

    def test_raw_junit_cannot_disagree_with_summary(self):
        self.make_bundle()
        with patch.object(CI, "report_record", return_value={"counts": {"tests": 0}, "reports": []}):
            with self.assertRaisesRegex(OracleError, "XML disagrees"):
                CI.validate_bundle(self.root, "producer", self.identity)

    def test_unrelated_native_tasks_are_not_success_proof(self):
        evidence = self.make_bundle()
        evidence["tasks"] = [":core:data:compileDebugKotlin"]
        (self.root / "wp203-evidence.json").write_bytes(json_bytes(evidence))
        with self.assertRaisesRegex(OracleError, "mandatory tasks"):
            CI.validate_bundle(self.root, "producer", self.identity)

    def test_oversized_input_is_rejected_before_open(self):
        path = self.root / "controlled.meshcoreone"
        path.write_bytes(b"123456")
        with patch.object(CI, "MAXIMUM_DATA_BYTES", 4), patch.object(Path, "open") as opened:
            with self.assertRaisesRegex(OracleError, "oversized"):
                CI.digest_file(path)
            opened.assert_not_called()

    def test_local_identity_cannot_be_presented_as_hosted_execution(self):
        with patch.object(CI, "execution_identity", return_value=None):
            with self.assertRaisesRegex(OracleError, "actual hosted"):
                CI.identity()

    def test_failure_retention_preserves_raw_failure_without_claiming_success(self):
        source = self.root / "android" / "core" / "data" / "build" / "test-results" / "testDebugUnitTest"
        source.mkdir(parents=True)
        raw = b'<testsuite tests="1" failures="1"><testcase name="actual"><failure/></testcase></testsuite>'
        (source / "TEST-failure.xml").write_bytes(raw)
        output = self.root / "output"
        output.mkdir()
        with patch.object(CI, "REPO", self.root):
            CI.retain_native_xml(output, self.identity)
        self.assertEqual(raw, (output / "junit" / "TEST-failure.xml").read_bytes())
        evidence = json.loads((output / "raw-native-retention.json").read_text(encoding="utf8"))
        self.assertEqual(self.identity, evidence["identity"])
        self.assertNotIn("passed", evidence)
        self.assertEqual(1, len(evidence["reports"]))

    def test_missing_report_retention_is_not_a_zero_test_success_record(self):
        output = self.root / "output"
        output.mkdir()
        with patch.object(CI, "REPO", self.root):
            CI.retain_native_xml(output, self.identity)
        value = json.loads((output / "raw-native-retention.json").read_text(encoding="utf8"))
        self.assertEqual([], value["reports"])
        self.assertNotIn("result", value)


if __name__ == "__main__":
    unittest.main()
