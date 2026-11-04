"""AndroidOnly: WP-003 Adversarial compiler-boundary controls, not real compiler execution."""

import copy
from dataclasses import asdict
import io
import json
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

from controller import compiler_verification as compiler
from controller.errors import PortError
from controller.gates import Binding
from controller.model import REFERENCE_SHA, git
from controller.workflows import ACTION_PINS, parse_yaml

REPO = Path(__file__).resolve().parents[3]
SOURCE = "android/app/src/main/kotlin/Consumer.kt"
SOURCE_ENTRIES = {path: "fixture-blob" for path in (*compiler.FIXTURES, SOURCE)}
EXPECTED_SOURCES = compiler.compiler_sources(SOURCE_ENTRIES)


def valid_actions():
    return [
        {"task": task, "classes": 3, "output_sha256": "e" * 64,
         "sources": len(EXPECTED_SOURCES[task]),
         "source_paths_sha256": compiler.source_paths_digest(EXPECTED_SOURCES[task])}
        for task in compiler.TASKS
    ]


class CompilerProjectionTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.repo = Path(self.temporary.name) / "repo"
        self.repo.mkdir()
        for arguments in (
            ("init", "--quiet"), ("config", "user.email", "fixture@example.invalid"),
            ("config", "user.name", "Fixture"),
        ):
            git(self.repo, *arguments)
        for path, data in {
            "android/gradlew": "trusted wrapper\n",
            "android/trusted-executable.sh": "trusted executable\n",
            "android/settings.gradle.kts": "trusted settings\n",
            "android/app/build.gradle.kts": "trusted compiler actions\n",
            "tools/android-port/controller/compiler_verification.py": "trusted verifier\n",
            "tools/android-port/controller/toolchain-pins.json": "{}\n",
            SOURCE: "class Consumer\n",
            **{path: "class Fixture\n" for path in compiler.FIXTURES},
        }.items():
            self.write(path, data)
        (self.repo / "android/trusted-executable.sh").chmod(0o755)
        self.base = self.commit()

    def write(self, path, data):
        file = self.repo / path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(data, encoding="utf-8")

    def commit(self):
        git(self.repo, "add", ".")
        git(self.repo, "commit", "--quiet", "-m", "fixture")
        return git(self.repo, "rev-parse", "HEAD").decode().strip()

    def test_candidate_launcher_forgery_is_not_projected_but_production_source_is(self):
        self.write(SOURCE, "class ChangedConsumer\n")
        verifier = "tools/android-port/controller/compiler_verification.py"
        self.write(verifier, "forge compiler success\n")
        head = self.commit()
        entries, inputs = compiler.projection(self.repo, self.base, head)
        root = Path(self.temporary.name) / "isolated"
        compiler.materialize(self.repo, entries, root, self.base)
        self.assertEqual((root / SOURCE).read_text(), "class ChangedConsumer\n")
        self.assertEqual((root / verifier).read_text(), "trusted verifier\n")
        self.assertFalse((root / ".git").exists())
        self.assertTrue((root / "android/trusted-executable.sh").stat().st_mode & 0o100)
        self.assertFalse((root / SOURCE).stat().st_mode & 0o111)
        self.assertNotEqual(inputs["trusted_tree"], inputs["candidate_tree"])
        compiler.verify_projection(root, entries)
        (root / SOURCE).write_text("changed during execution")
        with self.assertRaisesRegex(PortError, "Compiler input changed"):
            compiler.verify_projection(root, entries)

    def test_candidate_configuration_processor_wrapper_and_resource_changes_are_refused(self):
        for path in (
            "android/app/build.gradle.kts", "android/settings.gradle.kts",
            "android/gradlew", "android/gradle.properties",
            "android/build-logic/src/main/kotlin/EvilPlugin.kt",
            "android/app/src/main/resources/META-INF/services/javax.annotation.processing.Processor",
            "android/app/src/main/AndroidManifest.xml",
            "android/app/src/main/kotlin/build.gradle.kts",
            "android/app/src/main/java/Uncompiled.java",
            "android/app/src/release/kotlin/Uncompiled.kt",
            "android/core/data/src/main/kotlin/Uncompiled.kt",
        ):
            with self.subTest(path=path):
                self.write(path, "disabled compiler and forged success\n")
                head = self.commit()
                with self.assertRaisesRegex(PortError, "source-only boundary refuses"):
                    compiler.projection(self.repo, self.base, head)
                git(self.repo, "rm", "--quiet", "--", path)
                if path in ("android/app/build.gradle.kts", "android/settings.gradle.kts", "android/gradlew"):
                    self.write(path, git(self.repo, "show", self.base + ":" + path).decode())
                self.commit()

    def test_new_module_is_not_admitted_as_plain_source(self):
        self.write("android/core/untrusted/src/main/kotlin/Plugin.kt", "class Plugin\n")
        with self.assertRaisesRegex(PortError, "source-only boundary refuses"):
            compiler.projection(self.repo, self.base, self.commit())

    def test_deleted_consumer_fixture_is_refused(self):
        (self.repo / compiler.FIXTURES[0]).unlink()
        with self.assertRaisesRegex(PortError, "requires both actual consumer fixtures"):
            compiler.projection(self.repo, self.base, self.commit())

    def test_source_addition_deletion_and_test_changes_bind_only_production(self):
        (self.repo / SOURCE).unlink()
        replacement = "android/app/src/main/kotlin/New.kt"
        self.write(replacement, "class New")
        test = "android/app/src/test/kotlin/UnexecutedTest.kt"
        self.write(test, 'error("this test is not a compiler action")')
        entries, _ = compiler.projection(self.repo, self.base, self.commit())
        self.assertNotIn(SOURCE, entries)
        self.assertIn(replacement, entries)
        self.assertNotIn(test, entries)

    def test_variant_source_coverage_is_exact_and_includes_every_selected_input(self):
        benchmark = "android/app/src/benchmark/kotlin/Benchmark.kt"
        profile = "android/app/src/profile/kotlin/Profile.kt"
        selected = compiler.compiler_sources({**SOURCE_ENTRIES, benchmark: "x", profile: "y"})
        self.assertEqual(selected[compiler.TASKS[0]], sorted([*SOURCE_ENTRIES, benchmark]))
        self.assertEqual(selected[compiler.TASKS[1]], sorted([*SOURCE_ENTRIES, profile]))

    def test_oversized_and_linked_sources_are_refused(self):
        self.write(SOURCE, "x" * (compiler.MAX_SOURCE + 1))
        entries, _ = compiler.projection(self.repo, self.base, self.commit())
        with self.assertRaisesRegex(PortError, "Oversized compiler source"):
            compiler.materialize(self.repo, entries, Path(self.temporary.name) / "large", self.base)
        (self.repo / SOURCE).unlink()
        (self.repo / SOURCE).symlink_to("/etc/passwd")
        with self.assertRaisesRegex(PortError, "Unsupported reference entry"):
            compiler.projection(self.repo, self.base, self.commit())


