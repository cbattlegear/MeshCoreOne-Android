"""AndroidOnly: WP-003 Select changed-module checks; unknown inputs receive the full run."""

import argparse
import os
from pathlib import Path
import subprocess
import sys


JVM_MODULES = {
    "core/protocol", "core/model", "core/contracts", "core/runtime", "core/services",
    "tools/meshcli",
}
ANDROID_MODULES = {
    "core/database", "core/datastore", "core/data", "core/ble", "core/connectivity",
    "core/designsystem", "core/ui", "core/maps", "core/l10n",
    "feature/onboarding", "feature/chats", "feature/nodes", "feature/remotenodes",
    "feature/map", "feature/tools", "feature/settings",
    "platform/notifications", "platform/widgets", "platform/shortcuts",
}
APP_TEST_INPUTS = ("accessibility-tests/", "parity-tests/", "ui-tests/", "screenshot-tests/")


def full_scope():
    return dict(android=True, full=True, app_tests=True, apk=True, instrumentation=True, tasks=[])


def production_code(path):
    return path.endswith((".kt", ".java")) and any(
        f"/src/{variant}/{language}/" in path
        for variant in ("main", "debug")
        for language in ("kotlin", "java")
    )


def scope(paths, event):
    if event not in ("pull_request", "push"):
        return full_scope()
    result = dict(android=False, full=False, app_tests=False, apk=False, instrumentation=False, tasks=[])
    tasks = set()
    compile_app = False
    for path in paths:
        if path.startswith(("docs/android/evidence/", "tools/android-port/", ".github/")):
            return full_scope()
        if ((path.startswith("docs/") and path.endswith(".md"))
                or path in ("README.md", "AGENTS.md")):
            continue
        if not path.startswith("android/"):
            return full_scope()
        relative = path.removeprefix("android/")
        if (path.endswith((".gradle.kts", ".gradle", ".lockfile"))
                or relative.startswith(("gradle/", "build-logic/", "scaffold/", "benchmark/", "core/testing/"))
                or relative in ("gradle.properties", "gradlew", "gradlew.bat")):
            return full_scope()
        test_only = any(marker in path for marker in ("/src/test/", "/src/androidTest/"))
        if relative.startswith("app/") or relative.startswith(APP_TEST_INPUTS):
            result["android"] = result["app_tests"] = True
            if "/src/androidTest/" in path or "/src/shared/" in path:
                result["instrumentation"] = True
            if relative.startswith("app/") and not test_only:
                tasks.add(":app:lintDebug")
                if production_code(path):
                    compile_app = True
                else:
                    result["apk"] = True
            continue
        module = next(
            (name for name in JVM_MODULES | ANDROID_MODULES if relative.startswith(f"{name}/")),
            None,
        )
        if module is None:
            return full_scope()
        result["android"] = True
        project = ":" + module.replace("/", ":")
        if module in JVM_MODULES:
            tasks.add(f"{project}:test")
            if module == "tools/meshcli":
                tasks.add(":core:protocol:test")
        else:
            test = "verifyL10nTests" if module == "core/l10n" else "testDebugUnitTest"
            tasks.add(f"{project}:{test}")
            if not test_only:
                tasks.add(f"{project}:lintDebug")
            if "/src/androidTest/" in path:
                tasks.add(f"{project}:compileDebugAndroidTestKotlin")
        if module != "tools/meshcli" and not test_only:
            if production_code(path):
                compile_app = True
            else:
                result["apk"] = True
    if compile_app and not result["apk"]:
        tasks.update((":app:compileDebugKotlin", ":app:compileDebugJavaWithJavac"))
    result["tasks"] = sorted(tasks)
    return result


def changed_paths(event, base):
    if event == "pull_request" and not base:
        raise ValueError("pull_request requires --base")
    if event == "push" and (not base or set(base) == {"0"}):
        print("::warning::Push has no usable previous commit; selecting the full suite.", file=sys.stderr)
        return None
    if event not in ("pull_request", "push"):
        return []
    comparison = f"{base}...HEAD" if event == "pull_request" else f"{base}..HEAD"
    try:
        changed = subprocess.run(
            ["git", "diff", "--name-only", "-z", "--no-renames", comparison],
            check=True, capture_output=True,
        ).stdout
    except subprocess.CalledProcessError:
        if event != "push":
            raise
        print("::warning::Previous push commit unavailable; selecting the full suite.", file=sys.stderr)
        return None
    return changed.decode("utf-8", errors="strict").rstrip("\0").split("\0") if changed else []


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--event", required=True)
    parser.add_argument("--base")
    parser.add_argument("--focus", help="Manual run only: one changed path to select focused checks")
    args = parser.parse_args()
    if args.focus and args.event != "workflow_dispatch":
        parser.error("--focus is only supported for workflow_dispatch")
    try:
        paths = [args.focus] if args.focus else changed_paths(args.event, args.base)
    except ValueError as error:
        parser.error(str(error))
    selected = full_scope() if paths is None else scope(
        paths, "pull_request" if args.focus else args.event,
    )
    lines = [
        f"{name}={' '.join(value) if name == 'tasks' else str(value).lower()}"
        for name, value in selected.items()
    ]
    print("\n".join(lines))
    if output := os.environ.get("GITHUB_OUTPUT"):
        with Path(output).open("a", encoding="utf-8") as stream:
            stream.write("\n".join(lines) + "\n")
    if summary := os.environ.get("GITHUB_STEP_SUMMARY"):
        with Path(summary).open("a", encoding="utf-8") as stream:
            stream.write("## CI scope\n\n" + "\n".join(f"- `{line}`" for line in lines) + "\n")


if __name__ == "__main__":
    main()
