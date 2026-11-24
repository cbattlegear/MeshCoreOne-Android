"""AndroidOnly: WP-003 Keep Android CI selection conservative and independent of live interop."""

import unittest
from pathlib import Path
import subprocess
import sys
import tempfile

from ci_scope import scope


class CiScopeTest(unittest.TestCase):
    def test_main_manual_and_unknown_paths_run_everything(self):
        for paths, event in [
            ([], "push"), ([], "workflow_dispatch"),
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
                self.assertTrue(all(scope(paths, event).values()))

    def test_documentation_only_pr_still_leaves_source_checks_to_workflow(self):
        self.assertFalse(any(scope(["docs/android/README.md", "README.md"], "pull_request").values()))

    def test_ui_pr_runs_all_android_tests_without_lock_maintenance(self):
        selected = scope(["android/feature/chats/src/main/kotlin/Chat.kt"], "pull_request")
        self.assertEqual(selected, dict(android=True, dependencies=False))

    def test_backend_backup_and_fixture_changes_run_android_tests(self):
        for path in [
            "android/core/data/src/main/kotlin/Backup.kt",
            "android/core/database/schemas/Database/2.json",
            "android/core/datastore/src/main/kotlin/Preferences.kt",
            "android/core/services/src/main/kotlin/Service.kt",
            "android/core/testing/fixtures/reference-codec/backup.json",
        ]:
            with self.subTest(path=path):
                self.assertEqual(scope([path], "pull_request"), dict(android=True, dependencies=False))
        self.assertTrue(scope(["android/core/protocol/src/test/kotlin/CodecTest.kt"], "pull_request")["android"])

    def test_build_locks_workflows_and_oracle_harnesses_run_everything(self):
        for path in [
            "android/app/build.gradle.kts", "android/core/data/gradle.lockfile",
            "android/gradle/verification-metadata.xml", "android/build-logic/convention/src/main/Plugin.kt",
            "android/scaffold/inspect_apk.py", ".github/workflows/android-ci.yml",
            ".github/actions/setup-android/action.yml", "tools/android-port/oracle/wp203_interop.py",
            "docs/android/evidence/WP-203/WP203InteropTests.swift",
        ]:
            with self.subTest(path=path):
                self.assertTrue(all(scope([path], "pull_request").values()))

    def test_deleted_and_multiple_changed_paths_cannot_hide_expensive_work(self):
        self.assertTrue(scope([
            "docs/android/README.md", "android/feature/tools/src/main/Screen.kt",
            "android/core/model/src/main/kotlin/Envelope.kt",
        ], "pull_request")["android"])
        self.assertTrue(scope(["android/benchmark/dataset/kotlin/Workload.kt"], "pull_request")["android"])

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
            git("add", ".")
            git("commit", "--quiet", "-m", "base")
            base = git("rev-parse", "HEAD")
            source.unlink()
            # GitHub's changed-file API caps results. The local diff must not.
            for index in range(310):
                document = Path(directory) / "docs" / f"note-{index}.md"
                document.parent.mkdir(exist_ok=True)
                document.write_text("documentation\n", encoding="utf-8")
            git("add", "-A")
            git("commit", "--quiet", "-m", "remove backup and change docs")
            selected = subprocess.run(
                [sys.executable, str(script), "--event", "pull_request", "--base", base],
                cwd=directory, check=True, capture_output=True, text=True,
            ).stdout
            self.assertIn("android=true", selected)
            self.assertNotIn("backup=", selected)

    def test_missing_base_or_invalid_git_revision_fails_instead_of_skipping(self):
        script = Path(__file__).resolve().parents[1] / "ci_scope.py"
        for extra in ([], ["--base", "not-a-real-revision"]):
            result = subprocess.run(
                [sys.executable, str(script), "--event", "pull_request", *extra],
                capture_output=True, text=True,
            )
            self.assertNotEqual(result.returncode, 0)
            self.assertNotIn("android=false", result.stdout)


if __name__ == "__main__":
    unittest.main()
