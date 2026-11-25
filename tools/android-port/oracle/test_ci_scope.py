"""AndroidOnly: WP-003 Check focused CI selection and fail-closed full-suite fallbacks."""

import os
import shutil
import subprocess
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path

from ci_scope import full_scope, scope


class CiScopeTest(unittest.TestCase):
    def test_schedule_manual_and_unknown_paths_run_everything(self):
        for paths, event in [
            ([], "schedule"), ([], "workflow_dispatch"),
            (["unexpected-product-input"], "pull_request"),
            (["MC1Services/Sources/Backup.swift"], "pull_request"),
            (["android/new-module/src/Behavior.kt"], "pull_request"),
            (["android/core/new-module/src/Behavior.kt"], "pull_request"),
            ([".gitmodules"], "pull_request"),
            ([".gitattributes"], "pull_request"),
            ([".gitignore"], "pull_request"),
            (["docs/android/notices/dependencies.json"], "pull_request"),
        ]:
            with self.subTest(paths=paths, event=event):
                self.assertEqual(full_scope(), scope(paths, event))

    def test_documentation_only_changes_leave_source_checks_to_workflow(self):
        for event in ("pull_request", "push"):
            result = scope([
                "docs/android/README.md", "README.md", ".github/copilot-instructions.md",
                ".github/instructions/android.instructions.md",
            ], event)
            self.assertEqual(dict(
                android=False, build=False, full=False, app_tests=False,
                apk=False, instrumentation=False, tasks=[],
            ), result)

    def test_instruction_documents_do_not_hide_code_or_unknown_infrastructure(self):
        instruction = ".github/instructions/swift-reference.instructions.md"
        feature = "android/feature/chats/src/main/kotlin/Chat.kt"
        for event in ("pull_request", "push"):
            for paths in ([instruction, feature], [feature, instruction]):
                with self.subTest(event=event, paths=paths):
                    self.assertEqual(scope([feature], event), scope(paths, event))
            for path in [
                ".github/workflows/android-ci.yml",
                ".github/actions/setup-android/action.yml",
                ".github/instructions/README.md",
                ".github/instructions/.instructions.md",
                ".github/instructions/nested/file.instructions.md",
                ".github/copilot-instructions.md.yml",
                "tools/android-port/ci_scope.py",
            ]:
                for paths in ([instruction, path], [path, instruction]):
                    with self.subTest(event=event, paths=paths):
                        self.assertEqual(full_scope(), scope(paths, event))

    def test_feature_code_runs_its_tests_lint_and_app_compilation_not_apk_or_all_tests(self):
        selected = scope(["android/feature/chats/src/main/kotlin/Chat.kt"], "pull_request")
        self.assertTrue(selected["android"])
        self.assertTrue(selected["build"])
        self.assertFalse(selected["apk"])
        self.assertFalse(selected["full"])
        self.assertFalse(selected["app_tests"])
        self.assertFalse(selected["instrumentation"])
        self.assertEqual([
            ":app:compileDebugJavaWithJavac", ":app:compileDebugKotlin",
            ":feature:chats:lintDebug", ":feature:chats:testDebugUnitTest",
        ], selected["tasks"])

    def test_jvm_and_android_modules_are_selected_and_deduplicated(self):
        selected = scope([
            "android/core/protocol/src/main/kotlin/Packet.kt",
            "android/core/protocol/src/test/kotlin/CodecTest.kt",
            "android/core/data/src/main/kotlin/Backup.kt",
        ], "push")
        self.assertEqual([
            ":app:compileDebugJavaWithJavac", ":app:compileDebugKotlin",
            ":core:data:lintDebug", ":core:data:testDebugUnitTest", ":core:protocol:test",
        ], selected["tasks"])
        self.assertFalse(selected["apk"])
        self.assertFalse(selected["app_tests"])

    def test_resources_manifest_and_unknown_source_sets_still_package_the_apk(self):
        for path in [
            "android/app/src/main/AndroidManifest.xml",
            "android/app/src/main/assets/licenses/notice.txt",
            "android/app/src/main/assets/example.kt",
            "android/feature/chats/src/main/res/values/strings.xml",
            "android/core/protocol/src/release/kotlin/Packet.kt",
        ]:
            with self.subTest(path=path):
                self.assertTrue(scope([path], "pull_request")["apk"])

    def test_focused_workflow_runs_packaging_validators_only_when_building_an_apk(self):
        bash = shutil.which("bash")
        if sys.platform == "win32" and (git := shutil.which("git")):
            git_bash = Path(git).resolve().parent.parent / "bin" / "bash.exe"
            if git_bash.is_file():
                bash = str(git_bash)
        self.assertIsNotNone(bash, "Bash is required to exercise the CI task selector")

        workflow = (Path(__file__).resolve().parents[3] / ".github" / "workflows" / "android-ci.yml").read_text(
            encoding="utf-8",
        )
        start = '          case "$CHECK_FULL" in\n'
        end = "          printf 'Selected Gradle tasks:"
        self.assertEqual(1, workflow.count(start))
        self.assertEqual(1, workflow.count(end))
        selector = workflow.split(start, 1)[1].split(end, 1)[0]
        script = 'case "$CHECK_FULL" in\n' + textwrap.dedent(selector) + '\nprintf "%s\\n" "${tasks[@]}"\n'
        packaging_tasks = {
            ":app:assembleDebug",
            ":core:datastore:verifyPreferencePackaging",
            ":core:designsystem:verifyThemePackaging",
        }
        for path, builds_apk in [
            ("android/app/src/main/AndroidManifest.xml", True),
            ("android/feature/chats/src/main/res/values/strings.xml", True),
            ("android/feature/chats/src/main/kotlin/Chat.kt", False),
        ]:
            with self.subTest(path=path):
                selected = scope([path], "pull_request")
                self.assertEqual(builds_apk, selected["apk"])
                env = os.environ.copy()
                env.update(
                    CHECK_FULL="false",
                    BUILD_APK=str(selected["apk"]).lower(),
                    COMPILE_INSTRUMENTATION=str(selected["instrumentation"]).lower(),
                    FOCUSED_TASKS=" ".join(selected["tasks"]),
                )
                result = subprocess.run(
                    [bash, "-c", script], env=env, check=True, capture_output=True, text=True,
                )
                tasks = set(result.stdout.splitlines())
                self.assertTrue(set(selected["tasks"]).issubset(tasks))
                self.assertEqual(packaging_tasks if builds_apk else set(), packaging_tasks & tasks)

    def test_java_and_debug_source_changes_compile_the_app(self):
        for path in [
            "android/app/src/main/java/Main.java",
            "android/feature/chats/src/debug/kotlin/DebugChat.kt",
        ]:
            with self.subTest(path=path):
                selected = scope([path], "pull_request")
                self.assertFalse(selected["apk"])
                self.assertIn(":app:compileDebugKotlin", selected["tasks"])
                self.assertIn(":app:compileDebugJavaWithJavac", selected["tasks"])

    def test_code_and_resource_changes_use_apk_compilation_without_duplicate_app_tasks(self):
        selected = scope([
            "android/feature/chats/src/main/kotlin/Chat.kt",
            "android/feature/chats/src/main/res/values/strings.xml",
        ], "pull_request")
        self.assertTrue(selected["apk"])
        self.assertNotIn(":app:compileDebugKotlin", selected["tasks"])
        self.assertNotIn(":app:compileDebugJavaWithJavac", selected["tasks"])

    def test_test_only_and_dev_tool_changes_do_not_build_the_apk(self):
        for path in [
            "android/core/database/src/test/kotlin/DatabaseTest.kt",
            "android/tools/meshcli/src/main/kotlin/Main.kt",
        ]:
            with self.subTest(path=path):
                result = scope([path], "pull_request")
                self.assertTrue(result["android"])
                self.assertFalse(result["apk"])
                self.assertFalse(result["app_tests"])
                self.assertFalse(any(task.endswith(":lintDebug") for task in result["tasks"]))

    def test_module_instrumentation_changes_are_compiled_without_an_emulator(self):
        selected = scope([
            "android/platform/notifications/src/androidTest/kotlin/NotificationTest.kt",
        ], "pull_request")
        self.assertIn(":platform:notifications:testDebugUnitTest", selected["tasks"])
        self.assertIn(":platform:notifications:compileDebugAndroidTestKotlin", selected["tasks"])
        self.assertFalse(selected["apk"])
        self.assertFalse(selected["app_tests"])

    def test_app_and_shared_test_inputs_run_app_suite_and_instrumentation_when_needed(self):
        for path, apk, instrumentation in [
            ("android/app/src/main/kotlin/Main.kt", False, False),
            ("android/app/src/test/kotlin/MainTest.kt", False, False),
            ("android/app/src/androidTest/kotlin/MainTest.kt", False, True),
            ("android/ui-tests/src/shared/kotlin/Ui.kt", False, True),
            ("android/screenshot-tests/src/test/kotlin/ScreenTest.kt", False, False),
        ]:
            with self.subTest(path=path):
                selected = scope([path], "pull_request")
                self.assertTrue(selected["app_tests"])
                self.assertEqual(apk, selected["apk"])
                self.assertEqual(instrumentation, selected["instrumentation"])
                self.assertEqual([
                    ":app:compileDebugJavaWithJavac", ":app:compileDebugKotlin", ":app:lintDebug",
                ] if "/src/main/kotlin/" in path else [], selected["tasks"])
                self.assertEqual(bool(selected["tasks"]) or instrumentation, selected["build"])

    def test_app_test_only_changes_skip_the_independent_build(self):
        selected = scope(["android/app/src/test/kotlin/NavigationTest.kt"], "pull_request")
        self.assertTrue(selected["android"])
        self.assertTrue(selected["app_tests"])
        self.assertFalse(selected["build"])
        self.assertEqual([], selected["tasks"])

    def test_localization_keeps_its_test_evidence_guard(self):
        selected = scope(["android/core/l10n/src/main/res/values/strings.xml"], "pull_request")
        self.assertIn(":core:l10n:verifyL10nTests", selected["tasks"])
        self.assertIn(":core:l10n:lintDebug", selected["tasks"])

    def test_build_locks_workflows_and_oracle_harnesses_run_everything(self):
        for path in [
            "android/app/build.gradle.kts", "android/core/data/gradle.lockfile",
            "android/gradle/verification-metadata.xml", "android/build-logic/convention/src/main/Plugin.kt",
            "android/scaffold/inspect_apk.py", ".github/workflows/android-ci.yml",
            ".github/actions/setup-android/action.yml", "tools/android-port/oracle/wp203_interop.py",
            "docs/android/evidence/WP-203/WP203InteropTests.swift",
            "android/core/testing/fixtures/reference-codec/backup.json",
            "android/benchmark/dataset/kotlin/Workload.kt",
        ]:
            with self.subTest(path=path):
                self.assertEqual(full_scope(), scope([path], "pull_request"))

    def test_deleted_and_multiple_changed_paths_cannot_hide_expensive_work(self):
        selected = scope([
            "docs/android/README.md", "android/feature/tools/src/main/kotlin/Screen.kt",
            "android/core/model/src/main/kotlin/Envelope.kt",
        ], "pull_request")
        self.assertIn(":feature:tools:testDebugUnitTest", selected["tasks"])
        self.assertIn(":core:model:test", selected["tasks"])
        self.assertIn(":app:compileDebugKotlin", selected["tasks"])
        self.assertFalse(selected["apk"])

    def test_cli_uses_complete_git_diff_and_detects_deleted_backup_source(self):
        script = Path(__file__).resolve().parents[1] / "ci_scope.py"
        with tempfile.TemporaryDirectory() as directory:
            def git(*args):
                return subprocess.run(
                    ["git", *args], cwd=directory, check=True, capture_output=True, text=True,
                ).stdout.strip()

            git("init", "--quiet")
            git("config", "user.name", "CI fixture")
            git("config", "user.email", "fixture@example.invalid")
            source = Path(directory) / "android" / "core" / "data" / "Backup.kt"
            source.parent.mkdir(parents=True)
            source.write_text("old backup implementation\n", encoding="utf-8")
            renamed_source = Path(directory) / "android" / "feature" / "tools" / "src" / "main" / "Screen.kt"
            renamed_source.parent.mkdir(parents=True)
            renamed_source.write_text("old screen implementation\n", encoding="utf-8")
            git("add", ".")
            git("commit", "--quiet", "-m", "base")
            base = git("rev-parse", "HEAD")
            source.unlink()
            # GitHub's changed-file API caps results. The local diff must not.
            for index in range(310):
                document = Path(directory) / "docs" / f"note-{index}.md"
                document.parent.mkdir(exist_ok=True)
                document.write_text("documentation\n", encoding="utf-8")
            renamed_source.rename(Path(directory) / "docs" / "renamed-screen.md")
            git("add", "-A")
            git("commit", "--quiet", "-m", "remove backup and change docs")
            selected = subprocess.run(
                [sys.executable, str(script), "--event", "pull_request", "--base", base],
                cwd=directory, check=True, capture_output=True, text=True,
            ).stdout
            self.assertIn("android=true", selected)
            self.assertIn(":core:data:testDebugUnitTest", selected)
            self.assertIn(":feature:tools:testDebugUnitTest", selected)
            self.assertIn("full=false", selected)
            pushed = subprocess.run(
                [sys.executable, str(script), "--event", "push", "--base", base],
                cwd=directory, check=True, capture_output=True, text=True,
            ).stdout
            self.assertEqual(selected, pushed)

    def test_missing_base_or_invalid_git_revision_fails_instead_of_skipping(self):
        script = Path(__file__).resolve().parents[1] / "ci_scope.py"
        for extra in ([], ["--base", "not-a-real-revision"]):
            result = subprocess.run(
                [sys.executable, str(script), "--event", "pull_request", *extra],
                capture_output=True, text=True,
            )
            self.assertNotEqual(result.returncode, 0)
            self.assertNotIn("android=false", result.stdout)

    def test_missing_push_base_falls_back_to_full_instead_of_skipping(self):
        script = Path(__file__).resolve().parents[1] / "ci_scope.py"
        for extra in ([], ["--base", "0" * 40], ["--base", "not-a-real-revision"]):
            result = subprocess.run(
                [sys.executable, str(script), "--event", "push", *extra],
                check=True, capture_output=True, text=True,
            )
            self.assertIn("full=true", result.stdout)
            self.assertIn("::warning::", result.stderr)

    def test_manual_focus_is_explicit_and_never_accepted_for_a_pr(self):
        script = Path(__file__).resolve().parents[1] / "ci_scope.py"
        path = "android/core/protocol/src/main/kotlin/Packet.kt"
        result = subprocess.run(
            [sys.executable, str(script), "--event", "workflow_dispatch", "--focus", path],
            check=True, capture_output=True, text=True,
        )
        self.assertIn("full=false", result.stdout)
        self.assertIn(":core:protocol:test", result.stdout)
        invalid = subprocess.run(
            [sys.executable, str(script), "--event", "pull_request", "--focus", path],
            capture_output=True, text=True,
        )
        self.assertNotEqual(0, invalid.returncode)


if __name__ == "__main__":
    unittest.main()
