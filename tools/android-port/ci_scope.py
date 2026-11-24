"""AndroidOnly: WP-003 Select expensive CI work; unknown inputs always receive the full run."""

import argparse
import os
from pathlib import Path
import subprocess


def scope(paths, event):
    full = dict(android=True, dependencies=True)
    if event != "pull_request":
        return full
    result = dict.fromkeys(full, False)
    for path in paths:
        if path.startswith(("docs/android/evidence/", "tools/android-port/", ".github/")):
            return full
        if ((path.startswith("docs/") and path.endswith(".md"))
                or path in ("README.md", "AGENTS.md", ".gitignore", ".gitattributes")):
            continue
        if not path.startswith("android/"):
            return full
        result["android"] = True
        if (path.endswith((".gradle.kts", ".gradle", ".lockfile"))
                or path.startswith(("android/gradle/", "android/build-logic/", "android/scaffold/"))
                or path in ("android/gradle.properties", "android/gradlew", "android/gradlew.bat")):
            return full
        if path.startswith("android/core/") and not path.startswith((
            "android/core/data/", "android/core/database/", "android/core/datastore/",
            "android/core/model/", "android/core/contracts/", "android/core/protocol/",
            "android/core/runtime/", "android/core/services/", "android/core/testing/",
            "android/core/ble/", "android/core/connectivity/", "android/core/designsystem/",
            "android/core/l10n/", "android/core/maps/", "android/core/ui/",
        )):
            return full
        if not path.startswith((
            "android/app/", "android/core/", "android/feature/", "android/platform/",
            "android/tools/", "android/accessibility-tests/", "android/parity-tests/",
            "android/ui-tests/", "android/screenshot-tests/", "android/benchmark/",
        )):
            return full
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--event", required=True)
    parser.add_argument("--base")
    args = parser.parse_args()
    paths = []
    if args.event == "pull_request":
        if not args.base:
            parser.error("pull_request requires --base")
        changed = subprocess.run(
            ["git", "diff", "--name-only", "-z", "--no-renames", f"{args.base}...HEAD"],
            check=True, capture_output=True,
        ).stdout
        paths = changed.decode("utf-8", errors="strict").rstrip("\0").split("\0") if changed else []
    selected = scope(paths, args.event)
    lines = [f"{name}={str(value).lower()}" for name, value in selected.items()]
    print("\n".join(lines))
    if output := os.environ.get("GITHUB_OUTPUT"):
        with Path(output).open("a", encoding="utf-8") as stream:
            stream.write("\n".join(lines) + "\n")
    if summary := os.environ.get("GITHUB_STEP_SUMMARY"):
        with Path(summary).open("a", encoding="utf-8") as stream:
            stream.write("## CI scope\n\n" + "\n".join(f"- `{line}`" for line in lines) + "\n")


if __name__ == "__main__":
    main()
