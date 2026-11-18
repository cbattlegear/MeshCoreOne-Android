"""AndroidOnly: WP-203 Frozen source staging and actual Swift-report rejection tests."""

from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from oracle import wp203_interop as INTEROP
from oracle.reference import OracleError


class SwiftReportTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="wp203-swift-xml-")
        self.root = Path(self.temporary.name)
        self.path = self.root / "swift.xml"

    def tearDown(self):
        self.temporary.cleanup()

    def write(self, body=None, tests=1, failures=0, errors=0, skipped=0):
        body = body if body is not None else (
            f'<testcase classname="MC1ServicesTests.WP203InteropTests" name="{INTEROP.TEST_NAME}"/>')
        self.path.write_text(
            f'<testsuites><testsuite tests="{tests}" failures="{failures}" errors="{errors}" skipped="{skipped}">'
            f'{body}</testsuite></testsuites>', encoding="utf8")

    def test_exact_real_test_identity_and_xml_hash_are_retained(self):
        self.write()
        report = INTEROP.verify_swift_xml(self.path)
        self.assertEqual(INTEROP.TEST_NAME, report["test"])
        self.assertEqual(1, report["discovered"])
        self.assertEqual(1, report["passed"])
        self.assertEqual(INTEROP.sha256(self.path.read_bytes()), report["xml_sha256"])

    def test_missing_zero_or_multiple_testcases_fail(self):
        for body in ("", "<testcase/><testcase/>"):
            with self.subTest(body=body):
                self.write(body)
                with self.assertRaisesRegex(OracleError, "exactly one"):
                    INTEROP.verify_swift_xml(self.path)

    def test_proxy_or_renamed_swift_case_fails(self):
        self.write('<testcase classname="Other.Proxy" name="compiles"/>')
        with self.assertRaisesRegex(OracleError, "proxy"):
            INTEROP.verify_swift_xml(self.path)

    def test_failure_error_and_skips_never_count_as_passed(self):
        for tag in ("failure", "error", "skipped"):
            with self.subTest(tag=tag):
                self.write(
                    f'<testcase classname="MC1ServicesTests.WP203InteropTests" name="{INTEROP.TEST_NAME}"><{tag}/></testcase>')
                with self.assertRaisesRegex(OracleError, "failed or skipped"):
                    INTEROP.verify_swift_xml(self.path)

    def test_declared_suite_counts_cannot_hide_zero_assertion_runner(self):
        self.write(tests=0)
        with self.assertRaisesRegex(OracleError, "count/outcome"):
            INTEROP.verify_swift_xml(self.path)

    def test_encoded_xml_declarations_never_reach_parser(self):
        for encoding in ("utf8", "utf-16", "utf-16-le", "utf-16-be", "utf-32", "utf-32-le", "utf-32-be"):
            with self.subTest(encoding=encoding):
                self.path.write_bytes('<!DOCTYPE testsuite [<!ENTITY value "unsafe">]><testsuite/>'.encode(encoding))
                with patch.object(INTEROP.ET, "fromstring") as parser:
                    with self.assertRaisesRegex(OracleError, "Unsafe"):
                        INTEROP.verify_swift_xml(self.path)
                    parser.assert_not_called()

    def test_missing_suite_outcome_counter_fails_closed(self):
        self.write()
        raw = self.path.read_text(encoding="utf8").replace(' skipped="0"', "")
        self.path.write_text(raw, encoding="utf8")
        with self.assertRaisesRegex(OracleError, "count/outcome"):
            INTEROP.verify_swift_xml(self.path)

    def test_actual_swiftpm_xunit_shape_requires_unskipped_framework_trace(self):
        self.write()
        self.path.write_text(self.path.read_text(encoding="utf8").replace(' skipped="0"', ""), encoding="utf8")
        log = self.root / "execution.log"
        log.write_text(
            f"Test Case '-[MC1ServicesTests.WP203InteropTests {INTEROP.TEST_NAME}]' passed (0.1 seconds).\n"
            "Executed 1 test, with 0 failures (0 unexpected) in 0.1 seconds\n", encoding="utf8")
        report = INTEROP.verify_swift_xml(self.path, log)
        self.assertEqual(INTEROP.sha256(log.read_bytes()), report["execution_log_sha256"])
        self.assertEqual(1, report["passed"])

    def test_skipped_or_missing_framework_trace_never_substitutes_for_pass(self):
        self.write()
        log = self.root / "execution.log"
        for text in ("Executed 0 tests", f"Test Case '-[MC1ServicesTests.WP203InteropTests {INTEROP.TEST_NAME}]' skipped"):
            with self.subTest(text=text):
                log.write_text(text, encoding="utf8")
                with self.assertRaisesRegex(OracleError, "mandatory unskipped"):
                    INTEROP.verify_swift_xml(self.path, log)

    def test_windows_staging_cannot_claim_mac_execution(self):
        with patch.object(INTEROP.platform, "system", return_value="Windows"):
            with self.assertRaisesRegex(OracleError, "Windows staging is not execution"):
                INTEROP.run(self.root / "input", self.root / "output", self.root / "stage")

    def test_existing_stage_is_not_overwritten(self):
        with self.assertRaisesRegex(OracleError, "new absolute"):
            INTEROP.stage_package(None, self.root)


if __name__ == "__main__":
    unittest.main()
