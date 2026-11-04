"""AndroidOnly: WP-003 Optional trusted-base compiler experiment, not an acceptance bypass."""

import argparse
from dataclasses import asdict
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tarfile
import uuid

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from controller.errors import PortError
from controller.gates import Binding, policy_revision
from controller.model import REFERENCE_SHA, git, load_manifest, tree
from controller.schema import decode_json, digest, fields, load_json, positive_integer

WORKFLOW = ".github/workflows/android-compiler-verification.yml"
STEP = "Report independent compiler result"
GUARD = "tools/android-port/controller/independent_compiler.gradle"
TASKS = (":app:compileBenchmarkKotlin", ":app:compileProfileKotlin")
FIXTURES = (
    "android/benchmark/target/kotlin/com/meshcoreone/android/benchmarktarget/WorkloadActivity.kt",
    "android/benchmark/dataset/kotlin/com/meshcoreone/android/benchmark/Workload.kt",
)
SOURCE = re.compile(
    r"android/app/src/(?:main|benchmark|profile)/(?:kotlin|java)/.+\.kt$"
    r"|android/benchmark/(?:target|dataset)/kotlin/.+\.kt$"
)
TEST_DATA = re.compile(
    r"android/(?:app|(?:core|feature|platform)/[a-z0-9-]+)/src/"
    r"(?:test|testDebug|androidTest)/.+"
)
MAX_SOURCE = 4 * 1024 * 1024
RUNTIME_FILES = (
    "jdk/bin/java", "jdk/lib/modules", "jdk/lib/server/libjvm.so",
    "sdk/platforms/android-37.2/android.jar", "sdk/build-tools/37.0.0/aapt2",
    "host/bash", "host/libc", "host/loader",
)


def source_plan(repo, base, head):
    """Candidate executable/configuration bytes never enter the compiler tree."""
    before, after = tree(repo, base), tree(repo, head)
    selected = {path: blob for path, blob in after.items() if SOURCE.fullmatch(path)}
    for path in before.keys() | after.keys():
        if before.get(path) == after.get(path) or not path.startswith("android/"):
            continue
        if not SOURCE.fullmatch(path) and not TEST_DATA.fullmatch(path):
            raise PortError("Independent compiler source-only boundary refuses: " + path)
    if "android/app/build.gradle.kts" not in before:
        raise PortError("Independent compiler requires the trusted app module")
    if not set(FIXTURES) <= selected.keys():
        raise PortError("Independent compiler requires both actual consumer fixtures")
    return before, selected


def projection(repo, base, head):
    before, sources = source_plan(repo, base, head)
    entries = {path: blob for path, blob in before.items() if not SOURCE.fullmatch(path)}
    entries.update(sources)
    return entries, {
        "trusted_tree": git(repo, "rev-parse", base + "^{tree}").decode().strip(),
        "candidate_tree": git(repo, "rev-parse", head + "^{tree}").decode().strip(),
        "sources_sha256": digest(sources),
        "projection_sha256": digest(entries),
        "toolchain_pins_sha256": hashlib.sha256(
            git(repo, "show", base + ":tools/android-port/controller/toolchain-pins.json")
        ).hexdigest(),
    }


def materialize(repo, entries, destination, base):
    modes = {}
    for row in git(repo, "ls-tree", "-rz", base).split(b"\0"):
        if row:
            metadata, path = row.split(b"\t", 1)
            modes[path.decode()] = metadata.split()[0]
    destination.mkdir(mode=0o700)
    for path, blob in entries.items():
        target = destination / path
        target.parent.mkdir(parents=True, exist_ok=True)
        data = git(repo, "cat-file", "blob", blob)
        if SOURCE.fullmatch(path) and len(data) > MAX_SOURCE:
            raise PortError("Oversized compiler source: " + path)
        target.write_bytes(data)
        target.chmod(0o700 if modes.get(path) == b"100755" else 0o600)


