"""AndroidOnly: WP-203 Fail-closed checks for the current SwiftData evidence reader."""

import contextlib
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from oracle import wp739_verify


class SwiftDataReportTest(unittest.TestCase):
    def check(self, contents):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "results.xml"
            report.write_text(contents, encoding="utf-8")
            log = Path(directory) / "run.log"
            log.write_text("Test Case '-[MC1ServicesTests.WP739InteropTests testActualSwiftDataInteroperability]' passed\n"
                           "Executed 1 test, with 0 failures (0 unexpected)", encoding="utf-8")
            with patch("sys.argv", ["wp739_verify", str(report), str(log)]), contextlib.redirect_stdout(io.StringIO()):
                wp739_verify.main()

    def test_exact_passing_case(self):
        self.check('<testsuite tests="1" failures="0" errors="0"><testcase classname="MC1ServicesTests.WP739InteropTests" '
                   'name="testActualSwiftDataInteroperability"/></testsuite>')

    def test_missing_skipped_and_unrelated_cases_are_rejected(self):
        for contents in (
            '<testsuite/>',
            '<testsuite><testcase classname="MC1ServicesTests.WP739InteropTests" '
            'name="testActualSwiftDataInteroperability"><skipped/></testcase></testsuite>',
            '<testsuite><testcase classname="Unrelated" name="testActualSwiftDataInteroperability"/></testsuite>',
        ):
            with self.subTest(contents=contents), self.assertRaises(SystemExit):
                self.check(contents)


if __name__ == "__main__":
    unittest.main()
