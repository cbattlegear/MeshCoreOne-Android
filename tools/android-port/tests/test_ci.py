"""AndroidOnly: WP-504 Real source-disposition guards without widening scoped Gradle work."""

import contextlib
from dataclasses import replace
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

from fixtures import base_manifest
from controller import ci
from controller.ci_evidence import bind_test_lineage, prepare_test_lineage, read_test_lineage
from controller.errors import PortError
from oracle.workflow_scope import classify

SOURCE = "android/core/protocol/src/test/kotlin/com/meshcoreone/android/core/protocol/parity/TcpLifecycleParityTest.kt"
PIN = "db14559b39d32322b06477c6ae676112f583db50"
PORTED = (
    f"// PortedFrom: MeshCore/Tests/MeshCoreTests/Transport/WiFiTransportTests.swift@{PIN}\n"
    f"// PortedFrom: MeshCore/Sources/MeshCore/Transport/WiFiFrameCodec.swift@{PIN}\n"
    f"// PortedFrom: MeshCore/Sources/MeshCore/Protocol/PacketParser.swift@{PIN}\n"
)
MIXED = PORTED + (
    "// AndroidOnly: WP-109 Public localhost transport/parser/dispatcher regressions "
    "with source two-second phase deadlines.\n"
)


class BenchmarkConsumerTests(unittest.TestCase):
    def test_compile_scope_is_exclusive_and_does_not_change_existing_task_plans(self):
        selected = [ci.BENCHMARK_CONSUMER_SCOPE]
        self.assertEqual(ci.stage_tasks("scaffold", selected), list(ci.BENCHMARK_CONSUMER_TASKS))
        self.assertNotIn(ci.BENCHMARK_CONSUMER_SCOPE, ci.SCOPED_TASKS)
        self.assertNotEqual(list(ci.BENCHMARK_CONSUMER_TASKS), ci.TASKS["benchmark"])
        self.assertEqual(
            ci.stage_tasks("scaffold", ["scaffold"]),
            list(dict.fromkeys(ci.SCOPED_TASKS["scaffold"])),
        )
        for stage, scopes, affected in (
            ("benchmark", selected, False),
            ("protocol", selected, False),
            ("scaffold", selected + ["scaffold"], False),
            ("scaffold", selected + selected, False),
            ("scaffold", selected, True),
        ):
            with self.subTest(stage=stage, scopes=scopes, affected=affected), self.assertRaises(PortError):
                ci.stage_tasks(stage, scopes, affected_candidate=affected)

    def test_gradle_guard_requires_executed_compilers_and_committed_fixture_sources(self):
        guard = ci.TASK_GRAPH_GUARD.read_text(encoding="utf-8")
        for required in (
            "ANDROID_CI_BENCHMARK_CONSUMER",
            *ci.BENCHMARK_CONSUMER_KOTLIN_TASKS,
            "task.outputs.upToDateWhen { false }",
            'task.outputs.cacheIf("Benchmark consumer source observation requires task execution") { false }',
            "task.sources.files",
            "gradle.taskGraph.beforeTask { task ->",
            "consumerInputs[task.path] = identify()",
            "gradle.taskGraph.afterTask { task, state ->",
            "state.failure != null",
            "!state.executed",
            "!state.didWork",
            "state.skipped",
            "state.upToDate",
            "state.noSource",
            "state.skipMessage != null",
            "identify() != observed",
        ):
            with self.subTest(required=required):
                self.assertIn(required, guard)
        compiler_configuration = guard.split("gradle.beforeProject", 1)[1].split(
            "project.tasks.withType(org.gradle.api.tasks.testing.Test)", 1,
        )[0]
        self.assertNotIn("task.doFirst", compiler_configuration)
        self.assertNotIn("task.doLast", compiler_configuration)
        observation = guard.split("gradle.taskGraph.afterTask { task, state ->", 1)[1]
        self.assertLess(observation.index("state.skipMessage != null"),
                        observation.index("new File(consumerFile).append"))
        for path in ci.BENCHMARK_CONSUMER_SOURCES:
            self.assertIn(path.removeprefix("android/"), guard)

    def test_hosted_consumer_requires_fresh_project_and_private_gradle_directories(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            project = root / "android"
            module = project / "app"
            module.mkdir(parents=True)
            (project / "settings.gradle.kts").write_text("", encoding="utf-8")
            (module / "build.gradle.kts").write_text("", encoding="utf-8")
            output = root / "output"
            home, cache = ci.prepare_benchmark_consumer_state(project, output)
            self.assertEqual(home, output / "gradle-user-home")
            self.assertEqual(cache, output / "gradle-project-cache")
            self.assertEqual(list(home.iterdir()), [])
            self.assertEqual(list(cache.iterdir()), [])
            with self.assertRaisesRegex(PortError, "must use new directories"):
                ci.prepare_benchmark_consumer_state(project, output)

        for relative in ("build", ".gradle", "app/build", "app/.gradle", "build-logic/build"):
            with self.subTest(previous=relative), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                project = root / "android"
                for script in ("settings.gradle.kts", "app/build.gradle.kts",
                               "build-logic/settings.gradle.kts"):
                    path = project / script
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text("", encoding="utf-8")
                previous = project / relative
                previous.mkdir()
                marker = previous / "retained"
                marker.write_text("unchanged", encoding="utf-8")
                with self.assertRaisesRegex(PortError, "project state is not fresh"):
                    ci.prepare_benchmark_consumer_state(project, root / "output")
                self.assertEqual(marker.read_text(encoding="utf-8"), "unchanged")
                self.assertFalse((root / "output").exists())

        for name in ("gradle-user-home", "gradle-project-cache"):
            with self.subTest(existing=name), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                project = root / "android"
                project.mkdir()
                output = root / "output"
                (output / name).mkdir(parents=True)
                marker = output / name / "retained"
                marker.write_text("unchanged", encoding="utf-8")
                with self.assertRaisesRegex(PortError, "must use new directories"):
                    ci.prepare_benchmark_consumer_state(project, output)
                self.assertEqual(marker.read_text(encoding="utf-8"), "unchanged")

    def test_hosted_consumer_rejects_dangling_junctions_as_existing_state(self):
        for relative in ("android/build", "android/.gradle",
                         "output/gradle-user-home", "output/gradle-project-cache"):
            with self.subTest(previous=relative), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                project = root / "android"
                project.mkdir()
                (project / "settings.gradle.kts").write_text("", encoding="utf-8")
                junction = root / relative
                original = Path.is_junction

                def simulated_junction(path):
                    return path == junction or original(path)

                message = (
                    "project state is not fresh" if relative.startswith("android/")
                    else "must use new directories"
                )
                with patch.object(Path, "is_junction", simulated_junction):
                    with self.assertRaisesRegex(PortError, message):
                        ci.prepare_benchmark_consumer_state(project, root / "output")
                self.assertFalse(junction.exists())
                self.assertFalse((root / "output" / "gradle-user-home").exists())

    def test_hosted_consumer_refuses_linked_or_unreadable_project_inputs(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            project = root / "android"
            linked = project / "linked"
            linked.mkdir(parents=True)
            original = Path.is_symlink

            def simulated_link(path):
                return path == linked or original(path)

            with patch.object(Path, "is_symlink", simulated_link):
                with self.assertRaisesRegex(PortError, "Linked benchmark consumer project"):
                    ci.prepare_benchmark_consumer_state(project, root / "output")

            def inaccessible(*args, onerror, **kwargs):
                onerror(PermissionError("fixture"))
                yield from ()

            with patch.object(ci.os, "walk", inaccessible):
                with self.assertRaisesRegex(PortError, "Cannot inspect benchmark consumer project"):
                    ci.prepare_benchmark_consumer_state(project, root / "output")

            linked_output = root / "linked-output"
            linked_output.mkdir()

            def simulated_output_link(path):
                return path == linked_output or original(path)

            with patch.object(Path, "is_symlink", simulated_output_link):
                with self.assertRaisesRegex(PortError, "Linked benchmark consumer output"):
                    ci.prepare_benchmark_consumer_state(project, linked_output / "output")
            with self.assertRaisesRegex(PortError, "must be separate"):
                ci.prepare_benchmark_consumer_state(project, project / "output")
            with self.assertRaisesRegex(PortError, "must be absolute"):
                ci.prepare_benchmark_consumer_state(project, Path("relative-output"))

    def test_hosted_consumer_cli_rejects_linked_output_before_creating_it(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            target = root / "target"
            target.mkdir()
            linked = root / "linked"
            if os.name == "nt":
                subprocess.run(
                    [os.environ["ComSpec"], "/c", "mklink", "/J", str(linked), str(target)],
                    check=True, capture_output=True,
                )
            else:
                linked.symlink_to(target, target_is_directory=True)
            try:
                state = root / "state.json"
                state.write_text(json.dumps({
                    "schema_version": 1, "host": ci.host_name(), "java_home": str(root / "jdk"),
                    "android_home": str(root / "sdk"), "private_root": str(root / "private"),
                    "provenance": {},
                }), encoding="utf-8")
                environment = dict(os.environ, GRADLE_USER_HOME=str(root / "unused-gradle-home"))
                completed = subprocess.run(
                    [
                        sys.executable, "-B", str(ci.REPO / "tools" / "android-port" / "controller" / "ci.py"),
                        "run", "--stage", "scaffold", "--scope", ci.BENCHMARK_CONSUMER_SCOPE,
                        "--state", str(state), "--output", str(linked / "output"),
                    ],
                    cwd=ci.REPO, env=environment, capture_output=True, text=True, timeout=120,
                )
                self.assertEqual(completed.returncode, 2, completed.stderr)
                self.assertIn("BLOCKED: Linked benchmark consumer output directory", completed.stderr)
                self.assertEqual(list(target.iterdir()), [])
            finally:
                if os.name == "nt":
                    linked.rmdir()
                else:
                    linked.unlink()

    def test_hosted_consumer_cli_rejects_invalid_selection_before_creating_output(self):
        for stage, scopes, affected in (
            ("scaffold", [ci.BENCHMARK_CONSUMER_SCOPE, "protocol"], False),
            ("scaffold", [ci.BENCHMARK_CONSUMER_SCOPE, ci.BENCHMARK_CONSUMER_SCOPE], False),
            ("benchmark", [ci.BENCHMARK_CONSUMER_SCOPE], False),
            ("scaffold", [ci.BENCHMARK_CONSUMER_SCOPE], True),
            (None, [ci.BENCHMARK_CONSUMER_SCOPE], False),
        ):
            with self.subTest(stage=stage, scopes=scopes, affected=affected), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                output = root / "new-parent" / "output"
                arguments = ["run", "--state", str(root / "state.json"), "--output", str(output)]
                if stage is not None:
                    arguments.extend(("--stage", stage))
                for scope in scopes:
                    arguments.extend(("--scope", scope))
                if affected:
                    arguments.append("--affected-candidate")
                with (
                    patch.object(ci, "REPO", root),
                    patch.object(ci, "inputs", return_value={}),
                    patch.object(ci, "load_manifest", return_value=object()),
                    patch.object(ci, "port_map", return_value=[]),
                    patch.object(ci.platform, "python_version", return_value="3.12.4"),
                    patch.object(ci, "toolchain_lock", return_value={"python": "3.12.4"}),
                    patch.object(ci, "verify_wrapper") as verify_wrapper,
                    patch.object(ci, "execute") as execute,
                    contextlib.redirect_stdout(io.StringIO()),
                    contextlib.redirect_stderr(io.StringIO()) as errors,
                ):
                    result = ci.main(arguments)
                self.assertEqual(result, 2)
                self.assertIn("BLOCKED:", errors.getvalue())
                self.assertFalse(output.parent.exists())
                verify_wrapper.assert_not_called()
                execute.assert_not_called()

    def test_hosted_consumer_cli_creates_valid_output_after_inspection(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "android").mkdir()
            output = root / "output"
            state = {"host": "linux", "private_root": str(root / "private")}

            def stop_before_subprocess(command, environment, log, **kwargs):
                self.assertTrue(output.is_dir())
                self.assertTrue((output / "gradle-user-home").is_dir())
                self.assertTrue((output / "gradle-project-cache").is_dir())
                self.assertEqual(environment["GRADLE_USER_HOME"], str(output / "gradle-user-home"))
                raise PortError("test stopped before subprocess execution")

            with (
                patch.object(ci, "REPO", root),
                patch.object(ci, "inputs", return_value=state),
                patch.object(ci, "load_manifest", return_value=object()),
                patch.object(ci, "port_map", return_value=[]),
                patch.object(ci.platform, "python_version", return_value="3.12.4"),
                patch.object(ci, "toolchain_lock", return_value={"python": "3.12.4"}),
                patch.object(ci, "candidate_environment", return_value={}),
                patch.object(ci, "verify_wrapper") as verify_wrapper,
                patch.object(ci, "execute", side_effect=stop_before_subprocess) as execute,
                contextlib.redirect_stdout(io.StringIO()),
                contextlib.redirect_stderr(io.StringIO()) as errors,
            ):
                result = ci.main([
                    "run", "--stage", "scaffold", "--scope", ci.BENCHMARK_CONSUMER_SCOPE,
                    "--state", str(root / "state.json"), "--output", str(output),
                ])
            self.assertEqual(result, 2)
            self.assertIn("BLOCKED: test stopped before subprocess execution", errors.getvalue())
            verify_wrapper.assert_called_once()
            execute.assert_called_once()

    def test_cli_defers_output_only_for_hosted_consumer_selections(self):
        for local, scopes, initially_created in (
            (False, [ci.BENCHMARK_CONSUMER_SCOPE], False),
            (True, [ci.BENCHMARK_CONSUMER_SCOPE], True),
            (False, ["protocol"], True),
        ):
            with self.subTest(local=local, scopes=scopes), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                output = root / "output"
                arguments = ["run", "--stage", "scaffold", "--state", str(root / "state.json"),
                             "--output", str(output)]
                for scope in scopes:
                    arguments.extend(("--scope", scope))
                if local:
                    arguments.append("--local")

                def observe_stage(*args, **kwargs):
                    self.assertEqual(output.exists(), initially_created)

                with patch.object(ci, "inputs", return_value={}), \
                        patch.object(ci, "run_stage", side_effect=observe_stage) as run_stage:
                    self.assertEqual(ci.main(arguments), 0)
                run_stage.assert_called_once()

    def test_consumer_execution_isolates_hosted_home_and_disables_caches_for_both_phases(self):
        cases = (
            ("linux", False, [ci.BENCHMARK_CONSUMER_SCOPE]),
            ("windows", False, [ci.BENCHMARK_CONSUMER_SCOPE]),
            ("linux", True, [ci.BENCHMARK_CONSUMER_SCOPE]),
            ("linux", False, ["protocol"]),
        )
        for host, local, scopes in cases:
            with self.subTest(host=host, local=local, scopes=scopes), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                project = root / "android"
                project.mkdir()
                (project / "settings.gradle.kts").write_text("", encoding="utf-8")
                output = root / "output"
                calls = []

                def capture(command, environment, log, **kwargs):
                    calls.append((list(command), dict(environment), log))
                    if log.name == "gradle-scaffold.log":
                        raise PortError("captured build")

                with (
                    patch.object(ci, "REPO", root),
                    patch.object(ci, "load_manifest", return_value=object()),
                    patch.object(ci, "port_map", return_value=[]),
                    patch.object(ci.platform, "python_version", return_value="3.12.4"),
                    patch.object(ci, "toolchain_lock", return_value={"python": "3.12.4"}),
                    patch.object(ci, "candidate_environment", return_value={
                        "GRADLE_USER_HOME": str(root / "restored-home"), "PATH": "fixture",
                    }),
                    patch.object(ci, "verify_wrapper"),
                    patch.object(ci, "execute", side_effect=capture),
                    patch.object(ci, "validate_candidate_task_graph", return_value={"tasks": 1}),
                    patch.object(ci, "write_github_outputs"),
                    patch.object(ci.shutil, "which", return_value="fixture-pwsh"),
                    contextlib.redirect_stdout(io.StringIO()),
                    self.assertRaisesRegex(PortError, "captured build"),
                ):
                    ci.run_stage("scaffold", {"host": host, "private_root": str(root / "private")},
                                 output, local=local, scopes=scopes)

                consumer = scopes == [ci.BENCHMARK_CONSUMER_SCOPE]
                expected_home = output / "gradle-user-home" if consumer and not local else root / "restored-home"
                self.assertTrue(calls)
                for _, environment, _ in calls:
                    self.assertEqual(environment["GRADLE_USER_HOME"], str(expected_home))
                gradle = [call for call in calls if call[2].name.startswith("gradle-")]
                self.assertEqual(len(gradle), 2 if consumer else 1)
                for command, _, log in gradle:
                    if host == "windows":
                        suffix = "-preflight" if "preflight" in log.name else ""
                        invocation = json.loads((output / f"gradle-scaffold{suffix}-invocation.json")
                                                .read_text(encoding="utf-8"))
                        arguments = invocation["arguments"]
                    else:
                        arguments = command
                    self.assertEqual("--no-build-cache" in arguments, consumer)
                    self.assertEqual("--no-configuration-cache" in arguments, consumer)
                    self.assertEqual("--build-cache" in arguments, not consumer and not local)
                    self.assertEqual("--stacktrace" in arguments, not local)
                    cache = output / "gradle-project-cache" if consumer and not local else root / "private" / "project-root"
                    self.assertEqual(arguments[arguments.index("--project-cache-dir") + 1], str(cache))

    def test_stale_hosted_consumer_project_stops_before_any_subprocess(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            project = root / "android"
            (project / "build").mkdir(parents=True)
            (project / "settings.gradle.kts").write_text("", encoding="utf-8")
            with (
                patch.object(ci, "REPO", root),
                patch.object(ci, "load_manifest", return_value=object()),
                patch.object(ci, "port_map", return_value=[]),
                patch.object(ci.platform, "python_version", return_value="3.12.4"),
                patch.object(ci, "toolchain_lock", return_value={"python": "3.12.4"}),
                patch.object(ci, "candidate_environment", return_value={}),
                patch.object(ci, "execute") as execute,
                contextlib.redirect_stdout(io.StringIO()),
                self.assertRaisesRegex(PortError, "project state is not fresh"),
            ):
                ci.run_stage("scaffold", {"host": "linux", "private_root": str(root / "private")},
                             root / "output", scopes=[ci.BENCHMARK_CONSUMER_SCOPE])
            execute.assert_not_called()
            self.assertTrue((project / "build").is_dir())

    def test_source_evidence_requires_each_exact_task_and_committed_blob(self):
        snapshot = {path: str(index + 1) * 40 for index, path in enumerate(ci.BENCHMARK_CONSUMER_SOURCES)}
        sources = ci.benchmark_consumer_sources(snapshot)
        valid = [{"task": task, "sources": sources.copy()} for task in ci.BENCHMARK_CONSUMER_KOTLIN_TASKS]
        self.assertEqual(ci.validate_benchmark_consumer_compilation(valid[::-1], snapshot), valid)
        invalid = (
            [],
            valid[:1],
            valid + valid[:1],
            [valid[0], valid[0]],
            [{"task": ":app:compileDebugKotlin", "sources": sources}, valid[1]],
            [{"task": valid[0]["task"], "sources": {}}, valid[1]],
            [{"task": valid[0]["task"], "sources": {**sources, next(iter(sources)): "f" * 40}}, valid[1]],
            [{"task": valid[0]["task"], "sources": sources, "result": "success"}, valid[1]],
            [None, valid[1]],
        )
        for records in invalid:
            with self.subTest(records=records), self.assertRaises(PortError):
                ci.validate_benchmark_consumer_compilation(records, snapshot)
        with self.assertRaises(PortError):
            ci.benchmark_consumer_sources({})

    def test_evidence_reader_rejects_malformed_and_dirty_source_inputs(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            snapshot = {}
            for index, relative in enumerate(ci.BENCHMARK_CONSUMER_SOURCES):
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                data = f"fixture {index}\n".encode()
                path.write_bytes(data)
                snapshot[relative] = hashlib.sha1(f"blob {len(data)}\0".encode() + data).hexdigest()
            records = [
                {"task": task, "sources": snapshot.copy()}
                for task in ci.BENCHMARK_CONSUMER_KOTLIN_TASKS
            ]
            evidence = root / "consumer.jsonl"
            evidence.write_text("".join(json.dumps(record) + "\n" for record in records), encoding="utf-8")
            with patch.object(ci, "git", return_value=("a" * 40).encode()), \
                    patch.object(ci, "tree", return_value=snapshot):
                self.assertEqual(ci.read_benchmark_consumer_compilation(evidence, root), records)
                evidence.write_text("{bad-json}\n", encoding="utf-8")
                with self.assertRaises(PortError):
                    ci.read_benchmark_consumer_compilation(evidence, root)
                evidence.write_text("".join(json.dumps(record) + "\n" for record in records), encoding="utf-8")
                (root / ci.BENCHMARK_CONSUMER_SOURCES[0]).write_text("dirty", encoding="utf-8")
                with self.assertRaisesRegex(PortError, "differs from the committed head"):
                    ci.read_benchmark_consumer_compilation(evidence, root)


class SourceDispositionTests(unittest.TestCase):
    def test_source_capture_uses_completed_same_project_graph_compilers(self):
        guard = ci.TASK_GRAPH_GUARD.read_text(encoding="utf-8")
        self.assertNotIn("taskDependencies.getDependencies", guard)
        self.assertIn("graphTasks = graph.allTasks", guard)
        self.assertIn("candidate.project == task.project", guard)
        self.assertIn("candidate.outputs.files.files.any { classDirs.contains(it.canonicalFile) }", guard)
        self.assertIn("Overlapping Kotlin test compiler outputs", guard)
        self.assertIn("!it.state.executed || it.state.failure != null", guard)
        self.assertIn('":app:testDebugUnitTest", ":core:ui:testDebugUnitTest"', guard)
        for marker in ('observe("START")', 'observe("DEPENDENCIES")',
                       'observe("SOURCES")', 'observe("END")', "WP003_TEST_SUITE|"):
            self.assertIn(marker, guard)

    def test_dynamic_lineage_binds_only_real_unique_junit_case_identities(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = prepare_test_lineage(Path(temporary))
            entry = {"task": ":core:protocol:test", "class": "Fixture", "method": "child",
                     "factory": "family", "descriptor": "family(String)[2][1]"}
            inventory = {"core/protocol": [{"class": "Fixture", "method": "child"}]}
            path.write_text(json.dumps(entry) + "\n", encoding="utf-8")
            runners = {"core/protocol": "test"}
            bind_test_lineage(inventory, read_test_lineage(path), runners)
            self.assertEqual(inventory["core/protocol"][0]["factory"], "family")
            for kind in ("missing-child", "duplicate-event", "wrong-descriptor", "wrong-task", "malformed"):
                altered = dict(entry)
                if kind == "missing-child":
                    altered["method"] = "not-in-report"
                elif kind == "wrong-descriptor":
                    altered["descriptor"] = "other()[1]"
                elif kind == "wrong-task":
                    altered["task"] = ":core:protocol:testDebugUnitTest"
                text = json.dumps(altered) + "\n"
                if kind == "duplicate-event":
                    text += text
                elif kind == "malformed":
                    text = "{not-json}\n"
                path.write_text(text, encoding="utf-8")
                with self.subTest(kind=kind), self.assertRaises(PortError):
                    bind_test_lineage({"core/protocol": [{"class": "Fixture", "method": "child"}]},
                                      read_test_lineage(path), runners)

    def test_timeout_retains_fixed_observations_before_tail_without_inventing_method_completion(self):
        markers = ["WP501_PATH_RECENCY|TASK|:app:compileDebugUnitTestKotlin|END"]
        for (tag, name), methods in ci.WP501_OBSERVATION_METHODS.items():
            markers.extend(f"{tag}|TEST|{name}|{method}|START" for method in methods)
        content = "\n".join(markers) + "\n" + ("later unrelated output\n" * 2000) + "last actual progress\n"
        failure = ci.subprocess.TimeoutExpired(["fixture-gradle"], 1800)
        with tempfile.TemporaryDirectory() as temporary:
            def timeout(command, **kwargs):
                self.assertEqual(kwargs["timeout"], 1800)
                kwargs["stdout"].write(content)
                raise failure
            with patch.object(ci.subprocess, "run", side_effect=timeout), \
                    patch.object(Path, "read_text", side_effect=AssertionError("Whole-log reads forbidden")), \
                    contextlib.redirect_stdout(io.StringIO()), \
                    contextlib.redirect_stderr(io.StringIO()) as diagnostics:
                with self.assertRaises(ci.subprocess.TimeoutExpired) as observed:
                    ci.execute(["fixture-gradle"], {}, Path(temporary) / "timeout.log")
            self.assertIs(observed.exception, failure)
            output = diagnostics.getvalue()
            for marker in markers:
                self.assertNotIn(marker, content[-12000:])
                self.assertEqual(output.count(marker), 1)
            self.assertNotIn("|END|", output)
            self.assertIn("truncated=False|not-test-evidence", output)
            self.assertTrue(output.rstrip().endswith("last actual progress"))

    def test_observation_replay_rejects_nonwhitelisted_names_phases_payloads_and_overlong_lines(self):
        (tag, name), methods = next(iter(ci.WP501_OBSERVATION_METHODS.items()))
        start = f"{tag}|TEST|{name}|{methods[0]}|START"
        invalid = (
            start.replace(name, "com.meshcoreone.android.UnrelatedTest"),
            start.replace(methods[0], "unknownMethod"),
            start.replace(methods[0], methods[0] + "[37]"),
            start + "|PRIVATE_PAYLOAD",
            f"{tag}|PHASE|{name}|{methods[0]}|unknown-phase|END",
            start.replace("|START", "|END|MADE_UP_RESULT"),
            start + "PRIVATE_PAYLOAD" + "x" * 70000,
        )
        content = "\n".join(invalid) + "\n" + ("later unrelated output\n" * 2000) + "last actual progress\n"
        with tempfile.TemporaryDirectory() as temporary:
            log = Path(temporary) / "invalid-observations.log"
            log.write_text(content, encoding="utf-8")
            with contextlib.redirect_stderr(io.StringIO()) as diagnostics:
                ci.print_failed_command_log(log)
        output = diagnostics.getvalue()
        self.assertNotIn("WP501_", output)
        self.assertNotIn("PRIVATE_PAYLOAD", output)
        self.assertEqual(output.count("identity extraction is incomplete"), 1)
        self.assertTrue(output.rstrip().endswith("last actual progress"))

    def test_observation_replay_is_bounded_and_reports_truncation_in_original_order(self):
        markers = sorted(ci.WP501_OBSERVATION_MARKERS)[:ci.WP501_OBSERVATION_LIMIT + 1]
        self.assertEqual(len(markers), ci.WP501_OBSERVATION_LIMIT + 1)
        content = "\n".join(markers) + "\n" + ("later unrelated output\n" * 2000) + "last actual progress\n"
        with tempfile.TemporaryDirectory() as temporary:
            log = Path(temporary) / "bounded-observations.log"
            log.write_text(content, encoding="utf-8")
            with contextlib.redirect_stderr(io.StringIO()) as diagnostics:
                ci.print_failed_command_log(log)
        output = diagnostics.getvalue()
        self.assertNotIn(markers[0], output)
        observed = output.splitlines()
        self.assertEqual(observed[1:ci.WP501_OBSERVATION_LIMIT + 1], markers[1:])
        self.assertIn("retained=128|seen=129|truncated=True|not-test-evidence", output)
        self.assertTrue(output.rstrip().endswith("last actual progress"))

    def test_observation_whitelist_matches_app_rollback_names_and_existing_phase_protocol(self):
        source = (ci.REPO / "android" / "app" / "build.gradle.kts").read_text(encoding="utf-8")
        rollback = source.split("val rollbackTestNames = setOf(", 1)[1].split(").flatMap", 1)[0]
        names = tuple(line.strip().strip('",') for line in rollback.splitlines() if line.strip())
        key = ("WP501_ADVERT_ROLLBACK", "com.meshcoreone.android.parity.AdvertisementRollbackOriginalPredicateTest")
        self.assertEqual(names, ci.WP501_OBSERVATION_METHODS[key])
        phases = source.split("val pathRecencyPhases = setOf(", 1)[1].split(")", 1)[0]
        self.assertEqual(tuple(ci.re.findall(r'"([^"]+)"', phases)), ci.WP501_PATH_RECENCY_PHASES)
        for method in names:
            for suffix in ("", "()", "[31]", "[31]()"):
                for ending in ("START", "END|SUCCESS", "END|FAILURE", "END|SKIPPED"):
                    self.assertIn(f"{key[0]}|TEST|{key[1]}|{method}{suffix}|{ending}", ci.WP501_OBSERVATION_MARKERS)

    def test_overlong_failure_log_lines_report_incomplete_and_keep_later_identities(self):
        identity = "com.meshcoreone.android.FixtureTest > laterCase[37] FAILED"
        content = "x" * 140000 + "\n" + identity + "\nBUILD FAILED\n"
        with tempfile.TemporaryDirectory() as temporary:
            log = Path(temporary) / "overlong.log"
            log.write_text(content, encoding="utf-8")
            with contextlib.redirect_stderr(io.StringIO()) as diagnostics:
                ci.print_failed_command_log(log)
        output = diagnostics.getvalue()
        self.assertEqual(output.count("identity extraction is incomplete"), 1)
        self.assertIn(identity, output)
        self.assertTrue(output.rstrip().endswith("BUILD FAILED"))

    def test_failed_command_retains_every_gradle_identity_before_the_bounded_tail(self):
        identities = [
            f"com.meshcoreone.android.FixtureTest > originalCase{index}[{sdk}] FAILED"
            for index, sdk in enumerate((31, 37, 31, 37))
        ]
        content = "\n".join(
            f"bounded preceding context\n{identity}\n    java.lang.AssertionError: original assertion\n"
            for identity in identities
        ) + "\n800 tests completed, 4 failed\n" + ("later diagnostic output\n" * 2000) + "BUILD FAILED\n"
        with tempfile.TemporaryDirectory() as temporary:
            log = Path(temporary) / "gradle-failure.log"
            def failed(command, **kwargs):
                kwargs["stdout"].write(content)
                return ci.subprocess.CompletedProcess(command, 17)
            with patch.object(ci.subprocess, "run", side_effect=failed), \
                    patch.object(Path, "read_text", side_effect=AssertionError("Whole-log reads are forbidden")), \
                    contextlib.redirect_stdout(io.StringIO()), \
                    contextlib.redirect_stderr(io.StringIO()) as diagnostics:
                with self.assertRaisesRegex(PortError, "Declared command failed with exit 17"):
                    ci.execute(["fixture-gradle"], {}, log)
            output = diagnostics.getvalue()
            for identity in identities:
                self.assertNotIn(identity, content[-12000:])
                self.assertIn(identity, output)
            self.assertIn("800 tests completed, 4 failed", output)
            self.assertIn("java.lang.AssertionError: original assertion", output)
            self.assertTrue(output.rstrip().endswith("BUILD FAILED"))

    def test_successful_command_does_not_emit_failure_diagnostics(self):
        with tempfile.TemporaryDirectory() as temporary, \
                patch.object(ci.subprocess, "run", return_value=ci.subprocess.CompletedProcess(["fixture"], 0)), \
                patch.object(ci, "print_failed_command_log") as diagnostics, \
                contextlib.redirect_stdout(io.StringIO()):
            ci.execute(["fixture"], {}, Path(temporary) / "success.log")
            diagnostics.assert_not_called()

    def test_timeout_closes_log_and_emits_failure_identities_reports_and_bounded_tail(self):
        identity = "com.meshcoreone.android.FixtureTest > timedOutCase[31] FAILED"
        content = identity + "\n    original failure\n" + ("later progress\n" * 2000) + "last Gradle progress\n"
        failure = ci.subprocess.TimeoutExpired(["fixture-gradle"], 1800)
        with tempfile.TemporaryDirectory() as temporary:
            log = Path(temporary) / "gradle-timeout.log"
            report = Path(temporary) / "junit"
            report.mkdir()
            (report / "TEST-Fixture.xml").write_text(
                '<testsuite tests="1" failures="1" errors="0" skipped="0">'
                '<testcase classname="Fixture" name="timedOutCase"><failure message="original"/>'
                '</testcase></testsuite>', encoding="utf-8",
            )
            handles = []
            def timeout(command, **kwargs):
                self.assertEqual(kwargs["timeout"], 1800)
                handles.append(kwargs["stdout"])
                kwargs["stdout"].write(content)
                raise failure
            def reports():
                self.assertTrue(handles[0].closed)
                return [report]
            with patch.object(ci.subprocess, "run", side_effect=timeout), \
                    patch.object(Path, "read_text", side_effect=AssertionError("Whole-log reads forbidden")), \
                    contextlib.redirect_stdout(io.StringIO()), \
                    contextlib.redirect_stderr(io.StringIO()) as diagnostics:
                with self.assertRaises(ci.subprocess.TimeoutExpired) as observed:
                    ci.execute(["fixture-gradle"], {}, log, failure_reports=reports)
            self.assertIs(observed.exception, failure)
            self.assertTrue(handles[0].closed)
            output = diagnostics.getvalue()
            self.assertNotIn(identity, content[-12000:])
            self.assertIn(identity, output)
            self.assertIn("failed_command_report_totals", output)
            self.assertIn("timedOutCase", output)
            self.assertLess(output.index(identity), output.index("last Gradle progress"))
            self.assertTrue(output.rstrip().endswith("last Gradle progress"))
            self.assertLess(len(output), 16000)

    def test_timeout_is_not_replaced_by_failure_diagnostic_errors(self):
        for target, error in (
            ("reports", PortError("partial report inventory")),
            ("log", OSError("log unavailable")),
            ("log", RuntimeError("unexpected diagnostic failure")),
        ):
            with self.subTest(target=target, error=type(error).__name__), \
                    tempfile.TemporaryDirectory() as temporary:
                failure = ci.subprocess.TimeoutExpired(["fixture"], 1800)
                def timeout(command, **kwargs):
                    kwargs["stdout"].write("retained timeout progress\n")
                    raise failure
                with patch.object(ci.subprocess, "run", side_effect=timeout), \
                        patch.object(ci, "print_failed_command_log",
                                     side_effect=error if target == "log" else None), \
                        contextlib.redirect_stdout(io.StringIO()), \
                        contextlib.redirect_stderr(io.StringIO()) as diagnostics:
                    def reports():
                        raise error
                    with self.assertRaises(ci.subprocess.TimeoutExpired) as observed:
                        ci.execute(["fixture"], {}, Path(temporary) / "timeout.log",
                                   failure_reports=reports if target == "reports" else None)
                self.assertIs(observed.exception, failure)
                self.assertIn(str(error), diagnostics.getvalue())

    def test_timeout_is_not_replaced_by_broken_stderr_during_diagnostics(self):
        class BrokenDiagnostics(io.StringIO):
            def write(self, value):
                raise OSError("diagnostic output unavailable")

        failure = ci.subprocess.TimeoutExpired(["fixture"], 1800)
        handles = []
        def timeout(command, **kwargs):
            handles.append(kwargs["stdout"])
            kwargs["stdout"].write("actual timeout progress\n")
            raise failure
        with tempfile.TemporaryDirectory() as temporary, \
                patch.object(ci.subprocess, "run", side_effect=timeout), \
                contextlib.redirect_stdout(io.StringIO()), \
                contextlib.redirect_stderr(BrokenDiagnostics()):
            log = Path(temporary) / "timeout.log"
            with self.assertRaises(ci.subprocess.TimeoutExpired) as observed:
                ci.execute(["fixture"], {}, log)
            self.assertIs(observed.exception, failure)
            self.assertTrue(handles[0].closed)
            self.assertEqual(log.read_text(), "actual timeout progress\n")

    def test_shared_candidate_executor_change_requires_broad_verification(self):
        self.assertTrue(all(classify(["tools/android-port/controller/ci.py"]).values()))

    @contextlib.contextmanager
    def candidate(self, headers, *, tests=84):
        with tempfile.TemporaryDirectory(prefix="meshcore-source-ci-") as temporary:
            root = Path(temporary)
            source = root / SOURCE
            source.parent.mkdir(parents=True)
            source.write_text(headers + "package fixture\n", encoding="utf-8")
            reports = root / "android/core/protocol/build/test-results/test"
            reports.mkdir(parents=True)
            cases = "".join(f'<testcase classname="Fixture" name="case{index}"/>' for index in range(tests))
            (reports / "TEST-Fixture.xml").write_text(
                f'<testsuite tests="{tests}" failures="0" errors="0" skipped="0">{cases}</testsuite>',
                encoding="utf-8",
            )
            manifest = replace(base_manifest(), repo=root)
            output = root / "output"

            def execute(command, environment, log, **kwargs):
                if log.name.startswith("gradle-"):
                    graph = Path(environment["ANDROID_CI_TASK_GRAPH"])
                    graph.parent.mkdir(parents=True, exist_ok=True)
                    graph.write_text(json.dumps({
                        "schema_version": 1,
                        "tasks": [{"path": ":core:protocol:test", "type": "fixture", "command": []}],
                    }), encoding="utf-8")

            with (
                patch.object(ci, "REPO", root),
                patch.object(ci, "load_manifest", return_value=manifest),
                patch.object(ci.platform, "python_version", return_value="3.12.4"),
                patch.object(ci, "candidate_environment", return_value={}),
                patch.object(ci, "verify_wrapper"),
                patch.object(ci, "execute", side_effect=execute) as commands,
                patch.object(ci, "collect_suites", side_effect=AssertionError("Unselected scaffold suites")),
                patch.object(ci, "check_module_tests", side_effect=AssertionError("Unselected Android module tests")),
                patch.object(ci, "collect_lint", side_effect=AssertionError("Unselected scaffold lint")),
                contextlib.redirect_stdout(io.StringIO()) as printed,
            ):
                yield commands, printed, output, {"host": "linux", "private_root": str(root / "private")}

    def protocol_selection(self):
        selection = classify([SOURCE])
        self.assertEqual([name for name, selected in selection.items() if selected], ["protocol"])
        return [name for name, selected in selection.items() if selected]

    def test_protocol_only_actual_mixed_declarations_fail_before_any_build_command(self):
        # Actual declarations from rejected 6bb88ab; the validator itself is never mocked.
        for stage in ("scaffold", "protocol"):
            with self.subTest(stage=stage), self.candidate(MIXED) as (commands, printed, output, state):
                with self.assertRaisesRegex(PortError, "Conflicting traceability dispositions"):
                    ci.run_stage(stage, state, output, scopes=self.protocol_selection())
                commands.assert_not_called()
                self.assertNotIn('"result": "success"', printed.getvalue())

    def test_corrected_multi_source_candidate_keeps_protocol_only_gradle_and_discovery(self):
        with self.candidate(PORTED) as (commands, printed, output, state):
            ci.run_stage("scaffold", state, output, scopes=self.protocol_selection())
            self.assertEqual(commands.call_count, 2)
            gradle = commands.call_args_list[1].args[0]
            self.assertEqual(gradle[3], ":core:protocol:test")
            self.assertNotIn("verifyScaffoldTests", gradle)
            self.assertNotIn(":app:assembleDebug", gradle)
            self.assertNotIn(":app:compileDebugAndroidTestKotlin", gradle)
            self.assertIn("--dependency-verification", gradle)
            summary, report = printed.getvalue().split("\n", 1)
            self.assertEqual(summary, "Verified Kotlin source dispositions and pinned provenance: 1 files")
            result = json.loads(report)
            self.assertEqual(result["scopes"], ["protocol"])
            self.assertEqual(result["protocol_suite"]["discovered"], 84)
            self.assertEqual(result["protocol_suite"]["passed"], 84)
            self.assertEqual(len(result["unit_test_methods"]["core/protocol"]), 84)
            self.assertIn({"class": "Fixture", "method": "case0"},
                          result["unit_test_methods"]["core/protocol"])

    def test_scaffold_preflight_failure_does_not_launch_expensive_gradle_tasks(self):
        with self.candidate(PORTED) as (commands, printed, output, state):
            original_execute = commands.side_effect

            def fail_preflight(command, environment, log, **kwargs):
                if log.name == "gradle-scaffold-preflight.log":
                    raise PortError("graph preflight failed")
                return original_execute(command, environment, log, **kwargs)

            commands.side_effect = fail_preflight
            with self.assertRaisesRegex(PortError, "graph preflight failed"):
                ci.run_stage("scaffold", state, output)
            self.assertEqual(commands.call_count, 2)
            command, _, log = commands.call_args_list[-1].args
            self.assertEqual(log.name, "gradle-scaffold-preflight.log")
            tasks = []
            for argument in command[3:]:
                if argument.startswith("-"):
                    break
                tasks.append(argument)
            self.assertEqual(
                tasks,
                ["resolveScaffoldDependencies", "validateModuleGraph", "runtimeDependencyInventory"],
            )
            self.assertNotIn(":app:assembleDebug", command)
            self.assertIn("--parallel", command)
            self.assertIn("--max-workers=8", command)
            self.assertNotIn("--max-workers=1", command)
            self.assertNotIn('"result": "success"', printed.getvalue())

    def test_affected_candidate_preserves_module_closure_and_does_not_promote_scaffold_scope(self):
        cases = (
            (
                ["protocol"],
                {"scopes": ["protocol", "module:core/protocol"], "full_scaffold": False},
                {"full_scaffold": "false", "localization": "false"},
            ),
            (
                ["scaffold"],
                {"scopes": ["module:core/l10n"], "full_scaffold": False},
                {"full_scaffold": "false", "localization": "true"},
            ),
            (
                ["scaffold", "protocol", "backup"],
                {"scopes": ["scaffold", "protocol", "backup"], "full_scaffold": True},
                {"full_scaffold": "true", "localization": "true"},
            ),
        )
        for event_name in ("pull_request", "push", "merge_group"):
            for scopes, plan, expected_outputs in cases:
                with self.subTest(event=event_name, scopes=scopes), \
                        self.candidate(PORTED) as (commands, printed, output, state):
                    base, head = "1" * 40, "2" * 40
                    identity = {"binding": {"base_sha": base, "head_sha": head}}
                    output.mkdir()
                    output_file = output / "github-output"
                    with (
                        patch.object(ci, "execution_identity", return_value=identity),
                        patch("controller.local_scope.candidate_plan", return_value=plan) as planner,
                        patch("controller.local_scope.run_scaffold", return_value={"result": "success"}) as run,
                        patch.dict(os.environ, {
                            "GITHUB_OUTPUT": str(output_file), "GITHUB_EVENT_NAME": event_name,
                        }),
                    ):
                        ci.run_stage("scaffold", state, output, scopes=scopes, affected_candidate=True)
                    self.assertEqual(run.call_args.args[1], plan)
                    self.assertEqual(run.call_args.args[5].count("--stacktrace"), 1)
                    self.assertIn("--parallel", run.call_args.args[5])
                    self.assertIn("--max-workers=8", run.call_args.args[5])
                    build_options = run.call_args.kwargs["build_options"]
                    self.assertIn("--max-workers=1", build_options)
                    self.assertNotIn("--max-workers=8", build_options)
                    self.assertNotIn("--parallel", build_options)
                    self.assertEqual(
                        [option for option in run.call_args.args[5]
                         if option != "--parallel" and option != "--max-workers=8"],
                        [option for option in build_options if option != "--max-workers=1"],
                    )
                    self.assertEqual(planner.call_args.kwargs["merge_base_only"], event_name == "pull_request")
                    self.assertEqual(run.call_args.kwargs["merge_base_only"], event_name == "pull_request")
                    self.assertEqual(planner.call_args.args[3] if len(planner.call_args.args) > 3 else None,
                                     None if scopes == ["scaffold"] else list(dict.fromkeys([
                                         *plan["scopes"],
                                         *(scope for scope in scopes if scope in ("protocol", "backup")),
                                     ])))
                    self.assertEqual(
                        dict(line.split("=", 1) for line in output_file.read_text().splitlines()),
                        expected_outputs,
                    )
                    self.assertNotIn('"result": "failure"', printed.getvalue())

    def test_selected_gradle_batch_budget_reaches_executor_and_preserves_timeout_diagnostics(self):
        real_execute = ci.execute
        for outcome in ("success", "timeout"):
            with self.subTest(outcome=outcome), self.candidate(PORTED) as (commands, printed, output, state):
                graph_fixture = commands.side_effect
                failure = ci.subprocess.TimeoutExpired(["fixture-gradle"], 3600)

                def execute(command, environment, log, **kwargs):
                    graph_fixture(command, environment, log, **kwargs)
                    return real_execute(command, environment, log, **kwargs)

                class FixtureProcess:
                    def __init__(self):
                        self.stdout = io.StringIO("actual selected batch progress\n")
                        self.pid = 12345
                        self.wait_count = 0
                        self.killed = False

                    def poll(self):
                        return -9 if self.killed else None

                    def kill(self):
                        self.killed = True

                    def wait(self, timeout=None):
                        if timeout is not None:
                            self.assert_timeout(timeout)
                            if outcome == "timeout":
                                self.wait_count += 1
                                raise failure
                        self.wait_count += 1
                        return 0

                    @staticmethod
                    def assert_timeout(timeout):
                        if timeout != 3600:
                            raise AssertionError(f"Unexpected Gradle timeout: {timeout}")

                process = FixtureProcess()

                def run(command, **kwargs):
                    self.assertEqual(kwargs["timeout"], 60)
                    return ci.subprocess.CompletedProcess(command, 0)

                commands.side_effect = execute
                with patch.object(ci.subprocess, "run", side_effect=run), \
                        patch.object(ci.subprocess, "Popen", return_value=process), \
                        patch.object(ci.os, "killpg", create=True) as kill_group, \
                        contextlib.redirect_stderr(io.StringIO()) as diagnostics:
                    if outcome == "timeout":
                        with self.assertRaises(ci.subprocess.TimeoutExpired) as raised:
                            ci.run_stage("scaffold", state, output, scopes=self.protocol_selection())
                        self.assertIs(raised.exception, failure)
                        self.assertIn("actual selected batch progress", diagnostics.getvalue())
                        self.assertIn('"failed_command_report_totals"', diagnostics.getvalue())
                        self.assertIn('"discovered": 84', diagnostics.getvalue())
                        self.assertNotIn('"result": "success"', printed.getvalue())
                    else:
                        ci.run_stage("scaffold", state, output, scopes=self.protocol_selection())
                        self.assertIn('"passed": 84', printed.getvalue())
                if outcome == "timeout":
                    if ci.os.name != "nt":
                        kill_group.assert_called_once_with(process.pid, ci.signal.SIGKILL)
                    else:
                        self.assertTrue(process.killed)
                self.assertEqual((output / "gradle-scaffold.log").read_text(), "actual selected batch progress\n")

    def test_streamed_gradle_keyboard_interrupt_kills_process_tree_and_reaps_reader(self):
        class FixtureProcess:
            def __init__(self):
                self.stdout = io.StringIO("interrupted progress\n")
                self.pid = 12345
                self.wait_count = 0
                self.killed = False

            def poll(self):
                return -9 if self.killed else None

            def kill(self):
                self.killed = True

            def wait(self, timeout=None):
                self.wait_count += 1
                if timeout is not None:
                    raise KeyboardInterrupt
                return -9

        process = FixtureProcess()

        def kill_group(pid, sig):
            self.assertEqual((pid, sig), (process.pid, ci.signal.SIGKILL))
            process.killed = True

        with tempfile.TemporaryDirectory() as temporary:
            log = Path(temporary) / "gradle.log"
            with patch.object(ci.subprocess, "Popen", return_value=process), \
                    patch.object(ci.os, "killpg", side_effect=kill_group, create=True) as kill, \
                    contextlib.redirect_stdout(io.StringIO()) as output:
                with self.assertRaises(KeyboardInterrupt):
                    ci.execute(["fixture-gradle"], {}, log, timeout=3600, stream_output=True)

            if ci.os.name != "nt":
                kill.assert_called_once_with(process.pid, ci.signal.SIGKILL)
            else:
                self.assertTrue(process.killed)
            self.assertEqual(process.wait_count, 2)
            self.assertTrue(process.stdout.closed)
            self.assertIn("interrupted progress", output.getvalue())
            self.assertEqual(log.read_text(), "interrupted progress\n")

    def test_streamed_gradle_output_is_written_live_and_retained_in_the_command_log(self):
        class FixtureProcess:
            stdout = io.StringIO("configuration complete\n:core:protocol:test\n")

            @staticmethod
            def wait(timeout=None):
                return 0

        with tempfile.TemporaryDirectory() as temporary:
            log = Path(temporary) / "gradle.log"
            with patch.object(ci.subprocess, "Popen", return_value=FixtureProcess()), \
                    contextlib.redirect_stdout(io.StringIO()) as output:
                ci.execute(
                    ["fixture-gradle"], {}, log, timeout=3600, stream_output=True,
                )
            self.assertIn("configuration complete", output.getvalue())
            self.assertIn(":core:protocol:test", output.getvalue())
            self.assertEqual(log.read_text(), "configuration complete\n:core:protocol:test\n")

    def test_extended_batch_budget_keeps_task_graph_guard_and_benchmark_budget(self):
        for stage in ("scaffold", "benchmark"):
            with self.subTest(stage=stage), self.candidate(PORTED) as (commands, _, output, state):
                with patch.object(ci, "validate_candidate_task_graph", side_effect=PortError("invalid actual graph")) as graph:
                    with self.assertRaisesRegex(PortError, "invalid actual graph"):
                        ci.run_stage(stage, state, output, scopes=self.protocol_selection())
                self.assertEqual(commands.call_args.kwargs["timeout"], 3600 if stage == "scaffold" else 1800)
                self.assertIn("--init-script", commands.call_args.args[0])
                graph.assert_called_once()

    def test_pinned_provenance_is_enforced_for_protocol_only_scope(self):
        for headers in (PORTED.replace(PIN, "0" * 40), PORTED.replace("PacketParser.swift", "Unknown.swift")):
            with self.subTest(headers=headers), self.candidate(headers) as (commands, _, output, state):
                with self.assertRaisesRegex(PortError, "Unknown/stale source provenance"):
                    ci.run_stage("scaffold", state, output, scopes=self.protocol_selection())
                commands.assert_not_called()

    def test_missing_disposition_is_rejected_before_scoped_execution(self):
        with self.candidate("") as (commands, _, output, state):
            with self.assertRaisesRegex(PortError, "Missing traceability header"):
                ci.run_stage("scaffold", state, output, scopes=self.protocol_selection())
            commands.assert_not_called()

    def test_valid_disposition_does_not_allow_zero_protocol_assertions(self):
        with self.candidate(PORTED, tests=0) as (_, printed, output, state):
            with self.assertRaisesRegex(PortError, "Zero, failed, skipped or inconsistent JUnit suite"):
                ci.run_stage("scaffold", state, output, scopes=self.protocol_selection())
            self.assertNotIn('"result": "success"', printed.getvalue())

    def test_prepare_also_cannot_bypass_the_source_guard(self):
        with self.candidate(MIXED) as (commands, _, output, state):
            with self.assertRaisesRegex(PortError, "Conflicting traceability dispositions"):
                ci.run_stage("prepare", state, output)
            commands.assert_not_called()


if __name__ == "__main__":
    unittest.main()