def verify_projection(root, entries):
    for path, blob in entries.items():
        file = root / path
        if file.is_symlink() or not file.is_file():
            raise PortError("Compiler input missing or linked: " + path)
        data = file.read_bytes()
        if hashlib.sha1(f"blob {len(data)}\0".encode() + data).hexdigest() != blob:
            raise PortError("Compiler input changed: " + path)


def compiler_sources(entries):
    result = {task: [] for task in TASKS}
    for path in sorted(entries):
        if not SOURCE.fullmatch(path):
            continue
        tasks = TASKS
        if path.startswith("android/app/src/benchmark/"):
            tasks = TASKS[:1]
        elif path.startswith("android/app/src/profile/"):
            tasks = TASKS[1:]
        for task in tasks:
            result[task].append(path)
    return result


def source_paths_digest(paths):
    return hashlib.sha256("\n".join(paths).encode()).hexdigest()


def private_directory(path):
    if not path.is_absolute() or path.resolve() != path or path.exists():
        raise PortError("Independent compiler requires a fresh absolute unlinked output directory")
    path.mkdir(mode=0o700)


def toolchain_identity(jdk, sdk):
    files = (
        jdk / "bin/java", jdk / "lib/modules", jdk / "lib/server/libjvm.so",
        sdk / "platforms/android-37.2/android.jar", sdk / "build-tools/37.0.0/aapt2",
        Path("/bin/bash"), Path("/lib/x86_64-linux-gnu/libc.so.6"),
        Path("/lib64/ld-linux-x86-64.so.2"),
    )
    result = {}
    for label, file in zip(RUNTIME_FILES, files):
        if not file.is_file():
            raise PortError("Independent compiler runtime input missing: " + label)
        with file.open("rb") as stream:
            result[label] = hashlib.file_digest(stream, "sha256").hexdigest()
    return result


def trusted_checkout(repo, base):
    from controller.runtime_inputs import verify_committed_inputs

    if git(repo, "rev-parse", "HEAD").decode().strip() != base:
        raise PortError("Independent compiler reader requires the exact deployed current-base checkout")
    verify_committed_inputs(repo, base)


def docker_command(image, project, home, jdk, sdk, guard, sources, *, offline):
    # No host home, repository/.git, token, daemon socket or arbitrary environment is mounted.
    command = [
        "docker", "create", "--network", "none" if offline else "bridge",
        "--read-only", "--cap-drop=ALL", "--security-opt=no-new-privileges",
        "--pids-limit=512", "--memory=8g", "--cpus=2",
        "--user", f"{os.getuid()}:{os.getgid()}",
        "--tmpfs", "/tmp:rw,nosuid,nodev,size=2g,mode=1777",
        "--workdir=/project/android",
    ]
    for source, target, writable in (
        (project, "/project", True), (home, "/home/gradle", True),
        (jdk, "/jdk", False), (sdk, "/sdk", False), (guard, "/guard.gradle", False),
        (sources, "/compiler-sources.json", False),
        (Path("/usr"), "/usr", False), (Path("/bin"), "/bin", False),
        (Path("/lib"), "/lib", False), (Path("/lib64"), "/lib64", False),
        (Path("/etc/ssl/certs"), "/etc/ssl/certs", False),
    ):
        if not source.is_absolute() or "," in str(source) or "\n" in str(source):
            raise PortError("Unsafe independent compiler mount")
        command.extend(("--mount", f"type=bind,src={source},dst={target}" + ("" if writable else ",readonly")))
    for assignment in (
        "HOME=/home/gradle", "GRADLE_USER_HOME=/home/gradle", "JAVA_HOME=/jdk",
        "ANDROID_HOME=/sdk", "ANDROID_SDK_ROOT=/sdk", "PATH=/jdk/bin:/usr/bin:/bin",
        "LANG=C.UTF-8",
    ):
        command.extend(("--env", assignment))
    command.extend((
        image, "/bin/bash", "/project/android/gradlew", *TASKS,
        "--no-daemon", "--no-build-cache", "--no-configuration-cache", "--rerun-tasks",
        "--max-workers=1", "--console=plain", "--dependency-verification=strict",
        "-Pkotlin.compiler.execution.strategy=in-process", "--init-script", "/guard.gradle",
    ))
    if offline:
        command.append("--offline")
    return command