class CompilerIsolationTests(unittest.TestCase):
    def command(self, offline=True):
        return compiler.docker_command(
            "sha256:" + "a" * 64, Path("/private/project"), Path("/private/home"),
            Path("/private/jdk"), Path("/private/sdk"), Path("/private/guard.gradle"), offline=offline,
            sources=Path("/private/compiler-sources.json"),
        )

    def test_container_is_credential_free_and_offline_without_candidate_configuration(self):
        with patch.dict(os.environ, {"GITHUB_TOKEN": "fixture-secret", "JAVA_TOOL_OPTIONS": "evil"}):
            command = self.command()
        self.assertEqual(command[command.index("--network") + 1], "none")
        for required in ("--read-only", "--cap-drop=ALL", "--security-opt=no-new-privileges",
                         "--no-build-cache", "--no-configuration-cache", "--rerun-tasks", "--offline"):
            self.assertIn(required, command)
        environment = [command[index + 1] for index, value in enumerate(command) if value == "--env"]
        self.assertFalse(any("TOKEN" in value or "SECRET" in value or "JAVA_TOOL_OPTIONS" in value
                             for value in environment))
        mounts = [command[index + 1] for index, value in enumerate(command) if value == "--mount"]
        self.assertFalse(any("/.git" in value or "docker.sock" in value or "/root" in value for value in mounts))
        self.assertEqual([value for value in mounts if not value.endswith(",readonly")], [
            "type=bind,src=/private/project,dst=/project",
            "type=bind,src=/private/home,dst=/home/gradle",
        ])
        self.assertEqual(command[command.index("/project/android/gradlew") + 1:][:2], list(compiler.TASKS))
        self.assertNotIn("--offline", self.command(offline=False))

    def test_mount_delimiter_injection_is_refused(self):
        with self.assertRaisesRegex(PortError, "Unsafe independent compiler mount"):
            compiler.docker_command("image", Path("/project,src=/root"), Path("/home"),
                                    Path("/jdk"), Path("/sdk"), Path("/guard"), Path("/sources"), offline=True)

    def test_timeout_removes_only_the_exact_container_created_by_this_invocation(self):
        identifier = "a" * 64
        calls = []

        def checked(command, **kwargs):
            calls.append(command)
            if command[:2] == ["docker", "create"]:
                return SimpleNamespace(stdout=identifier + "\n")
            if command[:2] == ["docker", "start"]:
                raise PortError("fixture timeout")
            return SimpleNamespace(stdout="")

        with tempfile.TemporaryDirectory() as temporary, patch.object(compiler, "checked", side_effect=checked):
            with self.assertRaisesRegex(PortError, "fixture timeout"):
                compiler.container_compile(["docker", "create"], Path(temporary) / "compiler.log")
        self.assertEqual(calls[-1], ["docker", "rm", "--force", identifier])
        self.assertFalse(any("prune" in call or "killall" in call for call in calls))

    def test_non_owned_container_identity_never_reaches_start_or_remove(self):
        with patch.object(compiler, "checked", return_value=SimpleNamespace(stdout="incumbent")) as checked:
            with self.assertRaisesRegex(PortError, "exact owned container ID"):
                compiler.container_compile(["docker", "create"], Path("/unused"))
        checked.assert_called_once()

    def test_execute_refuses_candidate_before_tools_or_docker(self):
        binding = Binding("cbattlegear/MeshCoreOne-Android", "WP-003", "a" * 40, "b" * 40,
                          REFERENCE_SHA, "c" * 64, "d" * 64)
        with patch.dict(os.environ, {"GITHUB_REF": "refs/heads/candidate"}), \
                patch.object(compiler, "checked") as checked:
            with self.assertRaisesRegex(PortError, "deployed default-branch"):
                compiler.execute(REPO, binding, {}, Path("/unused"), 1, 1)
        checked.assert_not_called()

    def test_authenticated_reader_refuses_candidate_or_modified_runtime_checkout(self):
        with patch.object(compiler, "git", return_value=b"a" * 40), \
                patch("controller.runtime_inputs.verify_committed_inputs") as verify:
            with self.assertRaisesRegex(PortError, "exact deployed current-base"):
                compiler.trusted_checkout(REPO, "b" * 40)
            verify.assert_not_called()
            compiler.trusted_checkout(REPO, "a" * 40)
            verify.assert_called_once_with(REPO, "a" * 40)
            verify.side_effect = PortError("fixture changed runtime")
            with self.assertRaisesRegex(PortError, "changed runtime"):
                compiler.trusted_checkout(REPO, "a" * 40)

    def test_actual_runtime_file_digests_are_bound_and_missing_inputs_fail(self):
        with patch.object(Path, "is_file", return_value=True), \
                patch.object(Path, "open", side_effect=lambda *args: io.BytesIO(b"runtime-fixture")):
            identity = compiler.toolchain_identity(Path("/jdk"), Path("/sdk"))
        self.assertEqual(set(identity), set(compiler.RUNTIME_FILES))
        self.assertEqual(set(identity.values()), {
            compiler.hashlib.sha256(b"runtime-fixture").hexdigest(),
        })
        with patch.object(Path, "is_file", return_value=False):
            with self.assertRaisesRegex(PortError, "runtime input missing"):
                compiler.toolchain_identity(Path("/jdk"), Path("/sdk"))

    def test_missing_duplicate_skipped_and_zero_output_observations_are_refused(self):
        valid = valid_actions()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path = root / "compiler-observations.jsonl"
            for values, allowed in ((valid, True), ([], False), (valid[:1], False),
                                    ([valid[0], valid[0]], False), ([None, valid[1]], False),
                                    ([{**valid[0], "classes": 0}, valid[1]], False),
                                    ([{**valid[0], "sources": 1}, valid[1]], False),
                                    ([{**valid[0], "source_paths_sha256": "a" * 64}, valid[1]], False),
                                    ([{**valid[0], "skipped": True}, valid[1]], False)):
                path.write_text("".join(json.dumps(value) + "\n" for value in values), encoding="utf-8")
                with self.subTest(values=values):
                    if allowed:
                        self.assertEqual(compiler.observations(root, EXPECTED_SOURCES), valid)
                    else:
                        with self.assertRaises(PortError):
                            compiler.observations(root, EXPECTED_SOURCES)


