"""AndroidOnly: WP-003 Retain every active root-module unit suite without claiming source parity."""

import os
import re
import stat
from pathlib import Path

from .errors import PortError
from .model import SHA, git
from .paths import git_path

MODULE_SOURCE = re.compile(r"android/((?:core|feature|platform)/[a-z][a-z0-9-]*)/(.+)")
UNIT_SOURCE = re.compile(r"src/(?:test|testDebug)/(?:java|kotlin)/.+\.(?:java|kt)")
SEPARATE_SUITES = {"core/contracts", "core/protocol", "core/testing"}
REPORT_LOCATIONS = ("build/test-results/test", "build/test-results/testDebugUnitTest")
NOTIFICATION_ROOT = "src/androidTest/kotlin/com/meshcoreone/android/platform/notifications/messaging"
# Complete build profiles subject to trusted review, not a Gradle expression interpreter.
JVM_REMAPS = {
    "platform/notifications": {
        "builds": {"9c5e1f4f6ed16f68ff629e6768afdd9185d2da61", "1edcfc21437134ff7eb4bc431ffd6358c6967bab"},
        "roots": (NOTIFICATION_ROOT,),
        "runner": "testDebugUnitTest",
    },
}
NOTIFICATION_CASES = {
    ("com.meshcoreone.android.platform.notifications.messaging." + suite, method)
    for suite, methods in {
        "AndroidMessagingNotificationDeliveryTest": (
            "messagesUseMessagingStyleAndOnlyReplyIsMutable",
            "deniedPermissionReturnsTypedOutcomeAndNeverThrowsIntoMessaging",
        ),
        "NotificationPayloadCodecTest": (
            "everyTypedPayloadRoundTripsWithoutMessageContent", "malformedOrOutOfRangePayloadFailsClosed",
        ),
        "NotificationActionDispatcherTest": (
            "coldStartKeepsActionUntilTheCurrentGraphIsReady",
            "staleRadioIdentityRemainsAttachedForTheSessionHandlerGuard",
        ),
    }.items() for method in methods
}


def remapped_roots(module, build_blob):
    profile = JVM_REMAPS.get(module)
    if profile is None:
        return ()
    if build_blob not in profile["builds"]:
        raise PortError("Missing/unapproved immutable JVM source mapping build: " + module)
    return profile["roots"]


def unit_source(relative, roots=()):
    return bool(UNIT_SOURCE.fullmatch(relative)) or (
        relative.endswith((".kt", ".java"))
        and any(relative.startswith(root + "/") for root in roots)
    )


def module_sources(repo: Path, revision=None):
    revision = revision or git(repo, "rev-parse", "HEAD").decode().strip()
    if not isinstance(revision, str) or SHA.fullmatch(revision) is None:
        raise PortError("Module unit evidence requires an immutable candidate commit")
    if git(repo, "cat-file", "-t", revision).decode().strip() != "commit":
        raise PortError("Module unit evidence requires an immutable candidate commit")
    committed = {}
    for entry in git(repo, "ls-tree", "-rz", "--full-tree", revision, "--", "android").split(b"\0"):
        if not entry:
            continue
        metadata, raw_path = entry.split(b"\t", 1)
        path = git_path(raw_path.decode("utf-8"))
        match = MODULE_SOURCE.fullmatch(path)
        if match is None or match[1] in SEPARATE_SUITES:
            continue
        mode, kind, blob = metadata.decode("ascii").split()
        committed.setdefault(match[1], {})[match[2]] = (mode, kind, blob)
    entries = {}
    for module, files in sorted(committed.items()):
        build = files.get("build.gradle.kts")
        roots = remapped_roots(module, build[2] if build else None)
        if roots and not all(any(unit_source(relative, (root,)) and relative.startswith(root + "/")
                                 for relative in files) for root in roots):
            raise PortError("Missing committed remapped JVM test sources: " + module)
        if not any(unit_source(relative, roots) for relative in files):
            continue
        inputs = {}
        for relative, (mode, kind, blob) in files.items():
            if relative != "build.gradle.kts" and not relative.startswith(
                ("src/main/", "src/test/", "src/testDebug/", *(root + "/" for root in roots))
            ):
                continue
            path = f"android/{module}/{relative}"
            if mode not in ("100644", "100755") or kind != "blob" or SHA.fullmatch(blob) is None:
                raise PortError("Unsafe/non-file committed module test input: " + path)
            inputs[path] = blob
        entries[module] = dict(sorted(inputs.items()))
    return entries


def remapped_tasks(repo):
    return [
        ":" + module.replace("/", ":") + ":" + JVM_REMAPS[module]["runner"]
        for module in module_sources(repo) if module in JVM_REMAPS
    ]