def checked(command, **kwargs):
    try:
        return subprocess.run(command, check=True, **kwargs)
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired, OSError) as error:
        raise PortError("Independent compiler command failed: " + command[0]) from error


def container_compile(command, log):
    result = checked(command, capture_output=True, text=True, timeout=60)
    identifier = result.stdout.strip()
    if re.fullmatch(r"[a-f0-9]{64}", identifier) is None:
        raise PortError("Docker did not return an exact owned container ID")
    try:
        with log.open("xb") as stream:
            checked(["docker", "start", "--attach", identifier], stdout=stream,
                    stderr=subprocess.STDOUT, timeout=3600)
        state = decode_json(checked(
            ["docker", "inspect", identifier], capture_output=True, text=True, timeout=60,
        ).stdout)[0]["State"]
        if state["Running"] or state["ExitCode"] != 0 or state["OOMKilled"]:
            raise PortError("Owned compiler container did not complete successfully")
    finally:
        # Container ID was obtained from this invocation, never an incumbent name or owner.
        checked(["docker", "rm", "--force", identifier], capture_output=True, timeout=60)


def observations(project, expected_sources):
    path = project / "compiler-observations.jsonl"
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 65536:
        raise PortError("Missing or unsafe independent compiler observations")
    values = [decode_json(line) for line in path.read_text(encoding="utf-8").splitlines()]
    if (
        len(values) != len(TASKS) or any(not isinstance(value, dict) for value in values)
        or {value.get("task") for value in values} != set(TASKS)
    ):
        raise PortError("Missing, duplicate or skipped independent compiler action")
    for value in values:
        fields(value, {"task", "classes", "output_sha256", "sources", "source_paths_sha256"},
               label="independent compiler action")
        positive_integer(value["classes"], "Independent class-file count")
        paths = expected_sources[value["task"]]
        if type(value["sources"]) is not int or value["sources"] != len(paths) or (
            value["source_paths_sha256"] != source_paths_digest(paths)
        ):
            raise PortError("Independent compiler source coverage differs")
        if not isinstance(value["output_sha256"], str) or re.fullmatch(r"[a-f0-9]{64}", value["output_sha256"]) is None:
            raise PortError("Missing independent compiler output binding")
    return sorted(values, key=lambda value: value["task"])