class AuthenticatedCompilerTests(unittest.TestCase):
    def setUp(self):
        self.binding = Binding("cbattlegear/MeshCoreOne-Android", "WP-003", "a" * 40, "b" * 40,
                               REFERENCE_SHA, "c" * 64, "d" * 64)
        self.native = SimpleNamespace(
            workflow_run=Mock(return_value=({
                "head_branch": "main", "display_title": f"Independent compiler PR 518 head {'b' * 40}",
                "actor": {"login": "coordinator"}, "triggering_actor": {"login": "coordinator"},
            }, {})),
            policy={"default_branch": "main", "review_policy": {"authorized_coordinators": ["coordinator"]}},
            prefix="/repos/cbattlegear/MeshCoreOne-Android", manifest=SimpleNamespace(repo=REPO),
            backend=SimpleNamespace(pages=Mock(return_value=[{
                "id": 7, "name": "Independent compiler experiment", "run_id": 1, "head_sha": "a" * 40,
                "status": "completed", "conclusion": "success",
            }])),
        )
        self.inputs = {"source": "fixture-exact-source"}
        self.report = {
            "schema_version": 1, "scope": "independent-compiler-actions-only",
            "binding": asdict(self.binding), "run_id": 1, "run_attempt": 2, "nonce": "f" * 32,
            "inputs": self.inputs, "network": "none", "candidate_configuration_executed": False,
            "required_check": False, "wp_complete": False, "dependency_unlock": False,
            "runtime": {path: "a" * 64 for path in compiler.RUNTIME_FILES},
            "actions": valid_actions(),
        }

    def verify(self, report=None, logs=None):
        text = json.dumps(self.report if report is None else report)
        log = f"job\t{compiler.STEP}\t2026-11-04T22:00:00Z {text}\n"
        with patch("controller.repair_acceptance.read_job_log", return_value=log if logs is None else logs), \
                patch.object(compiler, "projection", return_value=(SOURCE_ENTRIES, self.inputs)):
            return compiler.authenticated_result(self.native, self.binding, 518, 1, 2)

    def test_actual_authenticated_run_has_bounded_nonrequired_result(self):
        result = self.verify()
        self.assertFalse(result["required_check"])
        self.assertFalse(result["wp_complete"])
        self.assertFalse(result["dependency_unlock"])
        self.native.workflow_run.assert_called_once_with(
            1, 2, compiler.WORKFLOW, self.binding.base_sha, ("workflow_dispatch",),
        )

    def test_replayed_forged_skipped_and_overclaimed_reports_are_refused(self):
        for key, value in (
            ("run_id", 9), ("run_attempt", 1), ("run_id", True),
            ("inputs", {}), ("network", "bridge"), ("candidate_configuration_executed", True),
            ("required_check", True), ("wp_complete", True), ("dependency_unlock", True),
            ("nonce", ""), ("actions", []), ("actions", [None, None]),
            ("runtime", {}), ("runtime", {path: "" for path in compiler.RUNTIME_FILES}),
            ("binding", {**asdict(self.binding), "head_sha": "e" * 40}),
            ("actions", [self.report["actions"][0]] * 2),
            ("actions", [{**self.report["actions"][0], "sources": 1}, self.report["actions"][1]]),
            ("actions", [{**self.report["actions"][0], "source_paths_sha256": "a" * 64},
                         self.report["actions"][1]]),
        ):
            report = copy.deepcopy(self.report)
            report[key] = value
            with self.subTest(key=key, value=value), self.assertRaises(PortError):
                self.verify(report)
        line = f"job\t{compiler.STEP}\t2026-11-04T22:00:00Z {json.dumps(self.report)}\n"
        for log in ("", line + line, line.replace(compiler.STEP, "Execute isolated trusted-base compiler actions")):
            with self.subTest(log=log), self.assertRaises(PortError):
                self.verify(logs=log)

    def test_candidate_actor_ref_or_failed_job_is_not_an_authority(self):
        original = self.native.workflow_run.return_value
        for change in (
            {"head_branch": "candidate"}, {"actor": {"login": "producer"}},
            {"triggering_actor": {"login": "producer"}}, {"display_title": "Independent compiler PR 519"},
        ):
            self.native.workflow_run.return_value = ({**original[0], **change}, {})
            with self.subTest(change=change), self.assertRaises(PortError):
                self.verify()
        self.native.workflow_run.return_value = original
        self.native.backend.pages.return_value[0]["conclusion"] = "skipped"
        with self.assertRaises(PortError):
            self.verify()


