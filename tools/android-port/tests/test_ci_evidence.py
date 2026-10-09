"""AndroidOnly: WP-003 Validate live CI reports without checked-in result bundles."""

import ast
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

from controller.ci_evidence import (
    EXPECTED_APK_PERMISSIONS,
    counts,
    lint_evidence,
    suite_counts,
    validate_graph_runtime,
)
from controller.errors import PortError

REPO = Path(__file__).resolve().parents[3]


def junit_report(path: Path, tests=1, failures=0, errors=0, skipped=0):
    suite = ET.Element("testsuite", {
        "tests": str(tests),
        "failures": str(failures),
        "errors": str(errors),
        "skipped": str(skipped),
    })
    for index in range(tests):
        case = ET.SubElement(suite, "testcase", {"classname": "fixture.live", "name": f"case-{index}"})
        if index < failures:
            ET.SubElement(case, "failure")
        elif index < failures + errors:
            ET.SubElement(case, "error")
        elif index < failures + errors + skipped:
            ET.SubElement(case, "skipped")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(ET.tostring(suite))


class LiveReportTests(unittest.TestCase):
    def write_runtime_reports(self, root: Path, runtime_rows: list[str]):
        (root / "module-graph.tsv").write_text(
            "consumer\tproducer\tconfiguration\n:app\t:core:model\tdebugRuntimeClasspath\n",
            encoding="utf-8",
        )
        (root / "runtime-dependencies.tsv").write_text(
            "artifact\tdeclared_license\tlicense_url\tlicense_pom\tlicense_pom_sha256\tlegal_gate\n"
            + "\n".join(runtime_rows)
            + "\n",
            encoding="utf-8",
        )

    def test_runtime_component_may_declare_multiple_distinct_licenses(self):
        digest = "a" * 64
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.write_runtime_reports(root, [
                f"example:component:1\tApache-2.0\thttps://example.test/apache\texample:component:1\t{digest}\thuman-review-pending",
                f"example:component:1\tBSD-3-Clause\thttps://example.test/bsd\texample:component:1\t{digest}\thuman-review-pending",
            ])
            validate_graph_runtime(root)

    def test_duplicate_runtime_license_declaration_rejects(self):
        digest = "a" * 64
        row = (
            f"example:component:1\tApache-2.0\thttps://example.test/apache\t"
            f"example:component:1\t{digest}\thuman-review-pending"
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.write_runtime_reports(root, [row, row.replace(digest, "b" * 64)])
            with self.assertRaisesRegex(PortError, "Duplicate runtime license declaration"):
                validate_graph_runtime(root)

    def test_apk_inspector_and_controller_require_the_same_exact_permissions(self):
        module = ast.parse((REPO / "android/scaffold/inspect_apk.py").read_text(encoding="utf-8"))
        expected = next(
            ast.literal_eval(node.value)
            for node in ast.walk(module)
            if isinstance(node, ast.Assign)
            and any(isinstance(target, ast.Name) and target.id == "expected" for target in node.targets)
            and isinstance(node.value, ast.List)
            and any(
                isinstance(element, ast.Constant) and element.value == "android.permission.BLUETOOTH_CONNECT"
                for element in node.value.elts
            )
        )
        self.assertEqual(expected, EXPECTED_APK_PERMISSIONS)

    def test_complete_junit_report_has_positive_discovery(self):
        with tempfile.TemporaryDirectory() as temporary:
            report = Path(temporary) / "TEST-suite.xml"
            junit_report(report, tests=3)
            self.assertEqual(suite_counts(report.parent, 3)["passed"], 3)

    def test_zero_failed_error_and_skipped_discovery_reject(self):
        cases = (
            {"tests": 0},
            {"tests": 1, "failures": 1},
            {"tests": 1, "errors": 1},
            {"tests": 1, "skipped": 1},
        )
        for case in cases:
            with self.subTest(case=case), tempfile.TemporaryDirectory() as temporary:
                report = Path(temporary) / "TEST-suite.xml"
                junit_report(report, **case)
                with self.assertRaises(PortError):
                    suite_counts(report.parent, 1)

    def test_claimed_counts_must_be_consistent_and_positive(self):
        valid = {"discovered": 2, "run": 2, "passed": 2, "failed": 0, "errors": 0, "skipped": 0}
        self.assertEqual(counts(valid, minimum=2), valid)
        for mutation in (
            {**valid, "discovered": 0, "run": 0, "passed": 0},
            {**valid, "failed": 1, "passed": 1},
            {**valid, "run": 1},
        ):
            with self.subTest(mutation=mutation), self.assertRaises(PortError):
                counts(mutation, minimum=1)

    def test_lint_errors_reject_live_report(self):
        with tempfile.TemporaryDirectory() as temporary:
            report = Path(temporary) / "lint.xml"
            report.write_text(
                '<issues format="6" by="lint fixture"><issue severity="Warning" id="W" message="warning"/></issues>',
                encoding="utf-8",
            )
            self.assertEqual(lint_evidence(report)["warnings"], 1)
            report.write_text(
                '<issues format="6" by="lint fixture"><issue severity="Error" id="E" message="error"/></issues>',
                encoding="utf-8",
            )
            with self.assertRaisesRegex(PortError, "lint contains actual errors"):
                lint_evidence(report)

    def test_malformed_junit_rejects(self):
        with tempfile.TemporaryDirectory() as temporary:
            report = Path(temporary) / "TEST-suite.xml"
            report.write_text("<testsuite>", encoding="utf-8")
            with self.assertRaises(PortError):
                suite_counts(report.parent, 1)


if __name__ == "__main__":
    unittest.main()