def execute(repo, binding, state, output, run_id, attempt):
    if (
        os.environ.get("GITHUB_REF") != "refs/heads/main"
        or os.environ.get("GITHUB_SHA") != binding.base_sha
        or git(repo, "rev-parse", "HEAD").decode().strip() != binding.base_sha
    ):
        raise PortError("Independent compiler must execute deployed default-branch code, not its candidate")
    from controller.ci import preflight

    trusted_checkout(repo, binding.base_sha)
    private_directory(output)
    preflight(state, output / "preflight")
    entries, inputs = projection(repo, binding.base_sha, binding.head_sha)
    image_tar = io.BytesIO()
    with tarfile.open(fileobj=image_tar, mode="w") as archive:
        data = b"FROM scratch\n"
        member = tarfile.TarInfo("Dockerfile")
        member.size = len(data)
        archive.addfile(member, io.BytesIO(data))
    image_id = output / "image-id"
    checked(["docker", "info"], capture_output=True, timeout=60)
    checked(["docker", "build", "--network=none", "--no-cache", "--iidfile", str(image_id), "-"],
            input=image_tar.getvalue(), capture_output=True, timeout=120)
    image = image_id.read_text().strip()
    if re.fullmatch(r"sha256:[a-f0-9]{64}", image) is None:
        raise PortError("Missing exact scratch execution-image identity")
    home = output / "gradle-home"
    home.mkdir(mode=0o700)
    guard = output / "guard.gradle"
    guard.write_bytes(git(repo, "show", binding.base_sha + ":" + GUARD))
    jdk, sdk = Path(state["java_home"]), Path(state["android_home"])
    runtime = toolchain_identity(jdk, sdk)
    # Resolve and exercise only trusted-base inputs before the offline candidate-source pass.
    baseline = output / "baseline"
    baseline_entries = tree(repo, binding.base_sha)
    materialize(repo, baseline_entries, baseline, binding.base_sha)
    baseline_sources = compiler_sources(baseline_entries)
    baseline_plan = output / "baseline-sources.json"
    baseline_plan.write_text(json.dumps(baseline_sources), encoding="utf-8")
    container_compile(docker_command(image, baseline, home, jdk, sdk, guard, baseline_plan, offline=False),
                      output / "baseline.log")
    observations(baseline, baseline_sources)
    project = output / "candidate-source"
    materialize(repo, entries, project, binding.base_sha)
    verify_projection(project, entries)
    candidate_sources = compiler_sources(entries)
    candidate_plan = output / "candidate-sources.json"
    candidate_plan.write_text(json.dumps(candidate_sources), encoding="utf-8")
    container_compile(docker_command(image, project, home, jdk, sdk, guard, candidate_plan, offline=True),
                      output / "candidate.log")
    verify_projection(project, entries)
    actions = observations(project, candidate_sources)
    if toolchain_identity(jdk, sdk) != runtime:
        raise PortError("Independent compiler runtime inputs changed")
    report = {
        "schema_version": 1, "scope": "independent-compiler-actions-only",
        "binding": asdict(binding), "run_id": run_id, "run_attempt": attempt,
        "nonce": uuid.uuid4().hex, "inputs": inputs, "actions": actions, "runtime": runtime,
        "network": "none", "candidate_configuration_executed": False,
        "required_check": False, "wp_complete": False, "dependency_unlock": False,
    }
    # This file is outside every container mount and is never read from candidate output.
    (output / "result.json").write_text(json.dumps(report), encoding="utf-8")
    return report


