"""AndroidOnly: WP-003 Same CI checks, warm Gradle execution, local-only reports."""

import argparse
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import time


def scaffold_options(advisory=False):
    options = [
        "--console=plain", "--dependency-verification", "strict", "--build-cache",
        "--no-parallel", "--max-workers=4", "-PscaffoldTestHeap=512m",
        "--no-daemon", "-Pkotlin.compiler.execution.strategy=in-process",
    ]
    return options


def local_test_command(repo):
    return [
        sys.executable, "-m", "unittest", "discover",
        "-s", str(repo / "tools/android-port/local"), "-p", "*test*.py", "-v",
    ]


def owned_lanes():
    spec = importlib.util.spec_from_file_location(
        "controller._local_owned_jvm_lanes", Path(__file__).with_name("jvm_lanes.py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def owned_scope():
    spec = importlib.util.spec_from_file_location(
        "controller._local_owned_scope", Path(__file__).with_name("local_scope.py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def owned_verification(repo):
    source = Path(__file__).resolve().parent
    if source == (repo / "tools/android-port/local").resolve():
        return repo / "tools/android-port/controller/verify_candidate_task_graph.gradle"
    import controller
    from controller.errors import PortError

    for name in ("ci_evidence", "publication", "repair_acceptance", "module_junit"):
        path = source / (name + ".py")
        if path.is_symlink() or not path.is_file():
            raise PortError("Missing or unsafe owned verification dependency: " + name)
        spec = importlib.util.spec_from_file_location("controller." + name, path)
        module = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = module
        spec.loader.exec_module(module)
        setattr(controller, name, module)
    script = source / "verify_candidate_task_graph.gradle"
    if script.is_symlink() or not script.is_file():
        raise PortError("Missing or unsafe owned compiler-source collector")
    return script


def execution_lane(work):
    descriptor = Path("/proc/self/fd/8")
    if not descriptor.is_file():
        return None
    admission = json.loads(descriptor.read_text(encoding="utf-8"))
    return owned_lanes().lane_directory(work, admission["jvm_lane"]) if "jvm_lane" in admission else None


def affected_request(repo, path, base, stages, advisory):
    from controller.errors import PortError

    if (path is None) != (base is None) or path is not None and advisory:
        raise PortError("Affected scope requires the exact manual owned request")
    if path is None:
        return None
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 8 * 1024 * 1024:
        raise PortError("Missing/unsafe/oversized affected request")
    admission = json.loads(Path("/proc/self/fd/8").read_text(encoding="utf-8"))
    plan = json.loads(path.read_text(encoding="utf-8"))
    plan = owned_scope().validate_plan(repo, plan, trusted_base=base)
    if (
        not isinstance(admission, dict)
        or set(admission) != {"schema_version", "interlock_version", "owner", "token", "commit", "tree"}
        | ({"jvm_lane"} if "jvm_lane" in admission else set())
        or type(admission["schema_version"]) is not int or admission["schema_version"] != 1
        or type(admission["interlock_version"]) is not int or admission["interlock_version"] != 2
        or not isinstance(admission["token"], str) or not admission["token"]
        or admission["owner"] != "local-check:" + admission["token"]
        or admission.get("commit") != plan["head"] or admission.get("tree") != plan["tree"]
        or stages != plan["stages"]
    ):
        raise PortError("Affected plan differs from the actual owned admission or required stages")
    if "jvm_lane" in admission:
        owned_lanes().validate_binding(admission["jvm_lane"])
    return plan


def execute(command, repo, environment, log):
    print("Executing: " + " ".join(map(str, command)), flush=True)
    with log.open("w", encoding="utf-8") as stream:
        with subprocess.Popen(command, cwd=repo, env=environment, stdout=subprocess.PIPE,
                              stderr=subprocess.STDOUT, text=True, errors="replace") as process:
            for line in process.stdout:
                print(line, end="", flush=True)
                stream.write(line)
            status = process.wait()
    if status:
        raise subprocess.CalledProcessError(status, command)


def notification_test_sources(repo, path):
    from controller.ci_evidence import read_test_sources
    from controller.module_junit import validate_remapped_compilation

    sources = read_test_sources(path, {"platform/notifications": "testDebugUnitTest"})
    validate_remapped_compilation(repo, sources, ["platform/notifications"])
    return sources


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", required=True, type=Path)
    parser.add_argument("--work", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--stages", required=True)
    parser.add_argument("--advisory", action="store_true")
    parser.add_argument("--affected-plan", type=Path)
    parser.add_argument("--affected-base")
    args = parser.parse_args()
    sys.path.insert(0, str(args.repo / "tools" / "android-port"))
    task_graph_script = owned_verification(args.repo)
    from controller import ci
    from controller.ci_environment import candidate_environment, verify_wrapper, write_json
    from controller.ci_evidence import (
        LINT_TARGETS, SUITES, bind_test_lineage, collect_lint, collect_suites,
        prepare_test_lineage, prepare_test_sources, read_test_lineage,
        read_test_sources, validate_graph_runtime,
    )
    from controller.errors import PortError
    from controller.module_junit import (
        REPORT_LOCATIONS, bounded_directory, check_module_tests,
        module_method_inventory, module_sources, remapped_tasks, validate_remapped_compilation,
    )

    stages = args.stages.split(",")
    plan = affected_request(args.repo, args.affected_plan, args.affected_base, stages, args.advisory)
    lane = execution_lane(args.work)
    if lane is not None and (plan is None or args.advisory):
        raise ValueError("Parallel JVM execution requires its exact manual affected plan")
    state = json.loads((args.work / "toolchain" / "environment.json").read_text(encoding="utf-8"))
    # Keep environment/credential stripping identical to CI, but retain local caches.
    state["private_root"] = str((lane or args.work / "local-checks") / "private")
    environment = candidate_environment(state)
    environment["PATH"] = os.pathsep.join([str(Path(state["java_home"]) / "bin"),
                                          str(Path(sys.executable).parent), environment.get("PATH", "")])
    environment["GRADLE_USER_HOME"] = str((lane or args.work) / ".gradle-fast")
    environment["GRADLE_OPTS"] = '-Dorg.gradle.jvmargs="-Xmx3g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8"'
    # Explicitly serial project execution: cross-project graph resolution is not
    # compatible with parallel-project execution. Gradle can still use four workers.
    verify_wrapper()
    started = time.monotonic()
    for stage in stages:
        print(f"\n===== FAST LOCAL: {stage} =====", flush=True)
        if stage == "python":
            ci.python_checks(args.output)
            command = local_test_command(args.repo) if plan is None else [
                sys.executable, str(args.repo / "tools/android-port/controller/test_runner.py"),
                "--tests", str(args.repo / "tools/android-port/local"), "--quiet",
            ]
            execute(command, args.repo, environment, args.output / "local-tool-tests.log")
        elif stage == "preflight":
            ci.preflight(state, args.output)
        elif stage == "scaffold":
            options = scaffold_options(args.advisory)
            if plan is not None:
                report = owned_scope().run_scaffold(
                    args.repo, plan, environment, args.output, execute, options,
                    trusted_base=args.affected_base, task_graph_script=task_graph_script)
                print(json.dumps(report, indent=2), flush=True)
                print(f"===== PASS: {stage} =====", flush=True)
                continue
            project = args.repo / "android"
            tasks = list(dict.fromkeys([*ci.TASKS[stage], *remapped_tasks(args.repo)]))
            directories = {project / relative for relative, _ in SUITES.values()}
            for module in module_sources(args.repo):
                directories.update(project / module / relative for relative in REPORT_LOCATIONS)
            reports = [
                project / module / "build/reports/lint-results-debug.xml" for module in LINT_TARGETS
            ] + [
                project / "build/reports/scaffold" / name
                for name in ("module-graph.tsv", "runtime-dependencies.tsv")
            ]
            for directory in directories:
                bounded_directory(directory, args.repo)
                reports.extend(directory.glob("TEST-*.xml"))
            for path in reports:
                bounded_directory(path.parent, args.repo)
                if path.is_symlink():
                    raise PortError("Linked previous report cannot become current evidence")
                path.unlink(missing_ok=True)
            lineage_path = prepare_test_lineage(args.output)
            sources_path = prepare_test_sources(args.output)
            graph_path = args.output / "gradle-scaffold-task-graph.json"
            if graph_path.is_symlink():
                raise PortError("Linked resolved task graph")
            graph_path.unlink(missing_ok=True)
            task_environment = {
                **environment, "ANDROID_CI_TEST_LINEAGE": str(lineage_path),
                "ANDROID_CI_TEST_SOURCES": str(sources_path),
                "ANDROID_CI_TASK_GRAPH": str(graph_path),
            }
            execute([str(args.repo / "android" / "gradlew"), "-p", str(project),
                     *tasks, *options, "--init-script", str(task_graph_script)],
                    args.repo, task_environment, args.output / f"gradle-{stage}.log")
            report = {"stage": stage, "result": "success", "tasks": tasks, "warm_cache": True}
            report["resolved_task_graph"] = ci.validate_candidate_task_graph(graph_path, tasks)
            report["suites"] = collect_suites(args.repo / "android")
            notification_sources = notification_test_sources(args.repo, sources_path)
            report["module_unit_tests"] = check_module_tests(args.repo, notification_sources)
            inventory = module_method_inventory(args.repo, report["module_unit_tests"])
            runners = {}
            for module in inventory:
                locations = [
                    relative for relative in REPORT_LOCATIONS
                    if any((project / module / relative).glob("TEST-*.xml"))
                ]
                if len(locations) != 1:
                    raise PortError("Missing or ambiguous selected JUnit runner: " + module)
                runners[module] = Path(locations[0]).name
            bind_test_lineage(inventory, read_test_lineage(lineage_path), runners)
            report["unit_test_methods"] = inventory
            report["compiled_test_sources"] = read_test_sources(sources_path, runners)
            report["remapped_jvm_inputs"] = validate_remapped_compilation(
                args.repo, report["compiled_test_sources"], runners)
            report["reports"] = collect_lint(args.repo / "android")
            validate_graph_runtime(args.repo / "android/build/reports/scaffold")
            execute([sys.executable, str(args.repo / "tools/android-port/l10n_convert.py"), "--check",
                     "--verify-android-tests", str(args.repo / "android/core/l10n/build/test-results/testDebugUnitTest"),
                     "--copy-android-junit", str(args.output / "l10n/junit")],
                    args.repo, environment, args.output / "l10n-junit.log")
            print(json.dumps(report, indent=2), flush=True)
        elif stage == "inspect":
            ci.inspect(state, args.output)
        elif stage in ("benchmark", "benchmark-dependencies"):
            from controller.performance import verify_build
            options = scaffold_options(args.advisory)
            if stage == "benchmark-dependencies":
                options += ["--write-locks", "--write-verification-metadata", "sha256"]
            execute([str(args.repo / "android" / "gradlew"), "-p", str(args.repo / "android"),
                     *ci.TASKS["benchmark"], *options], args.repo, environment, args.output / f"gradle-{stage}.log")
            if stage == "benchmark":
                print(json.dumps(verify_build(args.repo, Path(state["android_home"]), environment), indent=2))
        else:
            raise ValueError("Unknown local stage: " + stage)
        print(f"===== PASS: {stage} =====", flush=True)
    binding = {
        "commit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=args.repo, text=True).strip(),
        "tree": subprocess.check_output(["git", "rev-parse", "HEAD^{tree}"], cwd=args.repo, text=True).strip(),
        "stages": stages, "elapsed_seconds": round(time.monotonic() - started, 2),
        "scope": "Local pre-push check; not hosted/cold-cache, hardware, signing or human-gate evidence",
    }
    if plan is not None:
        owned_scope().validate_plan(args.repo, plan, trusted_base=args.affected_base)
        binding.update(kind="local-affected-only", base=plan["base"], scopes=plan["scopes"], tasks=plan["tasks"])
    print(json.dumps(binding, indent=2), flush=True)
    print(
        "AFFECTED SNAPSHOT CHECKS COMPLETE. Current-base finalization remains with the owned launcher."
        if plan is not None else "FAST LOCAL CHECK PASSED. No second cold local run is required.",
        flush=True,
    )


if __name__ == "__main__":
    # Tracebacks and underlying command/log locations intentionally remain visible.
    main()
