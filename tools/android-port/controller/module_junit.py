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
NOTIFICATION_INSTRUMENTATION_ROOTS = ("src/androidTest/kotlin/", "src/androidTest/java/")
NOTIFICATION_RESOURCE_ROOTS = tuple(
    "src/" + source_set + "/resources" for source_set in ("main", "debug", "test", "testDebug")
)
NOTIFICATION_INSTRUMENTATION_BUILD = "102fc68b8cde016474398764f0e99f766e00ee18"
NOTIFICATION_INSTRUMENTATION_TASK = ":platform:notifications:compileDebugAndroidTestKotlin"
NOTIFICATION_REPLY_SOURCES = {
    NOTIFICATION_ROOT + "/NotificationReply" + name + "Test.kt"
    for name in ("Admission", "Capacity", "Enrollment", "Issuance", "Lifecycle", "Publication", "StagingStore")
}
NOTIFICATION_INSTRUMENTATION_BASELINE = {
    NOTIFICATION_ROOT + "/" + name + ".kt"
    for name in ("AndroidMessagingNotificationDeliveryTest", "NotificationPayloadCodecTest",
                 "NotificationActionDispatcherTest")
} | {"src/androidTest/kotlin/com/meshcoreone/android/platform/notifications/status/ConnectionStatusTests.kt"}
# Complete build profiles subject to trusted review, not a Gradle expression interpreter.
JVM_REMAPS = {
    "platform/notifications": {
        "builds": {"9c5e1f4f6ed16f68ff629e6768afdd9185d2da61", "1edcfc21437134ff7eb4bc431ffd6358c6967bab",
                   NOTIFICATION_INSTRUMENTATION_BUILD},
        "roots": (NOTIFICATION_ROOT,),
        "runner": "testDebugUnitTest",
    },
}
JVM_REMAPPED_TASKS = {
    module: ":" + module.replace("/", ":") + ":" + profile["runner"]
    for module, profile in JVM_REMAPS.items()
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
        resource_roots = NOTIFICATION_RESOURCE_ROOTS if module in JVM_REMAPS else ()
        instrumentation_roots = (
            ("src/androidTest/",)
            if module == "platform/notifications" and build[2] == NOTIFICATION_INSTRUMENTATION_BUILD else ()
        )
        for relative, (mode, kind, blob) in files.items():
            if relative != "build.gradle.kts" and not relative.startswith(
                ("src/main/", "src/test/", "src/testDebug/", *(root + "/" for root in roots),
                 *(root + "/" for root in resource_roots), *instrumentation_roots)
            ):
                continue
            path = f"android/{module}/{relative}"
            if mode not in ("100644", "100755") or kind != "blob" or SHA.fullmatch(blob) is None:
                raise PortError("Unsafe/non-file committed module test input: " + path)
            inputs[path] = blob
        entries[module] = dict(sorted(inputs.items()))
    return entries


def remapped_tasks(repo, revision=None):
    return [
        JVM_REMAPPED_TASKS[module]
        for module in module_sources(repo, revision) if module in JVM_REMAPS
    ]


def select_remapped_tasks(tasks, remapped):
    return list(dict.fromkeys([
        *(task for task in tasks if task not in JVM_REMAPPED_TASKS.values() or task in remapped),
        *remapped,
    ]))


def notification_instrumentation_tasks(repo, revision=None):
    module = "platform/notifications"
    inputs = module_sources(repo, revision).get(module, {})
    if inputs.get(f"android/{module}/build.gradle.kts") == NOTIFICATION_INSTRUMENTATION_BUILD:
        return [NOTIFICATION_INSTRUMENTATION_TASK]
    return []


def notification_instrumentation_candidate(repo, revision=None):
    module = "platform/notifications"
    prefix = f"android/{module}/"
    inputs = module_sources(repo, revision).get(module, {})
    if inputs.get(prefix + "build.gradle.kts") != NOTIFICATION_INSTRUMENTATION_BUILD:
        return None
    replies = {prefix + relative for relative in NOTIFICATION_REPLY_SOURCES}
    present = replies & inputs.keys()
    if present and present != replies:
        raise PortError("Incomplete committed notification reply source family")
    expected = {}
    for source, blob in inputs.items():
        relative = source[len(prefix):]
        if not relative.startswith(NOTIFICATION_INSTRUMENTATION_ROOTS):
            continue
        if relative.endswith(".java"):
            raise PortError("Unsupported Java notification instrumentation input: " + source)
        if relative.endswith(".kt") and source not in replies:
            expected[source] = blob
    if not {prefix + relative for relative in NOTIFICATION_INSTRUMENTATION_BASELINE} <= expected.keys():
        raise PortError("Missing committed baseline notification instrumentation sources")
    return {
        "task": NOTIFICATION_INSTRUMENTATION_TASK,
        "sources": expected,
        "excluded_reply_sources": sorted(present),
        "jvm_sources": _notification_jvm_test_sources(module, inputs),
        "reply_jvm_sources": {path: inputs[path] for path in sorted(present)},
    }


def notification_instrumentation_evidence(repo, path, tasks, revision=None):
    from .ci_evidence import read_test_sources

    runner = ":platform:notifications:testDebugUnitTest"
    if not ({runner, NOTIFICATION_INSTRUMENTATION_TASK} & set(tasks)):
        return {}
    expected = notification_instrumentation_candidate(repo, revision)
    if expected is None:
        if NOTIFICATION_INSTRUMENTATION_TASK in tasks:
            raise PortError("Notification instrumentation compiler task requires an approved build profile")
        return {}
    if not {runner, expected["task"]} <= set(tasks):
        raise PortError("Notification instrumentation proof requires both declared compilation and JVM runner")
    module = "platform/notifications"
    check_module_inputs(repo, [module], revision)
    actual = read_test_sources(path, {module: "compileDebugAndroidTestKotlin"})
    if actual != {module: expected["sources"]}:
        raise PortError("Actual notification instrumentation compilation differs from immutable sources")
    return {
        key: value for key, value in expected.items()
        if key not in ("jvm_sources", "reply_jvm_sources")
    }


def check_module_inputs(repo, modules, revision=None):
    revision = revision or git(repo, "rev-parse", "HEAD").decode().strip()
    paths = []
    for module in modules:
        build = f"android/{module}/build.gradle.kts"
        blob = git(repo, "rev-parse", revision + ":" + build).decode().strip() if module in JVM_REMAPS else None
        resource_roots = NOTIFICATION_RESOURCE_ROOTS if module in JVM_REMAPS else ()
        paths.extend(f"android/{module}/{relative}" for relative in (
            "build.gradle.kts", "src/main", "src/test", "src/testDebug", *remapped_roots(module, blob),
            *resource_roots,
            *(("src/androidTest",) if blob == NOTIFICATION_INSTRUMENTATION_BUILD else ()),
        ))
    if paths and (
        git(repo, "diff", "--name-only", revision, "--", *paths).strip()
        or git(repo, "ls-files", "--others", "-z", "--", *paths).strip()
    ):
        raise PortError("Compiled module inputs differ from the immutable candidate")


def _notification_jvm_test_sources(module, inputs):
    prefix = f"android/{module}/"
    roots = JVM_REMAPS[module]["roots"]
    sources = {}
    for path, blob in inputs.items():
        if not path.startswith(prefix) or not unit_source(path[len(prefix):], roots):
            continue
        if not path.endswith(".kt"):
            raise PortError("Unsupported Java notification JVM test input: " + path)
        sources[path] = blob
    return sources


def _notification_remapped_sources(module, inputs):
    prefix = f"android/{module}/"
    roots = JVM_REMAPS[module]["roots"]
    return {
        path: blob for path, blob in inputs.items()
        if path.startswith(prefix) and any(
            path[len(prefix):].startswith(root + "/") for root in roots
        )
    }


def candidate_source_tree(repo, revision):
    result = {}
    for entry in git(repo, "ls-tree", "-rz", "--full-tree", revision).split(b"\0"):
        if not entry:
            continue
        metadata, raw_path = entry.split(b"\t", 1)
        mode, kind, blob = metadata.decode("ascii").split()
        path = git_path(raw_path.decode("utf-8"))
        if kind != "blob" or not path.endswith((".kt", ".java")):
            continue
        if mode not in ("100644", "100755") or SHA.fullmatch(blob) is None:
            raise PortError("Unsafe committed Kotlin/Java candidate source: " + path)
        result[path] = blob
    return result


def require_remapped_cases(repo, module, directory, inputs, compiled_sources, revision):
    if module not in JVM_REMAPS:
        return
    from .ci_evidence import suite_case_ids
    from .repair_acceptance import _kotlin_junit_lineage, _kotlin_junit_methods, validate_hosted_methods

    sources = _notification_remapped_sources(module, inputs)
    expected = _notification_jvm_test_sources(module, inputs)
    required = set(NOTIFICATION_CASES)
    if not isinstance(compiled_sources, dict) or not isinstance(compiled_sources.get(module), dict):
        raise PortError("Missing immutable compiled notification test-source inventory")
    if not sources or not expected or compiled_sources[module] != expected:
        raise PortError("Actual JVM compilation differs from immutable notification test sources: " + module)
    candidate_tree = candidate_source_tree(repo, revision)
    lineage = _kotlin_junit_lineage(candidate_tree, repo, compiled_sources)
    records = suite_case_ids(directory)
    observed = {(case["class"], case["method"]) for case in records}
    if not required <= observed:
        raise PortError("Missing mandatory remapped JVM named cases: " + str(sorted(required - observed)))
    resource_packages = set()
    for root in NOTIFICATION_RESOURCE_ROOTS:
        prefix = f"android/{module}/{root}/"
        for path in inputs:
            if not path.startswith(prefix):
                continue
            relative = path[len(prefix):]
            if relative == "robolectric.properties":
                resource_packages.add("")
            elif relative.endswith("/robolectric.properties"):
                resource_packages.add(relative.rsplit("/", 1)[0])

    def require_sdk_cases(path, methods, classes, sdk_configurations, *, remapped):
        expected_cases = set()
        configured_methods = set()
        for method in methods:
            configured_sdks = sdk_configurations[method]
            if configured_sdks is None:
                package = classes[method].rpartition(".")[0].replace(".", "/")
                if any(not parent or package == parent or package.startswith(parent + "/")
                       for parent in resource_packages):
                    raise PortError(
                        "Unresolved package Robolectric SDK configuration requires a literal "
                        "method/class SDK override: " + path + "#" + method
                    )
                if not remapped:
                    continue
                names = {method}
            elif configured_sdks in ((31,), (37,)):
                names = {method}
            elif configured_sdks == (31, 37):
                names = {method, method + "[31]"}
            else:
                raise PortError("Unsupported notification SDK family: " + path + "#" + method)
            configured_methods.add(method)
            expected_cases.update((classes[method], name) for name in names)
        actual_cases = [
            (case["class"], case["method"])
            for case in records if (
                remapped and case["class"] in set(classes.values())
                or not remapped and any(
                    case["class"] == classes[method] and (
                        case["method"] == method or case["method"].startswith(method + "[")
                        or case["method"].startswith(method + "()")
                    ) for method in configured_methods
                )
            )
        ]
        if len(actual_cases) != len(expected_cases) or set(actual_cases) != expected_cases:
            raise PortError("Missing, duplicate, or unexpected notification SDK cases: " + path)

    coverage, compiled_helpers = [], []
    for path in sources:
        classes, sdk_configurations = {}, {}
        methods = _kotlin_junit_methods(
            git(repo, "cat-file", "blob", inputs[path]).decode("utf-8"),
            classes, sdk_configurations=sdk_configurations, inheritance=lineage(path),
        )
        if not methods or any(kind != "test" for kind in methods.values()):
            raise PortError("Unsupported remapped notification JVM test family: " + path)
        require_sdk_cases(path, methods, classes, sdk_configurations, remapped=True)
        coverage.append({
            "path": path, "modules": [module],
            "native_methods": [classes[method] + "#" + method for method in methods],
            "hosted_methods": [],
        })
    for path, blob in expected.items():
        if path in sources:
            continue
        classes, sdk_configurations = {}, {}
        methods = _kotlin_junit_methods(
            git(repo, "cat-file", "blob", blob).decode("utf-8"),
            classes, sdk_configurations=sdk_configurations, inheritance=lineage(path),
        )
        if any(kind != "test" for kind in methods.values()):
            raise PortError("Unsupported conventional notification JVM test family: " + path)
        require_sdk_cases(path, methods, classes, sdk_configurations, remapped=False)
        if not methods:
            compiled_helpers.append(path)
            continue
        coverage.append({
            "path": path, "modules": [module], "native_methods": [],
            "hosted_methods": [
                {"path": path, "blob_sha": blob, "id": method} for method in methods
            ],
        })
    validate_hosted_methods(
        {"schema_version": 3, "kind": "whole-wp", "coverage": coverage},
        candidate_tree, repo, {module: records}, compiled_sources,
        compiled_helpers=compiled_helpers,
    )


def validate_remapped_compilation(repo, compiled_sources, modules, revision=None):
    selected = set(modules) & JVM_REMAPS.keys()
    if not selected:
        return {}
    if not isinstance(compiled_sources, dict):
        raise PortError("Missing compiled module source inventory")
    revision = revision or git(repo, "rev-parse", "HEAD").decode().strip()
    expected = module_sources(repo, revision)
    check_module_inputs(repo, selected, revision)
    for module in selected:
        sources = _notification_jvm_test_sources(module, expected.get(module, {}))
        actual = compiled_sources.get(module)
        if not sources or not isinstance(actual, dict) or actual != sources:
            raise PortError("Actual JVM compilation differs from immutable notification test sources: " + module)
    return {module: expected[module] for module in sorted(selected)}


def check_module_tests(repo: Path, compiled_sources=None, revision=None):
    from .ci_evidence import suite_counts

    revision = revision or git(repo, "rev-parse", "HEAD").decode().strip()
    expected = module_sources(repo, revision)
    check_module_inputs(repo, expected, revision)
    results = {}
    for module, inputs in expected.items():
        if module in JVM_REMAPS and compiled_sources is not None:
            validate_remapped_compilation(repo, compiled_sources, [module], revision)
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
        require_remapped_cases(repo, module, candidates[0], inputs, compiled_sources, revision)
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