def authenticated_result(native, binding, pr_number, run_id, attempt):
    """Read an original default-branch run; never accept a candidate-supplied receipt."""
    from controller.merged_acceptance import log_objects
    from controller.repair_acceptance import read_job_log

    run, _ = native.workflow_run(run_id, attempt, WORKFLOW, binding.base_sha, ("workflow_dispatch",))
    if (
        run.get("head_branch") != native.policy["default_branch"]
        or run.get("display_title") != f"Independent compiler PR {pr_number} head {binding.head_sha}"
        or any(run.get(key, {}).get("login") not in native.policy["review_policy"]["authorized_coordinators"]
               for key in ("actor", "triggering_actor"))
    ):
        raise PortError("Independent compiler run has untrusted dispatch identity")
    jobs = native.backend.pages(
        f"{native.prefix}/actions/runs/{run_id}/attempts/{attempt}/jobs", "jobs",
    )
    if len(jobs) != 1 or (
        jobs[0].get("name") != "Independent compiler experiment"
        or jobs[0].get("run_id") != run_id or jobs[0].get("head_sha") != binding.base_sha
        or jobs[0].get("status") != "completed" or jobs[0].get("conclusion") != "success"
    ):
        raise PortError("Independent compiler job missing, skipped or failed")
    objects = log_objects(
        read_job_log(native, positive_integer(jobs[0].get("id"), "Independent compiler job ID")), STEP,
    )
    if len(objects) != 1:
        raise PortError("Missing or replayed trusted-host compiler result")
    report = objects[0]
    fields(report, {
        "schema_version", "scope", "binding", "run_id", "run_attempt", "nonce", "inputs",
        "actions", "runtime", "network", "candidate_configuration_executed", "required_check",
        "wp_complete", "dependency_unlock",
    }, label="independent compiler result")
    entries, inputs = projection(native.manifest.repo, binding.base_sha, binding.head_sha)
    expected_sources = compiler_sources(entries)
    if (
        type(report["schema_version"]) is not int or report["schema_version"] != 1
        or report["scope"] != "independent-compiler-actions-only"
        or Binding.parse(report["binding"]) != binding
        or type(report["run_id"]) is not int or report["run_id"] != run_id
        or type(report["run_attempt"]) is not int or report["run_attempt"] != attempt
        or report["inputs"] != inputs or report["network"] != "none"
        or any(report[key] is not False for key in (
            "candidate_configuration_executed", "required_check", "wp_complete", "dependency_unlock",
        ))
        or not isinstance(report["nonce"], str) or re.fullmatch(r"[a-f0-9]{32}", report["nonce"]) is None
    ):
        raise PortError("Independent compiler report binding or execution boundary differs")
    fields(report["runtime"], set(RUNTIME_FILES), label="independent compiler runtime")
    if any(not isinstance(value, str) or re.fullmatch(r"[a-f0-9]{64}", value) is None
           for value in report["runtime"].values()):
        raise PortError("Missing authenticated compiler runtime digest")
    actions = report["actions"]
    if (
        not isinstance(actions, list) or len(actions) != 2
        or any(not isinstance(item, dict) for item in actions)
        or {item.get("task") for item in actions} != set(TASKS)
    ):
        raise PortError("Missing or duplicate authenticated compiler action")
    for action in actions:
        fields(action, {"task", "classes", "output_sha256", "sources", "source_paths_sha256"},
               label="authenticated compiler action")
        positive_integer(action["classes"], "Authenticated compiler class count")
        paths = expected_sources[action["task"]]
        if type(action["sources"]) is not int or action["sources"] != len(paths) or (
            action["source_paths_sha256"] != source_paths_digest(paths)
        ):
            raise PortError("Authenticated compiler source coverage differs")
        if not isinstance(action["output_sha256"], str) or re.fullmatch(r"[a-f0-9]{64}", action["output_sha256"]) is None:
            raise PortError("Missing authenticated compiler output hash")
    return report


