"""AndroidOnly: WP-003 Validate active-module JUnit directly in build outputs."""

import contextlib
import importlib.util
import io
import json
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
from unittest.mock import patch

from controller.errors import PortError
from controller.module_junit import (
    NOTIFICATION_CASES, NOTIFICATION_ROOT, check_module_tests,
    _notification_jvm_test_sources, module_method_inventory, module_sources,
    remapped_tasks, validate_remapped_compilation,
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
    write("android/platform/notifications/src/test/kotlin/ConventionalFixture.kt", "class ConventionalFixture\n")
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

    def compiled_notification_sources(self):
        inputs = module_sources(self.repo)["platform/notifications"]
        return {
            "platform/notifications": _notification_jvm_test_sources("platform/notifications", inputs),
        }

    def test_exact_mapping_activates_and_binds_all_remapped_inputs(self):
        self.notifications()
        sources = module_sources(self.repo)["platform/notifications"]
        self.assertIn("android/platform/notifications/build.gradle.kts", sources)
        self.assertEqual(len([path for path in sources if path.endswith(".kt")]), 4)
        compiled = self.compiled_notification_sources()
        self.assertEqual(check_module_tests(self.repo, compiled)["platform/notifications"]["passed"], 6)
        with self.assertRaisesRegex(PortError, "Missing immutable compiled notification test-source inventory"):
            check_module_tests(self.repo)
        self.assertEqual(remapped_tasks(self.repo), [":platform:notifications:testDebugUnitTest"])
        baseline = self.command("rev-parse", "HEAD")
        self.source("platform/notifications", NOTIFICATION_ROOT + "/NewTest.kt", "uncommitted")
        self.assertEqual(module_sources(self.repo, baseline)["platform/notifications"], sources)
        with self.assertRaisesRegex(PortError, "differ"):
            check_module_tests(self.repo, compiled, baseline)

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
        compiled = self.compiled_notification_sources()
        with self.assertRaises(PortError):
            check_module_tests(self.repo, compiled)
        for identities in (set(), set(NOTIFICATION_CASES) - {min(NOTIFICATION_CASES)},
                           {(classname, method + "Substitute") for classname, method in NOTIFICATION_CASES}):
            with self.subTest(identities=identities):
                notification_report(self.repo, identities)
                with self.assertRaises(PortError):
                    check_module_tests(self.repo, compiled)
        path = notification_report(self.repo)
        suite = ET.fromstring(path.read_bytes())
        suite.set("skipped", "1")
        ET.SubElement(suite.find("testcase"), "skipped")
        path.write_bytes(ET.tostring(suite))
        with self.assertRaises(PortError):
            check_module_tests(self.repo, compiled)

    def test_compiled_remapped_methods_use_shared_immutable_lineage_validator(self):
        from controller.repair_acceptance import validate_hosted_methods

        self.notifications()
        compiled = self.compiled_notification_sources()
        report = notification_report(self.repo)
        with patch("controller.repair_acceptance.validate_hosted_methods") as validate:
            result = check_module_tests(self.repo, compiled)
        self.assertEqual(result["platform/notifications"]["passed"], 6)
        validate.assert_called_once()
        scope, candidate_tree, repo, executed, actual = validate.call_args.args
        self.assertEqual(scope["kind"], "whole-wp")
        remapped = {
            path for path in compiled["platform/notifications"]
            if "/src/androidTest/" in path
        }
        self.assertTrue(remapped <= candidate_tree.keys())
        self.assertEqual(repo, self.repo)
        self.assertEqual(actual, compiled)
        self.assertEqual(len(executed["platform/notifications"]), 6)
        self.assertEqual(
            {coverage["path"] for coverage in scope["coverage"]},
            remapped,
        )
        self.assertEqual(
            set(validate.call_args.kwargs["compiled_helpers"]),
            set(compiled["platform/notifications"]) - remapped,
        )
        self.assertEqual(report.parent.name, "testDebugUnitTest")

    def test_remapped_parameterized_sdk_identities_require_every_configured_execution(self):
        self.notifications()
        relative = NOTIFICATION_ROOT + "/SdkFamilyTest.kt"
        self.source("platform/notifications", relative, """
package com.meshcoreone.android.platform.notifications.messaging
import android.app.NotificationManager
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import org.robolectric.shadows.ShadowNotificationManager
@Config(sdk = [31, 37])
class SdkFamilyTest {
    @Test fun runsAtEveryConfiguredSdk() {}
    @Implements(NotificationManager::class)
    class CountingNotificationManager : ShadowNotificationManager() {
        @Implementation
        fun notify(tag: String?, id: Int) {}
    }
}
""")
        self.command("add", "--all")
        self.command("commit", "--quiet", "-m", "SDK parameterized notification test")
        compiled = self.compiled_notification_sources()
        classname = "com.meshcoreone.android.platform.notifications.messaging.SdkFamilyTest"
        base = (classname, "runsAtEveryConfiguredSdk")
        sdk31 = (classname, "runsAtEveryConfiguredSdk[31]")
        required = set(NOTIFICATION_CASES)

        notification_report(self.repo, required | {base, sdk31})
        self.assertEqual(check_module_tests(self.repo, compiled)["platform/notifications"]["passed"], 8)

        for identities in (
            required | {base},
            required | {sdk31},
            required | {base, sdk31, (classname, "runsAtEveryConfiguredSdk[37]")},
            list(required | {base, sdk31}) + [sdk31],
        ):
            with self.subTest(identities=identities):
                notification_report(self.repo, identities)
                with self.assertRaises(PortError):
                    check_module_tests(self.repo, compiled)

    def test_remapped_sdk_method_override_and_unsupported_configuration(self):
        self.notifications()
        relative = NOTIFICATION_ROOT + "/SdkFamilyTest.kt"
        source = """
package com.meshcoreone.android.platform.notifications.messaging
import org.junit.Test
import org.robolectric.annotation.Config
@Config(sdk = [31, 37])
class SdkFamilyTest {
    @Config(sdk = [37]) @Test fun oneSdk() {}
    @Config(shadows = []) @Test fun inheritedSdks() {}
}
"""
        self.source("platform/notifications", relative, source)
        self.command("add", "--all")
        self.command("commit", "--quiet", "-m", "SDK method overrides")
        classname = "com.meshcoreone.android.platform.notifications.messaging.SdkFamilyTest"
        notification_report(self.repo, NOTIFICATION_CASES | {
            (classname, "oneSdk"), (classname, "inheritedSdks"), (classname, "inheritedSdks[31]"),
        })
        self.assertEqual(check_module_tests(
            self.repo, self.compiled_notification_sources(),
        )["platform/notifications"]["passed"], 9)

        for value in ("sdkProvider()", "[31, 34, 37]", "[3 1, 37]", "[31, 31]", "[31, 37], maxSdk = 31"):
            self.source("platform/notifications", relative, source.replace("[31, 37]", value))
            self.command("add", "--all")
            self.command("commit", "--quiet", "-m", "unsupported SDK configuration")
            with self.subTest(value=value), self.assertRaisesRegex(PortError, "SDK"):
                check_module_tests(self.repo, self.compiled_notification_sources())

    def test_default_full_fast_scaffold_requires_fresh_notification_compiler_inventory(self):
        self.notifications()
        path = self.root / "gradle-test-sources.jsonl"
        fast_path = Path(__file__).resolve().parents[1] / "local" / "fast.py"
        spec = importlib.util.spec_from_file_location("test_local_fast_runner", fast_path)
        fast = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(fast)

        def write_inventory(sources):
            path.write_text(json.dumps({
                "task": ":platform:notifications:testDebugUnitTest",
                "sources": [{"path": name, "blob": blob} for name, blob in sorted(sources.items())],
            }) + "\n", encoding="utf-8")

        expected = self.compiled_notification_sources()["platform/notifications"]
        write_inventory(expected)
        actual = fast.notification_test_sources(self.repo, path)
        self.assertEqual(actual["platform/notifications"], expected)
        self.assertEqual(check_module_tests(self.repo, actual)["platform/notifications"]["passed"], 6)

        path.unlink()
        with self.assertRaisesRegex(PortError, "Missing or unsafe compiled test source inventory"):
            fast.notification_test_sources(self.repo, path)
        write_inventory({**expected, min(expected): "0" * 40})
        with self.assertRaisesRegex(PortError, "differs from immutable notification test sources"):
            fast.notification_test_sources(self.repo, path)
        write_inventory({
            **expected,
            "android/platform/notifications/src/androidTest/kotlin/"
            "com/meshcoreone/android/platform/notifications/native/NativeOnlyTest.kt": "0" * 40,
        })
        with self.assertRaisesRegex(PortError, "differs from immutable notification test sources"):
            fast.notification_test_sources(self.repo, path)
        write_inventory({**expected, "shared/Outside.kt": "0" * 40})
        with self.assertRaisesRegex(PortError, "Malformed or duplicate compiled test source"):
            fast.notification_test_sources(self.repo, path)

    def test_default_full_fast_main_binds_only_current_invocation_compiler_inventory(self, notifications=True):
        from controller import ci, ci_environment, ci_evidence

        if notifications:
            self.notifications()
        fast_path = Path(__file__).resolve().parents[1] / "local" / "fast.py"
        spec = importlib.util.spec_from_file_location("test_default_fast_main", fast_path)
        fast = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(fast)
        work = self.root / "work"
        (work / "toolchain").mkdir(parents=True)
        (work / "toolchain/environment.json").write_text(json.dumps({
            "java_home": str(self.root / "fixture-java"),
        }), encoding="utf-8")
        expected = (
            self.compiled_notification_sources()["platform/notifications"] if notifications else {
                path: blob for path, blob in module_sources(self.repo)["core/model"].items()
                if "/src/test/" in path
            }
        )
        record = {
            "task": ":platform:notifications:testDebugUnitTest" if notifications else ":core:model:test",
            "sources": [{"path": path, "blob": blob} for path, blob in sorted(expected.items())],
        }
        for mode in (("current", "missing", "stale") if notifications else ("current",)):
            output = self.root / ("fast-" + mode)
            output.mkdir()
            inventory_path = output / "gradle-test-sources.jsonl"
            inventory_path.write_text(json.dumps(record) + "\n", encoding="utf-8")

            def execute(command, repo, environment, log):
                if log.name != "gradle-scaffold.log":
                    return
                self.assertEqual(":platform:notifications:testDebugUnitTest" in command, notifications)
                self.assertEqual(command[-2:], [
                    "--init-script",
                    str(self.repo / "tools/android-port/controller/verify_candidate_task_graph.gradle"),
                ])
                self.assertEqual(environment["ANDROID_CI_TEST_SOURCES"], str(inventory_path))
                self.assertEqual(inventory_path.read_text(encoding="utf-8"), "")
                report_path = (self.repo / "android/platform/notifications/build/test-results/"
                               "testDebugUnitTest/TEST-notifications.xml")
                self.assertFalse(report_path.exists())
                self.report("core/model")
                self.report("core/database", runner="testDebugUnitTest")
                self.report("app", runner="testDebugUnitTest")
                self.report("core/contracts")
                if notifications:
                    notification_report(self.repo)
                if mode != "missing":
                    actual = json.loads(json.dumps(record))
                    if mode == "stale":
                        actual["sources"][0]["blob"] = "0" * 40
                    inventory_path.write_text(json.dumps(actual) + "\n", encoding="utf-8")

            text = io.StringIO()
            with self.subTest(mode=mode), \
                    patch.object(sys, "argv", [
                        "fast.py", "--repo", str(self.repo), "--work", str(work),
                        "--output", str(output), "--stages", "scaffold",
                    ]), patch.object(fast, "execution_lane", return_value=None), \
                    patch.object(fast, "owned_verification", return_value=(
                        self.repo / "tools/android-port/controller/verify_candidate_task_graph.gradle")), \
                    patch.object(fast, "execute", side_effect=execute), \
                    patch.object(ci_environment, "candidate_environment", return_value={}), \
                    patch.object(ci_environment, "verify_wrapper"), \
                    patch.object(ci, "validate_candidate_task_graph", return_value={"tasks": 1}), \
                    patch.object(ci, "TASKS", {
                        **ci.TASKS, "scaffold": [
                            task for task in ci.TASKS["scaffold"]
                            if notifications or task != ":platform:notifications:testDebugUnitTest"
                        ],
                    }), \
                    patch.object(ci_evidence, "collect_suites", return_value={}), \
                    patch.object(ci_evidence, "collect_lint", return_value={}), \
                    patch.object(ci_evidence, "validate_graph_runtime"), \
                    contextlib.redirect_stdout(text):
                if mode == "current":
                    fast.main()
                else:
                    with self.assertRaisesRegex(PortError, "differs from immutable notification test sources"):
                        fast.main()
            self.assertEqual("FAST LOCAL CHECK PASSED" in text.getvalue(), mode == "current")
            if mode == "current":
                self.assertIn('"compiled_test_sources"', text.getvalue())
                self.assertIn('"remapped_jvm_inputs"', text.getvalue())
                if not notifications:
                    self.assertIn('"remapped_jvm_inputs": {}', text.getvalue())
                    self.assertNotIn("platform:notifications", text.getvalue())

    def test_default_full_fast_main_does_not_require_absent_notification_module(self):
        self.test_default_full_fast_main_binds_only_current_invocation_compiler_inventory(notifications=False)

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
        compiled = self.compiled_notification_sources()
        with self.assertRaisesRegex(PortError, "Verification coverage|SDK cases"):
            check_module_tests(self.repo, compiled)
        notification_report(self.repo, NOTIFICATION_CASES | {
            ("com.meshcoreone.android.platform.notifications.messaging.NewTest", "newlyRequired"),
        })
        self.assertEqual(check_module_tests(self.repo, compiled)["platform/notifications"]["passed"], 7)

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
        compiled = self.compiled_notification_sources()
        self.assertIn(source, inputs)
        with self.assertRaisesRegex(PortError, "did not actually execute"):
            check_module_tests(self.repo, compiled)
        notification_report(self.repo, NOTIFICATION_CASES | {
            ("com.meshcoreone.android.platform.notifications.messaging.AdapterTest", "conventionalRequired"),
        })
        self.assertEqual(check_module_tests(self.repo, compiled)["platform/notifications"]["passed"], 7)
        notification_report(self.repo, NOTIFICATION_CASES | {
            ("com.meshcoreone.android.platform.notifications.messaging.AdapterTest", "conventionalRequired[31]"),
            ("com.meshcoreone.android.platform.notifications.messaging.AdapterTest", "conventionalRequired[37]"),
        })
        self.assertEqual(check_module_tests(self.repo, compiled)["platform/notifications"]["passed"], 8)

        expected = {"platform/notifications": inputs}
        self.assertEqual(validate_remapped_compilation(
            self.repo, compiled, ["platform/notifications"],
        ), expected)
        for actual in (
            {path: blob for path, blob in compiled["platform/notifications"].items() if path != source},
            {**compiled["platform/notifications"], source: "0" * 40},
            {**compiled["platform/notifications"], source + "Extra.kt": "0" * 40},
        ):
            with self.subTest(actual=actual), self.assertRaisesRegex(PortError, "compilation"):
                validate_remapped_compilation(
                    self.repo, {"platform/notifications": actual}, ["platform/notifications"],
                )

        self.source("platform/notifications", relative, "changed after commit")
        with self.assertRaisesRegex(PortError, "differ"):
            check_module_tests(self.repo)

    def test_conventional_explicit_sdk_family_requires_all_variants_not_other_parameters(self):
        self.notifications()
        self.source("platform/notifications", "src/test/kotlin/ConfiguredAdapterTest.kt", """
package com.meshcoreone.android.platform.notifications.messaging
import android.app.NotificationManager
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import org.robolectric.shadows.ShadowNotificationManager
@Config(`sdk` = [31, 37,])
class ConfiguredAdapterTest {
    @Test fun conventionalRequired() {}
    @Implements(NotificationManager::class)
    class CountingNotificationManager : ShadowNotificationManager() {
        @Implementation
        fun notify(tag: String?, id: Int) {}
    }
}
class GenericAdapterTest { @Test fun genericParameters() {} }
class Fixture { @Config(sdk = [37]) class ConfiguredAdapterTest {} }
""")
        self.command("add", "--all")
        self.command("commit", "--quiet", "-m", "conventional explicit SDK family")
        compiled = self.compiled_notification_sources()
        classname = "com.meshcoreone.android.platform.notifications.messaging.ConfiguredAdapterTest"
        generic = (
            "com.meshcoreone.android.platform.notifications.messaging.GenericAdapterTest",
            "genericParameters[non-sdk-parameter]",
        )
        base = (classname, "conventionalRequired")
        sdk31 = (classname, "conventionalRequired[31]")
        for variants in ({base}, {sdk31}):
            notification_report(self.repo, NOTIFICATION_CASES | {generic} | variants)
            with self.subTest(variants=variants), self.assertRaisesRegex(PortError, "SDK cases"):
                check_module_tests(self.repo, compiled)
        notification_report(self.repo, NOTIFICATION_CASES | {generic, base, sdk31})
        self.assertEqual(check_module_tests(self.repo, compiled)["platform/notifications"]["passed"], 9)

    def test_conventional_inherited_only_suite_cannot_hide_as_a_helper(self):
        self.notifications()
        self.source("platform/notifications", "src/test/kotlin/BaseTest.kt", """
package sample
import org.junit.Test
open class BaseTest { @Test fun inheritedRequired() {} }
""")
        self.source("platform/notifications", "src/test/kotlin/InheritedTest.kt", """
package sample
class InheritedTest : BaseTest() {}
""")
        self.command("add", "--all")
        self.command("commit", "--quiet", "-m", "inherited-only conventional suite")
        notification_report(self.repo, NOTIFICATION_CASES | {("sample.BaseTest", "inheritedRequired")})
        with self.assertRaisesRegex(PortError, "inherited Kotlin JUnit"):
            check_module_tests(self.repo, self.compiled_notification_sources())

    def test_conventional_unreferenced_junit_free_helper_is_only_compiled_evidence(self):
        from controller.repair_acceptance import validate_hosted_methods

        self.notifications()
        relative = "src/test/kotlin/Helper.kt"
        self.source("platform/notifications", relative, """
package sample
open class HelperBase { fun support() {} }
class Helper : HelperBase() {}
""")
        self.command("add", "--all")
        self.command("commit", "--quiet", "-m", "JUnit-free conventional helper")
        compiled = self.compiled_notification_sources()
        with patch("controller.repair_acceptance.validate_hosted_methods", wraps=validate_hosted_methods) as validate:
            self.assertEqual(check_module_tests(self.repo, compiled)["platform/notifications"]["passed"], 6)
        helper = "android/platform/notifications/" + relative
        scope = validate.call_args.args[0]
        self.assertIn(helper, validate.call_args.kwargs["compiled_helpers"])
        self.assertNotIn(helper, {coverage["path"] for coverage in scope["coverage"]})
        self.assertFalse(any(coverage["hosted_methods"] for coverage in scope["coverage"]))
        incomplete = {"platform/notifications": {
            path: blob for path, blob in compiled["platform/notifications"].items() if path != helper
        }}
        with self.assertRaisesRegex(PortError, "compilation"):
            check_module_tests(self.repo, incomplete)
        self.source("platform/notifications", relative, """
package sample
import org.junit.Test
class Helper { @Test fun noLongerAHelper() {} }
""")
        self.command("add", "--all")
        self.command("commit", "--quiet", "-m", "helper now declares an unexecuted test")
        with self.assertRaisesRegex(PortError, "did not actually execute"):
            check_module_tests(self.repo, self.compiled_notification_sources())

    def test_conventional_inherited_sdk_requires_explicit_resolved_configuration(self):
        self.notifications()
        self.source("platform/notifications", "src/test/kotlin/ConfiguredBase.kt", """
package sample
import org.robolectric.annotation.Config
@Config(sdk = [31, 37])
open class ConfiguredBase {}
""")
        child = "src/test/kotlin/ChildTest.kt"
        self.source("platform/notifications", child, """
package sample
import org.junit.Test
class ChildTest : ConfiguredBase() { @Test fun inheritedSdk() {} }
""")
        self.command("add", "--all")
        self.command("commit", "--quiet", "-m", "unresolved inherited SDK family")
        notification_report(self.repo, NOTIFICATION_CASES | {("sample.ChildTest", "inheritedSdk")})
        with self.assertRaisesRegex(PortError, "inherited Robolectric SDK configuration"):
            check_module_tests(self.repo, self.compiled_notification_sources())
        self.source("platform/notifications", child, """
package sample
import org.junit.Test
import org.robolectric.annotation.Config
@Config(sdk = [37])
class ChildTest : ConfiguredBase() { @Test fun inheritedSdk() {} }
""")
        self.command("add", "--all")
        self.command("commit", "--quiet", "-m", "explicit child SDK override")
        self.assertEqual(check_module_tests(
            self.repo, self.compiled_notification_sources(),
        )["platform/notifications"]["passed"], 7)

    def test_cross_file_sdk_alias_cannot_hide_missing_execution(self):
        self.notifications()
        self.source("platform/notifications", "src/test/kotlin/DeviceConfig.kt", """
package sample
typealias DeviceConfig = org.robolectric.annotation.Config
""")
        relative = "src/test/kotlin/AliasedTest.kt"
        source = """
package sample
import org.junit.Test
@DeviceConfig(sdk = [31, 37])
class AliasedTest { @Test fun required() {} }
"""
        self.source("platform/notifications", relative, source)
        self.command("add", "--all")
        self.command("commit", "--quiet", "-m", "unresolved cross-file SDK alias")
        notification_report(self.repo, NOTIFICATION_CASES | {("sample.AliasedTest", "required")})
        with self.assertRaisesRegex(PortError, "Unresolved SDK-bearing annotation"):
            check_module_tests(self.repo, self.compiled_notification_sources())
        self.source("platform/notifications", relative, source.replace(
            "@DeviceConfig", "@org.robolectric.annotation.Config",
        ))
        self.command("add", "--all")
        self.command("commit", "--quiet", "-m", "explicit canonical SDK annotation")
        with self.assertRaisesRegex(PortError, "SDK cases"):
            check_module_tests(self.repo, self.compiled_notification_sources())
        notification_report(self.repo, NOTIFICATION_CASES | {
            ("sample.AliasedTest", "required"), ("sample.AliasedTest", "required[31]"),
        })
        self.assertEqual(check_module_tests(
            self.repo, self.compiled_notification_sources(),
        )["platform/notifications"]["passed"], 8)

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
                       {**sources, "android/platform/notifications/" + NOTIFICATION_ROOT + "/Extra.kt": "0" * 40},
                       {**sources, "android/platform/notifications/src/androidTest/kotlin/"
                                  "com/meshcoreone/android/platform/notifications/native/NativeOnlyTest.kt":
                            "0" * 40},
                       {**sources, "android/platform/notifications/src/main/kotlin/"
                                  "com/meshcoreone/android/platform/notifications/Shared.kt":
                            "0" * 40}):
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
        self.assertEqual(
            check_module_tests(self.repo, self.compiled_notification_sources())
            ["platform/notifications"]["passed"],
            6,
        )

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
        self.assertEqual(
            check_module_tests(self.repo, self.compiled_notification_sources())
            ["platform/notifications"]["passed"],
            6,
        )


if __name__ == "__main__":
    unittest.main()