def check_module_inputs(repo, modules):
    paths = []
    for module in modules:
        build = f"android/{module}/build.gradle.kts"
        blob = git(repo, "rev-parse", "HEAD:" + build).decode().strip() if module in JVM_REMAPS else None
        paths.extend(f"android/{module}/{relative}" for relative in (
            "build.gradle.kts", "src/main", "src/test", "src/testDebug", *remapped_roots(module, blob),
        ))
    if paths and (
        git(repo, "diff", "--name-only", "HEAD", "--", *paths).strip()
        or git(repo, "ls-files", "--others", "-z", "--", *paths).strip()
    ):
        raise PortError("Compiled module inputs differ from the immutable candidate")


def require_remapped_cases(repo, module, directory, inputs):
    if module not in JVM_REMAPS:
        return
    from .ci_evidence import suite_case_ids
    from .repair_acceptance import _kotlin_junit_methods

    required = set(NOTIFICATION_CASES)
    for path, blob in inputs.items():
        if not path.startswith(f"android/{module}/{NOTIFICATION_ROOT}/") or not path.endswith(".kt"):
            continue
        classes = {}
        methods = _kotlin_junit_methods(
            git(repo, "cat-file", "blob", blob).decode("utf-8"), classes, reject_inherited=True,
        )
        if any(kind != "test" for kind in methods.values()):
            raise PortError("Unsupported remapped JVM test family: " + path)
        required.update((classes[method], method) for method in methods)
    observed = {(case["class"], case["method"]) for case in suite_case_ids(directory)}
    if not required <= observed:
        raise PortError("Missing mandatory remapped JVM named cases: " + str(sorted(required - observed)))


def validate_remapped_compilation(repo, compiled_sources, modules):
    selected = set(modules) & JVM_REMAPS.keys()
    if not selected:
        return {}
    expected = module_sources(repo)
    check_module_inputs(repo, selected)
    for module in selected:
        sources = {
            path: blob for path, blob in expected.get(module, {}).items()
            if path.startswith(f"android/{module}/{NOTIFICATION_ROOT}/") and path.endswith(".kt")
        }
        actual = {
            path: blob for path, blob in compiled_sources.get(module, {}).items()
            if path.startswith(f"android/{module}/{NOTIFICATION_ROOT}/")
        }
        if not sources or actual != sources:
            raise PortError("Actual JVM compilation omits/changes immutable remapped sources: " + module)
    return {module: expected[module] for module in sorted(selected)}


def check_module_tests(repo: Path):
    from .ci_evidence import suite_counts

    expected = module_sources(repo)
    check_module_inputs(repo, expected)
    results = {}
    for module, inputs in expected.items():
        module_root = repo / "android" / module
        candidates = [
            module_root / relative for relative in REPORT_LOCATIONS
            if any((module_root / relative).glob("TEST-*.xml"))
        ]
        if len(candidates) != 1:
            raise PortError("Missing/ambiguous actual unit runner for active module: " + module)
        if module in JVM_REMAPS and candidates[0].name != JVM_REMAPS[module]["runner"]:
            raise PortError("Wrong actual remapped JVM unit runner: " + module)
        results[module] = suite_counts(candidates[0], 1)
        require_remapped_cases(repo, module, candidates[0], inputs)
    return results


def module_method_inventory(repo: Path, modules):
    from .ci_evidence import SUITES, suite_case_ids

    result = {
        "app": suite_case_ids(repo / "android" / SUITES["app"][0]),
        "core/contracts": suite_case_ids(repo / "android" / SUITES["contracts"][0]),
    }
    for module in modules:
        locations = [
            repo / "android" / module / relative for relative in REPORT_LOCATIONS
            if any((repo / "android" / module / relative).glob("TEST-*.xml"))
        ]
        if len(locations) != 1:
            raise PortError("Missing/ambiguous executed method inventory for module: " + module)
        result[module] = suite_case_ids(locations[0])
    return result


def linked(path: Path):
    if path.is_symlink():
        return True
    return os.name == "nt" and path.exists() and bool(path.lstat().st_file_attributes & stat.FILE_ATTRIBUTE_REPARSE_POINT)


def bounded_directory(directory: Path, root: Path):
    if not directory.resolve().is_relative_to(root.resolve()):
        raise PortError("Unit report directory escapes its bounded root")
    for parent in (directory, *directory.parents):
        if linked(parent):
            raise PortError("Linked unit-report directory is not evidence")
        if parent == root:
            break


def safe_reports(directory: Path, root: Path):
    bounded_directory(directory, root)
    files = sorted(directory.glob("TEST-*.xml"), key=lambda path: path.name)
    if not files:
        raise PortError("Missing mandatory active-module JUnit reports: " + str(directory))
    if any(linked(path) or not path.is_file() for path in files):
        raise PortError("Unsafe active-module JUnit report")
    return files


def report_record(root: Path, directory: Path):
    from .ci_environment import file_sha256
    from .ci_evidence import suite_counts

    files = safe_reports(directory, root)
    return {
        "counts": suite_counts(directory, 1),
        "reports": [{
            "path": path.relative_to(root).as_posix(),
            "size": path.stat().st_size,
            "sha256": file_sha256(path),
        } for path in files],
    }