def validate_workflow(value, text):
    from controller.workflows import ACTION_PINS, validate_fixed_steps

    job = value.get("jobs", {}).get("compiler", {})
    if (
        set(value) != {"name", "run-name", "on", "permissions", "jobs"}
        or value["name"] != "Independent Android compiler experiment"
        or value["run-name"] != "Independent compiler PR ${{ inputs.pull_request }} head ${{ inputs.head_sha }}"
        or value["permissions"] != {"contents": "read", "pull-requests": "read", "actions": "read"}
        or set(value["on"]) != {"workflow_dispatch"} or set(value["jobs"]) != {"compiler"}
        or set(value["on"]["workflow_dispatch"].get("inputs", {})) != {"pull_request", "head_sha"}
        or any(
            item.get("type") != "string" or item.get("required") != "true"
            for item in value["on"]["workflow_dispatch"]["inputs"].values()
        )
        or set(job) != {"name", "if", "runs-on", "timeout-minutes", "env", "steps"}
        or job["name"] != "Independent compiler experiment"
        or job["if"] != "github.ref == format('refs/heads/{0}', github.event.repository.default_branch)"
        or job["runs-on"] != "ubuntu-24.04" or job["timeout-minutes"] != "150"
        or job["env"] != {"PR_NUMBER": "${{ inputs.pull_request }}", "CANDIDATE_HEAD": "${{ inputs.head_sha }}"}
    ):
        raise PortError("Independent compiler workflow must remain manual, read-only and default-branch-only")
    expected = [
        {
            "name": "Read-only deployed verifier",
            "uses": "actions/checkout@" + ACTION_PINS["actions/checkout"],
            "with": {"ref": "${{ github.sha }}", "fetch-depth": "0", "persist-credentials": "false"},
        },
        {"uses": "actions/setup-python@" + ACTION_PINS["actions/setup-python"],
         "with": {"python-version": "3.12.4"}},
        {"name": "Fetch candidate objects as data only", "run": [
            '[[ "$PR_NUMBER" =~ ^[1-9][0-9]*$ ]] || exit 2',
            '[[ "$CANDIDATE_HEAD" =~ ^[a-f0-9]{40}$ ]] || exit 2',
            'git fetch --no-tags origin "refs/pull/$PR_NUMBER/head"',
            'test "$(git rev-parse FETCH_HEAD)" = "$CANDIDATE_HEAD"',
        ]},
        {"name": "Provision pinned tools without candidate execution", "run": [
            'python tools/android-port/controller/ci.py provision '
            '--root "$RUNNER_TEMP/compiler-toolchain" --accept-sdk-license',
        ]},
        {"name": "Execute isolated trusted-base compiler actions",
         "env": {"ANDROID_PORT_GITHUB_TOKEN": "${{ github.token }}"}, "run": [
             'python tools/android-port/controller/compiler_verification.py run '
             '--pr "$PR_NUMBER" --head "$CANDIDATE_HEAD" '
             '--state "$RUNNER_TEMP/compiler-toolchain/environment.json" '
             '--output "$RUNNER_TEMP/compiler-experiment"',
         ]},
        {"name": STEP, "run": [
            'python tools/android-port/controller/compiler_verification.py report '
            '--output "$RUNNER_TEMP/compiler-experiment"',
        ]},
    ]
    validate_fixed_steps(job["steps"], expected, "Independent compiler", powershell=False)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("run", "inspect", "report"))
    parser.add_argument("--pr", type=int)
    parser.add_argument("--head")
    parser.add_argument("--run-id", type=int)
    parser.add_argument("--attempt", type=int)
    parser.add_argument("--state", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args(argv)
    try:
        if args.command == "report":
            report = load_json(args.output / "result.json", 65536)
            print(json.dumps(report))
            return 0
        from controller.backends import GitHubApi, CloudBackend
        from controller.native_review import NativeReview
        from controller.settings import Settings

        repo = Path(__file__).resolve().parents[3]
        manifest = load_manifest(repo)
        policy = load_json(repo / "docs/android/automation-policy.json")
        token = os.environ.get("ANDROID_PORT_GITHUB_TOKEN")
        if not token:
            raise PortError("Independent compiler requires read-only authenticated server facts")
        settings = Settings.from_env(dict(os.environ, ANDROID_PORT_GITHUB_TOKEN=token), policy)
        backend = CloudBackend(GitHubApi(token), manifest, policy, settings)
        pr = backend.api.request("GET", backend.prefix + f"/pulls/{positive_integer(args.pr, 'Compiler PR')}")
        base = backend.current_base()
        trusted_checkout(repo, base)
        if (
            pr.get("state") != "open" or pr.get("merged") is not False
            or pr.get("head", {}).get("sha") != args.head
            or pr.get("base", {}).get("sha") != base
            or pr.get("base", {}).get("repo", {}).get("full_name") != policy["repository"]
            or pr.get("base", {}).get("ref") != policy["default_branch"]
        ):
            raise PortError("Independent compiler requires an exact open current-base PR")
        binding = Binding(policy["repository"], "WP-003", base, args.head, REFERENCE_SHA,
                          manifest.sha256, policy_revision(manifest, policy))
        if args.command == "run":
            if args.output is None or args.state is None:
                raise PortError("Independent compiler requires explicit private output and toolchain state")
            execute(repo, binding, load_json(args.state), args.output,
                    positive_integer(int(os.environ["GITHUB_RUN_ID"]), "Compiler run"),
                    positive_integer(int(os.environ["GITHUB_RUN_ATTEMPT"]), "Compiler attempt"))
        else:
            native = NativeReview(backend, manifest, policy)
            print(json.dumps(authenticated_result(native, binding, args.pr, args.run_id, args.attempt)))
        return 0
    except (PortError, OSError, ValueError, KeyError) as error:
        print(f"BLOCKED: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