class CompilerWorkflowTests(unittest.TestCase):
    def test_report_requires_an_explicit_output_directory(self):
        with patch("sys.stderr", new_callable=io.StringIO) as errors:
            self.assertEqual(compiler.main(["report"]), 2)
        self.assertIn("requires an explicit output directory", errors.getvalue())

    def test_workflow_is_manual_nonrequired_default_branch_with_no_publisher_or_candidate_checkout(self):
        text = (REPO / compiler.WORKFLOW).read_text()
        value = parse_yaml(text)
        self.assertEqual(set(value["on"]), {"workflow_dispatch"})
        self.assertEqual(set(value["permissions"].values()), {"read"})
        job = value["jobs"]["compiler"]
        self.assertEqual(job["if"], "github.ref == format('refs/heads/{0}', github.event.repository.default_branch)")
        checkout = job["steps"][0]
        self.assertEqual(checkout["with"], {
            "ref": "${{ github.sha }}", "fetch-depth": "0", "persist-credentials": "false",
        })
        self.assertEqual(checkout["uses"], "actions/checkout@" + ACTION_PINS["actions/checkout"])
        self.assertNotIn("checks: write", text)
        self.assertNotIn("pull_request_target", text)
        self.assertNotIn("actions/cache@", text)
        self.assertNotIn("continue-on-error", text)
        self.assertEqual(job["steps"][-1]["name"], compiler.STEP)
        self.assertNotIn("env", job["steps"][-1])
        rules = json.loads((REPO / "docs/android/automation-policy.json").read_text())
        self.assertNotIn("Independent compiler experiment", rules["required_checks"])

    def test_exact_workflow_guard_rejects_candidate_execution_and_failure_overrides(self):
        text = (REPO / compiler.WORKFLOW).read_text()
        value = parse_yaml(text)
        compiler.validate_workflow(value, text)
        for mutation in ("checkout", "cache", "token-report", "shell", "ignored-failure", "trigger", "command"):
            altered = copy.deepcopy(value)
            job = altered["jobs"]["compiler"]
            if mutation == "checkout":
                job["steps"][0]["with"]["ref"] = "${{ inputs.head_sha }}"
            elif mutation == "cache":
                job["steps"].insert(2, {"uses": "actions/cache@" + ACTION_PINS["actions/cache"]})
            elif mutation == "token-report":
                job["steps"][-1]["env"] = {"TOKEN": "${{ github.token }}"}
            elif mutation == "shell":
                job["defaults"] = {"run": {"shell": "bash -c 'exit 0'"}}
            elif mutation == "ignored-failure":
                job["steps"][-2]["continue-on-error"] = "true"
            elif mutation == "trigger":
                altered["on"]["pull_request_target"] = {}
            else:
                job["steps"][-2]["run"] += "\necho forged success"
            with self.subTest(mutation=mutation), self.assertRaises(PortError):
                compiler.validate_workflow(altered, text)


if __name__ == "__main__":
    unittest.main()
