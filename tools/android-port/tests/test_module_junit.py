"""AndroidOnly: WP-003 Validate active-module JUnit directly in build outputs."""

import contextlib
import io
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
from unittest.mock import patch

from controller.errors import PortError
from controller.module_junit import (
    NOTIFICATION_CASES, NOTIFICATION_ROOT, check_module_tests,
    module_method_inventory, module_sources, remapped_tasks, validate_remapped_compilation,
)
from test_ci_evidence import junit_report

NOTIFICATION_FIXTURE_COMMIT = "1e1ff70345f203b41f9a8b04836c2cc4640500c9"


def notification_fixture(write):
    root = Path(__file__).resolve().parents[3]
    paths = ["build.gradle.kts"] + [
        NOTIFICATION_ROOT + "/" + name + ".kt" for name in (
            "AndroidMessagingNotificationDeliveryTest", "NotificationPayloadCodecTest",
            "NotificationActionDispatcherTest",
        )
    ]
    for relative in paths:
        content = subprocess.check_output([
            "git", "-C", str(root), "show",
            NOTIFICATION_FIXTURE_COMMIT + ":android/platform/notifications/" + relative,
        ]).decode("utf-8")
        write("android/platform/notifications/" + relative, content)
    for module, plugin in (("model", "mesh.jvm.library"), ("contracts", "mesh.jvm.library"),
                           ("l10n", "mesh.android.library")):
        write(f"android/core/{module}/build.gradle.kts", f'plugins {{ id("{plugin}") }}\n')


def notification_report(repo, identities=NOTIFICATION_CASES, *, runner="testDebugUnitTest"):
    path = repo / "android/platform/notifications/build/test-results" / runner / "TEST-notifications.xml"
    path.parent.mkdir(parents=True, exist_ok=True)
    suite = ET.Element("testsuite", tests=str(len(identities)), failures="0", errors="0", skipped="0")
    for classname, method in sorted(identities):
        ET.SubElement(suite, "testcase", classname=classname, name=method)
    path.write_bytes(ET.tostring(suite))
    return path


class ModuleJUnitTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.repo = self.root / "repo"
        self.repo.mkdir()
        self.command("init", "--quiet")
        (self.repo / ".gitignore").write_text("**/build/\n", encoding="utf-8")
        self.source("core/model", "src/main/kotlin/Model.kt", "class Model\n")
        self.source("core/model", "src/test/kotlin/ModelTest.kt", "class ModelTest\n")
        self.source("core/model", "build.gradle.kts", 'plugins { id("mesh.jvm.library") }\n')
        self.source("core/database", "src/main/kotlin/Database.kt", "class Database\n")
        self.source("core/database", "src/test/java/DatabaseTest.java", "class DatabaseTest {}\n")
        self.command("add", "--all")
        self.command("commit", "--quiet", "-m", "fixture")
        self.report("core/model")
        self.report("core/database", runner="testDebugUnitTest")

    def command(self, *arguments):
        return subprocess.run(
            ["git", "-c", "core.autocrlf=false", "-c", "core.hooksPath=" + str(self.root / "hooks"),
             "-c", "commit.gpgsign=false", "-c", "user.name=Fixture", "-c",
             "user.email=fixture@example.invalid", "-C", str(self.repo), *arguments],
            capture_output=True, text=True, check=True, timeout=30,
        ).stdout.strip()

    def source(self, module, relative, content):
        path = self.repo / "android" / module / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8", newline="\n")

    def report(self, module, count=3, runner="test"):
        path = self.repo / "android" / module / "build" / "test-results" / runner / "TEST-fixture.xml"
        junit_report(path, count)
        return path

    def test_committed_sources_select_active_modules(self):
        sources = module_sources(self.repo)
        self.assertEqual(set(sources), {"core/model", "core/database"})
        self.assertIn("android/core/model/src/main/kotlin/Model.kt", sources["core/model"])

    def test_live_reports_are_validated_without_copying(self):
        result = check_module_tests(self.repo)
        self.assertEqual(result["core/model"]["passed"], 3)
        self.assertEqual(result["core/database"]["passed"], 3)
        self.assertFalse((self.root / "evidence").exists())

    def test_method_inventory_includes_separately_collected_contracts_and_app(self):
        self.report("app", runner="testDebugUnitTest")
        self.report("core/contracts")
        inventory = module_method_inventory(self.repo, check_module_tests(self.repo))
        self.assertEqual(set(inventory), {"app", "core/contracts", "core/model", "core/database"})
        self.assertEqual(len(inventory["core/contracts"]), 3)

    def test_missing_or_ambiguous_runner_rejects(self):
        database = self.repo / "android/core/database/build/test-results/testDebugUnitTest/TEST-fixture.xml"
        database.unlink()
        with self.assertRaisesRegex(PortError, "Missing/ambiguous"):
            check_module_tests(self.repo)
        self.report("core/database", runner="testDebugUnitTest")
        self.report("core/model", runner="testDebugUnitTest")
        with self.assertRaisesRegex(PortError, "Missing/ambiguous"):
            check_module_tests(self.repo)

    def test_zero_failed_skipped_and_malformed_reports_reject(self):
        for kind in ("zero", "failure", "error", "skipped", "malformed"):
            with self.subTest(kind=kind):
                path = self.report("core/model", 0 if kind == "zero" else 3)
                if kind == "malformed":
                    path.write_text("<testsuite>", encoding="utf-8")
                elif kind != "zero":
                    root = ET.fromstring(path.read_bytes())
                    ET.SubElement(root.find("testcase"), kind)
                    path.write_bytes(ET.tostring(root))
                with self.assertRaises(PortError):
                    check_module_tests(self.repo)

    def test_dirty_compiled_inputs_reject(self):
        self.source("core/model", "src/main/kotlin/Model.kt", "class ChangedModel\n")
        with self.assertRaisesRegex(PortError, "differ"):
            check_module_tests(self.repo)

    def notifications(self):
        def write(path, content):
            target = self.repo / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content, encoding="utf-8", newline="\n")

        notification_fixture(write)
        self.command("add", "--all")
        self.command("commit", "--quiet", "-m", "notification mapping")
        notification_report(self.repo)

    def test_exact_mapping_activates_and_binds_all_remapped_inputs(self):
        self.notifications()
        sources = module_sources(self.repo)["platform/notifications"]
        self.assertIn("android/platform/notifications/build.gradle.kts", sources)
        self.assertEqual(len([path for path in sources if path.endswith(".kt")]), 3)
        self.assertEqual(check_module_tests(self.repo)["platform/notifications"]["passed"], 6)
        self.assertEqual(remapped_tasks(self.repo), [":platform:notifications:testDebugUnitTest"])
        baseline = self.command("rev-parse", "HEAD")
        self.source("platform/notifications", NOTIFICATION_ROOT + "/NewTest.kt", "uncommitted")
        self.assertEqual(module_sources(self.repo, baseline)["platform/notifications"], sources)
        with self.assertRaisesRegex(PortError, "differ"):
            check_module_tests(self.repo)

    def test_missing_changed_dynamic_and_unsafe_mapping_reject(self):
        self.notifications()
        path = self.repo / "android/platform/notifications/build.gradle.kts"
        original = path.read_text(encoding="utf-8")
        for content in (
            None,
            original.replace('sourceSets.getByName("test")', 'sourceSets.getByName("androidTest")'),
            original.replace(NOTIFICATION_ROOT, "../outside"),
            original.replace('"' + NOTIFICATION_ROOT + '"', 'providers.gradleProperty("tests").get()'),
            original + '\nsourceSets.getByName("test").kotlin.setSrcDirs(emptyList<String>())\n',
        ):
            with self.subTest(content=content):
                if content is None:
                    path.unlink()
                else:
                    path.write_text(content, encoding="utf-8", newline="\n")
                self.command("add", "--all")
                self.command("commit", "--quiet", "-m", "unsupported mapping")
                with self.assertRaisesRegex(PortError, "mapping build"):
                    module_sources(self.repo)

    def test_dirty_remapped_source_and_build_and_new_resources_reject(self):
        self.notifications()
        for relative in ("build.gradle.kts", NOTIFICATION_ROOT + "/NotificationPayloadCodecTest.kt",
                         NOTIFICATION_ROOT + "/extra.txt"):
            with self.subTest(relative=relative):
                path = self.repo / "android/platform/notifications" / relative
                original = path.read_bytes() if path.exists() else None
                path.write_text("changed bytes", encoding="utf-8")
                with self.assertRaisesRegex(PortError, "differ"):
                    check_module_tests(self.repo)
                if original is None:
                    path.unlink()
                else:
                    path.write_bytes(original)
        (self.repo / ".git/info/exclude").write_text("Ignored.kt\n", encoding="utf-8")
        self.source("platform/notifications", NOTIFICATION_ROOT + "/Ignored.kt", "ignored but compiled input")
        with self.assertRaisesRegex(PortError, "differ"):
            check_module_tests(self.repo)

    def test_missing_zero_skipped_or_substituted_named_remapped_cases_reject(self):
        self.notifications()
        path = notification_report(self.repo)
        path.unlink()
        with self.assertRaises(PortError):
            check_module_tests(self.repo)
        for identities in (set(), set(NOTIFICATION_CASES) - {min(NOTIFICATION_CASES)},
                           {(classname, method + "Substitute") for classname, method in NOTIFICATION_CASES}):
            with self.subTest(identities=identities):
                notification_report(self.repo, identities)
                with self.assertRaises(PortError):
                    check_module_tests(self.repo)
        path = notification_report(self.repo)
        suite = ET.fromstring(path.read_bytes())
        suite.set("skipped", "1")
        ET.SubElement(suite.find("testcase"), "skipped")
        path.write_bytes(ET.tostring(suite))
        with self.assertRaises(PortError):
            check_module_tests(self.repo)

    def test_future_candidate_named_case_is_mandatory_not_a_fixed_count(self):
        self.notifications()
        relative = NOTIFICATION_ROOT + "/NewTest.kt"
        self.source("platform/notifications", relative, """
package com.meshcoreone.android.platform.notifications.messaging
import org.junit.Test
class NewTest { @Test fun newlyRequired() {} }
""")
        self.command("add", "--all")
        self.command("commit", "--quiet", "-m", "new case")
        with self.assertRaisesRegex(PortError, "newlyRequired"):
            check_module_tests(self.repo)
        notification_report(self.repo, NOTIFICATION_CASES | {
            ("com.meshcoreone.android.platform.notifications.messaging.NewTest", "newlyRequired"),
        })
        self.assertEqual(check_module_tests(self.repo)["platform/notifications"]["passed"], 7)

    def test_conventional_notification_case_is_mandatory_and_compiled_exactly(self):
        self.notifications()
        relative = "src/test/kotlin/com/meshcoreone/android/platform/notifications/messaging/AdapterTest.kt"
        self.source("platform/notifications", relative, """
package com.meshcoreone.android.platform.notifications.messaging
import org.junit.Test
class AdapterTest { @Test fun conventionalRequired() {} }
""")
        self.command("add", "--all")
        self.command("commit", "--quiet", "-m", "conventional notification test")
        source = "android/platform/notifications/" + relative
        inputs = module_sources(self.repo)["platform/notifications"]
        self.assertIn(source, inputs)
        with self.assertRaisesRegex(PortError, "conventionalRequired"):
            check_module_tests(self.repo)
        notification_report(self.repo, NOTIFICATION_CASES | {
            ("com.meshcoreone.android.platform.notifications.messaging.AdapterTest", "conventionalRequired"),
        })
        self.assertEqual(check_module_tests(self.repo)["platform/notifications"]["passed"], 7)

        compiled = {path: blob for path, blob in inputs.items() if path.endswith(".kt")}
        expected = {"platform/notifications": inputs}
        self.assertEqual(validate_remapped_compilation(
            self.repo, {"platform/notifications": compiled}, ["platform/notifications"],
        ), expected)
        for actual in (
            {path: blob for path, blob in compiled.items() if path != source},
            {**compiled, source: "0" * 40},
            {**compiled, source + "Extra.kt": "0" * 40},
        ):
            with self.subTest(actual=actual), self.assertRaisesRegex(PortError, "compilation"):
                validate_remapped_compilation(
                    self.repo, {"platform/notifications": actual}, ["platform/notifications"],
                )

        self.source("platform/notifications", relative, "changed after commit")
        with self.assertRaisesRegex(PortError, "differ"):
            check_module_tests(self.repo)

    def test_unsupported_java_notification_test_input_fails_closed(self):
        self.notifications()
        relative = "src/test/java/com/meshcoreone/android/platform/notifications/messaging/AdapterTest.java"
        self.source("platform/notifications", relative, "class AdapterTest {}")
        self.command("add", "--all")
        self.command("commit", "--quiet", "-m", "unsupported Java notification test")
        with self.assertRaisesRegex(PortError, "Unsupported Java notification JVM test input"):
            check_module_tests(self.repo)

    def test_actual_compilation_requires_exact_remapped_blobs_and_no_extra_sources(self):
        self.notifications()
        inputs = module_sources(self.repo)["platform/notifications"]
        sources = {path: blob for path, blob in inputs.items() if path.endswith(".kt")}
        expected = {"platform/notifications": inputs}
        self.assertEqual(validate_remapped_compilation(
            self.repo, {"platform/notifications": sources}, ["platform/notifications"],
        ), expected)
        for actual in ({}, dict(list(sources.items())[1:]),
                       {**sources, min(sources): "0" * 40},
                       {**sources, "android/platform/notifications/" + NOTIFICATION_ROOT + "/Extra.kt": "0" * 40}):
            with self.subTest(actual=actual), self.assertRaisesRegex(PortError, "compilation"):
                validate_remapped_compilation(
                    self.repo, {"platform/notifications": actual}, ["platform/notifications"],
                )

    def test_no_selected_test_runner_has_no_remapped_evidence_obligation(self):
        self.notifications()
        self.source("platform/notifications", "build.gradle.kts", "unsupported")
        self.command("add", "--all")
        self.command("commit", "--quiet", "-m", "unselected compile-only input")
        self.assertEqual(validate_remapped_compilation(self.repo, {}, []), {})
        self.assertEqual(validate_remapped_compilation(self.repo, {}, ["core/model"]), {})
        with self.assertRaisesRegex(PortError, "mapping build"):
            validate_remapped_compilation(self.repo, {}, ["platform/notifications"])

    def test_full_and_benchmark_consumer_ci_select_only_their_actual_tasks(self):
        from controller import ci

        self.notifications()
        output = self.root / "output"
        output.mkdir()

        def capture_build(command, environment, log, **kwargs):
            if log.name.startswith("gradle-") and not log.name.endswith("-preflight.log"):
                raise PortError("captured build request; no Gradle executed")

        for stage, scopes, required in (
            ("scaffold", ["scaffold"], True),
            ("benchmark", ["benchmark-consumer"], False),
        ):
            with self.subTest(scopes=scopes), patch.object(ci, "REPO", self.repo), \
                    patch.object(ci, "load_manifest"), patch.object(ci, "port_map", return_value={}), \
                    patch.object(ci, "candidate_environment", return_value={}), \
                    patch.object(ci, "verify_wrapper"), \
                    patch.object(ci, "execute", side_effect=capture_build) as execute, \
                    patch.object(ci, "validate_candidate_task_graph", return_value={"tasks": 1}), \
                    contextlib.redirect_stdout(io.StringIO()):
                with self.assertRaisesRegex(PortError, "captured build"):
                    ci.run_stage(stage, {"host": "linux", "private_root": str(self.root)}, output, scopes=scopes)
                self.assertEqual(
                    ":platform:notifications:testDebugUnitTest" in execute.call_args.args[0], required,
                )

    def test_build_task_profile_preserves_the_identical_mapping(self):
        self.notifications()
        path = self.repo / "android/platform/notifications/build.gradle.kts"
        baseline = path.read_text(encoding="utf-8")
        wiring = (
            '\nrootProject.tasks.named("verifyScaffoldTests") { dependsOn(":platform:notifications:testDebugUnitTest") }\n'
            'tasks.named("check") { dependsOn("testDebugUnitTest") }\n'
        )
        path.write_text(baseline + wiring, encoding="utf-8", newline="\n")
        self.command("add", "--all")
        self.command("commit", "--quiet", "-m", "notification producer wiring")
        self.assertEqual(self.command("rev-parse", "HEAD:android/platform/notifications/build.gradle.kts"),
                         "1edcfc21437134ff7eb4bc431ffd6358c6967bab")
        self.assertEqual(check_module_tests(self.repo)["platform/notifications"]["passed"], 6)

    def test_unsafe_committed_remapped_source_cannot_supply_provenance(self):
        self.notifications()
        path = "android/platform/notifications/" + NOTIFICATION_ROOT + "/NotificationPayloadCodecTest.kt"
        blob = self.command("rev-parse", "HEAD:" + path)
        self.command("update-index", "--cacheinfo", "120000," + blob + "," + path)
        self.command("commit", "--quiet", "-m", "unsafe source mode")
        with self.assertRaisesRegex(PortError, "Unsafe/non-file"):
            module_sources(self.repo)

    def test_revision_requires_a_commit_not_a_mutable_ref_or_tree(self):
        for revision in ("HEAD", self.command("rev-parse", "HEAD^{tree}")):
            with self.subTest(revision=revision), self.assertRaisesRegex(PortError, "immutable candidate commit"):
                module_sources(self.repo, revision)

    def test_wrong_runner_and_absent_committed_remapped_sources_reject(self):
        self.notifications()
        notification_report(self.repo).unlink()
        notification_report(self.repo, runner="test")
        with self.assertRaisesRegex(PortError, "Wrong actual"):
            check_module_tests(self.repo)
        for path in (self.repo / "android/platform/notifications" / NOTIFICATION_ROOT).glob("*.kt"):
            path.unlink()
        self.command("add", "--all")
        self.command("commit", "--quiet", "-m", "remove required remapped sources")
        with self.assertRaisesRegex(PortError, "Missing committed"):
            module_sources(self.repo)

    def test_unrelated_instrumentation_is_not_jvm_source_or_dirty_input(self):
        self.source("platform/widgets", "src/androidTest/kotlin/DeviceTest.kt", "class DeviceTest")
        self.command("add", "--all")
        self.command("commit", "--quiet", "-m", "instrumentation only")
        self.assertNotIn("platform/widgets", module_sources(self.repo))
        self.notifications()
        path = "src/androidTest/kotlin/device/DeviceOnlyTest.kt"
        self.source("platform/notifications", path, "unrelated instrumentation")
        self.command("add", "--all")
        self.command("commit", "--quiet", "-m", "unrelated instrumented source")
        sources = module_sources(self.repo)["platform/notifications"]
        self.assertNotIn("android/platform/notifications/" + path, sources)
        self.source("platform/notifications", path, "dirty device test")
        self.assertEqual(check_module_tests(self.repo)["platform/notifications"]["passed"], 6)


if __name__ == "__main__":
    unittest.main()
