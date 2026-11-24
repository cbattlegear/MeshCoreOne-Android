"""AndroidOnly: WP-203 Require the actual current-upstream SwiftData XCTest result."""

import sys
import re
import xml.etree.ElementTree as ET
from pathlib import Path


def main():
    if len(sys.argv) != 3:
        raise SystemExit("Expected the Swift XCTest XML report and execution log")
    root = ET.parse(Path(sys.argv[1])).getroot()
    cases = root.findall(".//testcase")
    if len(cases) != 1:
        raise SystemExit("Expected exactly one current-upstream SwiftData test")
    case = cases[0]
    if (case.get("name", "").removesuffix("()") != "testActualSwiftDataInteroperability"
            or not case.get("classname", "").endswith("WP739InteropTests")
            or any(case.find(status) is not None for status in ("failure", "error", "skipped"))):
        raise SystemExit("Current-upstream SwiftData test did not pass")
    for suite in root.iter("testsuite"):
        if suite.findall("testcase"):
            if any(suite.get(key) != str(value) for key, value in (("tests", 1), ("failures", 0), ("errors", 0))):
                raise SystemExit("Swift XCTest XML counts do not match")
            if suite.get("skipped", "0") != "0":
                raise SystemExit("Swift XCTest XML reports skipped tests")
    log = Path(sys.argv[2]).read_text(encoding="utf-8")
    passed = re.findall(r"Test Case '[^'\n]*\btestActualSwiftDataInteroperability[^'\n]*' passed", log)
    if len(passed) != 1 or not re.search(r"Executed 1 test, with 0 failures \(0 unexpected\)", log):
        raise SystemExit("Swift execution log does not prove the test ran and passed")
    print("Current-upstream SwiftData XCTest: 1 passed")


if __name__ == "__main__":
    main()
