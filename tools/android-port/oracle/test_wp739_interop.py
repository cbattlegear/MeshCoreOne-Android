"""AndroidOnly: WP-203 Fail-closed checks for the current SwiftData evidence reader."""

import contextlib
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from oracle import wp739_stage, wp739_verify


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


class UpstreamBuildFixTest(unittest.TestCase):
    def test_scroll_cancellation_captures_self_by_value_in_main_actor_task(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "ChatCoordinator+Mutations.swift"
            path.write_text(wp739_stage.SCROLL_CANCEL, encoding="utf-8")
            wp739_stage.replace_once(path, wp739_stage.SCROLL_CANCEL, wp739_stage.SCROLL_CANCEL_FIXED)
            self.assertIn("Task { @MainActor [weak self] in", path.read_text(encoding="utf-8"))
            with self.assertRaises(RuntimeError):
                wp739_stage.replace_once(path, wp739_stage.SCROLL_CANCEL, wp739_stage.SCROLL_CANCEL_FIXED)

    def test_room_send_fix_is_scoped_and_fails_closed(self):
        source = f"before\n{wp739_stage.ROOM_SEND_START}\nbody\n{wp739_stage.ROOM_SEND_END}\nafter"
        expected = f"before\n{wp739_stage.ROOM_SEND_START_FIXED}\nbody\n{wp739_stage.ROOM_SEND_END_FIXED}\nafter"
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "RoomServerService.swift"
            path.write_text(source, encoding="utf-8")
            wp739_stage.replace_once(path, wp739_stage.ROOM_SEND_START, wp739_stage.ROOM_SEND_START_FIXED)
            wp739_stage.replace_once(path, wp739_stage.ROOM_SEND_END, wp739_stage.ROOM_SEND_END_FIXED)
            self.assertEqual(expected, path.read_text(encoding="utf-8"))
            with self.assertRaises(RuntimeError):
                wp739_stage.replace_once(path, wp739_stage.ROOM_SEND_START, wp739_stage.ROOM_SEND_START_FIXED)

    def test_unrelated_send_is_disabled_without_touching_neighboring_code(self):
        source = "before\n  func send() async throws -> MessageDTO {\n    defer { await cleanup() }\n  }\nafter"
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "Send.swift"
            path.write_text(source, encoding="utf-8")
            wp739_stage.disable_unrelated_send(path, "  func send(", "\n  }\nafter", ") async throws -> MessageDTO {")
            self.assertEqual(
                'before\n  func send() async throws -> MessageDTO {\n'
                '    fatalError("Direct-message sending is unavailable in the backup oracle")\n  }\nafter',
                path.read_text(encoding="utf-8"),
            )
            with self.assertRaises(RuntimeError):
                wp739_stage.disable_unrelated_send(path, "  func send(", "\n  }\nafter", ") async throws -> MessageDTO {")


if __name__ == "__main__":
    unittest.main()
