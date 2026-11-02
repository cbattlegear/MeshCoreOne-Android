"""AndroidOnly: WP-003 Parse YAML and reject candidate/privileged trust-boundary drift."""

import argparse
import re
import sys
from pathlib import Path

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from controller.errors import PortError

ACTION_PINS = {
    "actions/cache": "caa296126883cff596d87d8935842f9db880ef25",
    "actions/checkout": "d23441a48e516b6c34aea4fa41551a30e30af803",
    "actions/setup-python": "ece7cb06caefa5fff74198d8649806c4678c61a1",
    "actions/upload-artifact": "b7c566a772e6b6bfb58ed0dc250532a479d7789f",
    "actions/download-artifact": "37930b1c2abaa49bbe596cd826c3c89aef350131",
}
TRUSTED_WORKFLOWS = (
    "android-port-dispatch.yml",
    "android-pr-shepherd.yml", "android-gate-integrity.yml",
)


def parse_yaml(text: str):
    import yaml

    class UniqueLoader(yaml.BaseLoader):
        def construct_mapping(self, node, deep=False):
            result = {}
            for key_node, value_node in node.value:
                key = self.construct_object(key_node, deep=deep)
                if not isinstance(key, str) or key in result:
                    raise PortError("Duplicate/invalid workflow YAML key")
                result[key] = self.construct_object(value_node, deep=deep)
            return result

    try:
        value = yaml.load(text, Loader=UniqueLoader)
    except yaml.YAMLError as error:
        raise PortError(f"Malformed workflow YAML: {error}") from error
    if not isinstance(value, dict):
        raise PortError("Workflow YAML must be a mapping")
    return value


def workflow_text(value, label: str):
    if not isinstance(value, str):
        raise PortError(f"{label} must be a string")
    if any(
        (ord(character) < 32 and character not in "\t\n")
        or 127 <= ord(character) <= 159 or character in "\u2028\u2029"
        for character in value
    ):
        raise PortError(f"{label} contains a non-LF or control character")
    return value


def executable_lines(script: str, *, expression_lines=(), powershell=False):
    script = workflow_text(script, "Workflow run script")
    lines = script.split("\n")
    if any("${{" in line and line.strip(" \t") not in expression_lines for line in lines):
        raise PortError("Workflow run script contains GitHub expression expansion")
    if powershell and any(re.match(r"[ \t]*#requires", line, re.IGNORECASE) for line in lines):
        raise PortError("Workflow run script contains a PowerShell #requires directive")
    return [
        line.strip(" \t") for line in lines
        if line.strip(" \t") and not line.lstrip(" \t").startswith("#")
    ]


def validate_fixed_steps(steps, expected, label: str):
    if len(steps) != len(expected):
        raise PortError(f"{label} has missing or additional steps")
    for index, (step, fixed) in enumerate(zip(steps, expected)):
        if (
            not isinstance(step, dict)
            or {key: value for key, value in step.items() if key != "run"}
            != {key: value for key, value in fixed.items() if key != "run"}
            or ("run" in step) != ("run" in fixed)
            or ("run" in fixed and executable_lines(step["run"], powershell=True) != fixed["run"])
        ):
            raise PortError(f"{label} step {index + 1} differs from the fixed execution contract")


def validate_build_setup(steps):
    toolchain_hash = (
        "${{ hashFiles('android/gradle/wrapper/gradle-wrapper.properties', "
        "'android/gradle/wrapper/gradle-wrapper.jar', 'tools/android-port/controller/toolchain-pins.json') }}"
    )
    dependencies_hash = (
        "${{ hashFiles('android/gradle/libs.versions.toml', 'android/gradle/verification-metadata.xml', "
        "'android/gradle/dependency-locks/*.lockfile', 'android/**/gradle.lockfile') }}"
    )
    cache_prefix = "android-gradle-v4-${{ runner.os }}-" + toolchain_hash + "-" + dependencies_hash + "-"
    validate_fixed_steps(steps, [
        {
            "name": "Read-only exact candidate",
            "uses": "actions/checkout@" + ACTION_PINS["actions/checkout"],
            "with": {
                "ref": "${{ github.event.pull_request.head.sha || github.sha }}",
                "fetch-depth": "0",
                "persist-credentials": "false",
            },
        },
        {
            "uses": "actions/setup-python@" + ACTION_PINS["actions/setup-python"],
            "with": {"python-version": "3.12.4", "architecture": "x64"},
        },
        {
            "name": "Install checksum-pinned workflow parser",
            "run": [
                "python -m pip install --disable-pip-version-check --require-hashes "
                "--no-deps --only-binary=:all: -r tools/android-port/controller/requirements-ci.txt --quiet",
            ],
        },
        {
            "name": "Declare one toolchain, work and Gradle cache root",
            "run": [
                "$root = Join-Path $env:RUNNER_TEMP 'meshcoreone-ci'",
                '"ANDROID_CI_WORK=$(Join-Path $root \'work\')" | Out-File $env:GITHUB_ENV -Append -Encoding utf8',
                '"ANDROID_CI_STATE=$(Join-Path (Join-Path $root \'toolchain\') \'environment.json\')" '
                '| Out-File $env:GITHUB_ENV -Append -Encoding utf8',
                '"GRADLE_USER_HOME=$(Join-Path $root \'gradle-home\')" | Out-File $env:GITHUB_ENV -Append -Encoding utf8',
            ],
        },
        {
            "name": "Restore verified Gradle and task-output cache",
            "uses": "actions/cache@" + ACTION_PINS["actions/cache"],
            "with": {
                "path": "${{ env.GRADLE_USER_HOME }}",
                "key": cache_prefix + "${{ github.run_id }}-${{ github.run_attempt }}",
                "restore-keys": (
                    cache_prefix + "\n"
                    "android-gradle-v3-${{ runner.os }}-\n"
                    "android-gradle-${{ runner.os }}-\n"
                ),
            },
        },
        {
            "name": "Validate frozen source inputs",
            "run": [
                "python tools/android-port/test_inventory.py --check",
                "python tools/android-port/extract_vectors.py --check",
            ],
        },
        {
            "name": "Run selected Python oracle assertions",
            "if": "${{ needs.scope.outputs.scaffold == 'true' }}",
            "run": [
                "python tools/android-port/oracle/run_tests.py --output (Join-Path $env:RUNNER_TEMP 'oracle-tests.json')",
            ],
        },
        {
            "name": "Provision exact first-party JDK and SDK archives",
            "run": [
                "python tools/android-port/controller/ci.py provision --root "
                "(Split-Path $env:ANDROID_CI_STATE -Parent) --accept-sdk-license",
            ],
        },
        {
            "name": "Check worker readiness",
            "run": ["python tools/android-port/controller/ci.py preflight"],
        },
    ], "Linux build setup")


def validate_boundary(value: dict, text: str):
    if value.get("permissions") != {"contents": "read"}:
        raise PortError("Workflow token must have contents:read only")
    if any(word in text for word in ("secrets.", "pull_request_target", "workflow_run", "self-hosted", "--allow-all", "--live")):
        raise PortError("Workflow crosses candidate/privileged execution boundary")
    for job in value.get("jobs", {}).values():
        if job.get("permissions", {"contents": "read"}) != {"contents": "read"}:
            raise PortError("Job token permissions were elevated")
        if job.get("environment") or job.get("container") or job.get("services"):
            raise PortError("Undeclared environment/shared-service build capability")
        for step in job.get("steps", []):
            if step.get("continue-on-error") == "true":
                raise PortError("Mandatory failure cannot be converted into success")
            if "uses" in step:
                name, separator, pin = step["uses"].partition("@")
                if not separator or ACTION_PINS.get(name) != pin or re.fullmatch(r"[0-9a-f]{40}", pin) is None:
                    raise PortError("Unreviewed/unpinned workflow action")
                if name == "actions/checkout" and step.get("with", {}).get("persist-credentials") != "false":
                    raise PortError("Candidate checkout credentials would persist")


def validate_candidate(value: dict, text: str):
    validate_boundary(value, text)
    if set(value) != {"name", "on", "permissions", "concurrency", "jobs"}:
        raise PortError("Candidate workflow must not add global shell defaults or environment")
    events = value.get("on", {})
    if not {"pull_request", "merge_group", "push", "workflow_dispatch"}.issubset(events):
        raise PortError("Required PR/merge-group/push/manual event is missing")
    jobs = value.get("jobs", {})
    if set(jobs) != {"scope", "controller", "build", "external-oracle", "backup-oracle", "android-ci"}:
        raise PortError("Missing/unknown mandatory CI job")
    gate = jobs["android-ci"]
    scope = jobs["scope"]
    if not isinstance(scope, dict):
        raise PortError("Candidate scope job is malformed")
    event_arguments = (
        '--candidate-event-name "${{ github.event_name }}" '
        '--candidate-event-path "${{ github.event_path }}"'
    )
    selector_command = (
        "python tools/android-port/controller/test_runner.py " + event_arguments + ' --scope-output "$GITHUB_OUTPUT"'
    )
    classifier_command = (
        'python tools/android-port/oracle/workflow_scope.py --event-name "${{ github.event_name }}" '
        '--event-path "${{ github.event_path }}" --output "$GITHUB_OUTPUT"'
    )
    scope_outputs = {
        "controller": "${{ steps.changed.outputs.controller }}",
        "accessibility-suite": "${{ steps.changed.outputs.accessibility-suite }}",
        "scaffold": "${{ steps.changed.outputs.scaffold }}",
        "protocol": "${{ steps.changed.outputs.protocol }}",
        "backup": "${{ steps.changed.outputs.backup }}",
        "benchmark": "${{ steps.changed.outputs.benchmark }}",
        "external-oracle": "${{ steps.changed.outputs['external-oracle'] }}",
    }
    scope_steps = scope.get("steps")
    if (
        set(scope) != {"name", "runs-on", "timeout-minutes", "outputs", "steps"}
        or scope.get("name") != "Classify candidate scopes"
        or scope.get("runs-on") != "ubuntu-24.04" or scope.get("timeout-minutes") != "5"
        or scope.get("outputs") != scope_outputs
        or not isinstance(scope_steps, list) or len(scope_steps) != 2
        or scope_steps[0] != {
            "name": "Read-only exact candidate and immutable diff endpoints",
            "uses": "actions/checkout@" + ACTION_PINS["actions/checkout"],
            "with": {
                "ref": "${{ github.event.pull_request.head.sha || github.sha }}",
                "fetch-depth": "0",
                "persist-credentials": "false",
            },
        }
        or not isinstance(scope_steps[1], dict)
        or set(scope_steps[1]) != {"id", "name", "run"}
        or scope_steps[1]["id"] != "changed"
        or scope_steps[1]["name"] != "Classify changed inputs with fail-safe defaults"
        or executable_lines(
            scope_steps[1]["run"], expression_lines=(classifier_command, selector_command),
        ) != [classifier_command, selector_command]
    ):
        raise PortError("Candidate scopes must come from the exact classifier and native selector without overrides")
    if (set(gate) != {"name", "if", "needs", "runs-on", "timeout-minutes", "steps"}
            or gate.get("name") != "android-ci" or gate.get("if") != "${{ always() }}"
            or gate.get("runs-on") != "ubuntu-24.04" or gate.get("timeout-minutes") != "5"
            or gate.get("needs") != ["scope", "controller", "build", "external-oracle", "backup-oracle"]):
        raise PortError("android-ci must always report every selected build outcome")
    build = jobs["build"]
    if any(jobs[name].get("runs-on") != "macos-26-intel" for name in ("external-oracle", "backup-oracle")):
        raise PortError("Reference oracles require the supported macOS26 Intel host; do not downgrade runtime or skip oracles")
    build_condition = (
        "${{ needs.scope.outputs.scaffold == 'true' || "
        "needs.scope.outputs.protocol == 'true' || "
        "needs.scope.outputs.backup == 'true' || "
        "needs.scope.outputs.benchmark == 'true' }}"
    )
    condition = workflow_text(build.get("if"), "Linux build condition")
    if (set(build) != {"name", "needs", "if", "runs-on", "timeout-minutes", "defaults", "steps"}
            or build.get("name") != "One Linux Android build"
            or build.get("runs-on") != "ubuntu-24.04" or build.get("timeout-minutes") != "75"
            or build.get("defaults") != {"run": {"shell": "pwsh"}}
            or build.get("needs") != ["scope"]
            or " ".join(re.split(r"[ \t\n]+", condition.strip(" \t\n"))) != build_condition):
        raise PortError("The selected Linux execution host must be mandatory, dependency-gated and unmatrixed")
    steps = build["steps"]
    if not isinstance(steps, list) or any(not isinstance(step, dict) for step in steps):
        raise PortError("Candidate Linux build steps are malformed")
    stage_steps = [step for step in steps if step.get("id") == "gradle"]
    if len(stage_steps) != 1:
        raise PortError("Candidate workflow must use one consolidated Gradle execution step")
    stage = steps.index(stage_steps[0])
    validate_build_setup(steps[:stage])
    stage_run = stage_steps[0].get("run", "")
    stage_environment = stage_steps[0].get("env", {})
    expected_stage_environment = {
        "RUN_SCAFFOLD": "${{ needs.scope.outputs.scaffold }}",
        "RUN_PROTOCOL": "${{ needs.scope.outputs.protocol }}",
        "RUN_BACKUP": "${{ needs.scope.outputs.backup }}",
        "RUN_BENCHMARK": "${{ needs.scope.outputs.benchmark }}",
        "RUN_CONTROLLER": "${{ needs.scope.outputs.controller }}",
    }
    stage_lines = [
        "if ($env:RUN_BENCHMARK -eq 'true') {",
        "& python tools/android-port/controller/ci.py run --stage benchmark",
        "if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }",
        "}",
        "if ($env:RUN_SCAFFOLD -eq 'true' -or $env:RUN_PROTOCOL -eq 'true' -or $env:RUN_BACKUP -eq 'true') {",
        "$scopes = @()",
        *(
            f"if ($env:RUN_{scope.upper()} -eq 'true') {{ $scopes += @('--scope', '{scope}') }}"
            for scope in ("scaffold", "protocol", "backup")
        ),
        "$affected = @()",
        "if ($env:RUN_CONTROLLER -ne 'true') { $affected = @('--affected-candidate') }",
        "& python tools/android-port/controller/ci.py run --stage scaffold @affected @scopes",
        "if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }",
        "}",
    ]
    if (
        set(stage_steps[0]) != {"id", "name", "env", "run"}
        or stage_steps[0].get("name") != "Run parallel Gradle validation and Android tasks"
        or stage_environment != expected_stage_environment
        or executable_lines(stage_run, powershell=True) != stage_lines
    ):
        raise PortError("Candidate Gradle stages must run selected benchmark verification and exact affected scaffold planning")
    selected_controller = (
        "${{ needs.scope.outputs.controller == 'true' || "
        "needs.scope.outputs.accessibility-suite == 'true' }}"
    )
    unselected_controller = (
        "${{ needs.scope.outputs.controller != 'true' && "
        "needs.scope.outputs.accessibility-suite != 'true' }}"
    )
    controller_steps = jobs["controller"]["steps"]
    selected_steps = [step for step in controller_steps if step.get("name") != "No controller inputs selected"]
    unselected_steps = [step for step in controller_steps if step.get("name") == "No controller inputs selected"]
    if (
        not selected_steps or any(step.get("if") != selected_controller for step in selected_steps)
        or len(unselected_steps) != 1 or unselected_steps[0].get("if") != unselected_controller
    ):
        raise PortError("Controller execution must honor either diff-selected inputs or the mandatory accessibility suite")
    validate_fixed_steps(steps[stage + 1:stage + 5], [
        {
            "name": "Validate localization discovery",
            "if": "${{ steps.gradle.outputs.localization == 'true' }}",
            "run": [
                "python tools/android-port/l10n_convert.py --check "
                "--verify-android-tests android/core/l10n/build/test-results/testDebugUnitTest",
            ],
        },
        {
            "name": "Inspect exact APK and static alignment",
            "if": "${{ steps.gradle.outputs.full_scaffold == 'true' }}",
            "run": ["python tools/android-port/controller/ci.py inspect"],
        },
        {
            "name": "Upload inspected debug APK for human testing",
            "if": "${{ success() && steps.gradle.outputs.full_scaffold == 'true' }}",
            "uses": "actions/upload-artifact@" + ACTION_PINS["actions/upload-artifact"],
            "with": {
                "name": (
                    "debug-apk-${{ github.event.pull_request.head.sha || github.sha }}"
                    "-${{ github.run_id }}-${{ github.run_attempt }}"
                ),
                "path": "android/app/build/outputs/apk/debug/app-debug.apk",
                "if-no-files-found": "error",
                "retention-days": "7",
            },
        },
        {
            "name": "Transfer actual backup bytes to the platform oracle",
            "if": "${{ needs.scope.outputs.backup == 'true' }}",
            "uses": "actions/upload-artifact@" + ACTION_PINS["actions/upload-artifact"],
            "with": {
                "name": "backup-input-${{ github.run_id }}-${{ github.run_attempt }}",
                "path": "android/core/data/build/reports/wp203/interop/",
                "if-no-files-found": "error",
                "retention-days": "1",
            },
        },
    ], "Linux build handoff")
    if (
        sum("ci.py inspect" in step.get("run", "") for step in steps) != 1
        or sum(step.get("name") == "Upload inspected debug APK for human testing" for step in steps) != 1
        or sum(step.get("name") == "Validate localization discovery" for step in steps) != 1
    ):
        raise PortError("Candidate requires one localization check and one inspected debug APK handoff")
    selected_build_gate = (
        'if [ "$SCAFFOLD_SELECTED" = "true" ] || [ "$PROTOCOL_SELECTED" = "true" ] '
        '|| [ "$BACKUP_SELECTED" = "true" ] || [ "$BENCHMARK_SELECTED" = "true" ]; then'
    )
    gate_steps = gate.get("steps")
    gate_environment = {
        "CONTROLLER_SELECTED": "${{ needs.scope.outputs.controller }}",
        "SCAFFOLD_SELECTED": "${{ needs.scope.outputs.scaffold }}",
        "PROTOCOL_SELECTED": "${{ needs.scope.outputs.protocol }}",
        "BACKUP_SELECTED": "${{ needs.scope.outputs.backup }}",
        "BENCHMARK_SELECTED": "${{ needs.scope.outputs.benchmark }}",
        "EXTERNAL_SELECTED": "${{ needs.scope.outputs['external-oracle'] }}",
        "CONTROLLER_RESULT": "${{ needs.controller.result }}",
        "BUILD_RESULT": "${{ needs.build.result }}",
        "EXTERNAL_RESULT": "${{ needs.external-oracle.result }}",
        "BACKUP_RESULT": "${{ needs.backup-oracle.result }}",
    }
    gate_lines = [
        'test "$CONTROLLER_RESULT" = "success"',
        selected_build_gate,
        'test "$BUILD_RESULT" = "success"',
        "else",
        'test "$BUILD_RESULT" = "skipped"',
        "fi",
        'if [ "$EXTERNAL_SELECTED" = "true" ]; then',
        'test "$EXTERNAL_RESULT" = "success"',
        "else",
        'test "$EXTERNAL_RESULT" = "skipped"',
        "fi",
        'if [ "$BACKUP_SELECTED" = "true" ]; then',
        'test "$BACKUP_RESULT" = "success"',
        "else",
        'test "$BACKUP_RESULT" = "skipped"',
        "fi",
    ]
    if (
        not isinstance(gate_steps, list) or len(gate_steps) != 1
        or not isinstance(gate_steps[0], dict)
        or set(gate_steps[0]) != {"name", "env", "run"}
        or gate_steps[0]["name"] != "Require every selected scope to pass"
        or gate_steps[0]["env"] != gate_environment
        or executable_lines(gate_steps[0]["run"]) != gate_lines
    ):
        raise PortError("Android CI must use direct fail-closed job results without duplicate report artifacts")
    all_runs = "\n".join(
        step.get("run", "") for job in jobs.values() for step in job.get("steps", [])
    )
    if all_runs.count("controller/test_runner.py") != 2:
        raise PortError("Controller requires one scope-only selector and exactly one strict test invocation")
    runner_command = (
        "python tools/android-port/controller/test_runner.py --include-tests tools/android-port/local "
        + event_arguments
    )
    parity_runner_command = runner_command.replace(
        " --candidate-event-name", " --include-tests android/parity-tests --candidate-event-name", 1,
    )
    selectors = [step for step in jobs["scope"]["steps"] if "controller/test_runner.py" in step.get("run", "")]
    if (
        len(selectors) != 1 or "if" in selectors[0] or selectors[0].get("continue-on-error")
        or selector_command not in selectors[0].get("run", "").splitlines()
    ):
        raise PortError("Candidate scope must select the committed native family without conditional or ignored failure")
    runners = [
        step for step in jobs["controller"]["steps"] if "controller/test_runner.py" in step.get("run", "")
    ]
    if (
        len(runners) != 1 or runners[0].get("if") != selected_controller
        or runners[0].get("continue-on-error")
        or [
            line for line in runners[0].get("run", "").splitlines()
            if "controller/test_runner.py" in line
        ] not in ([runner_command], [parity_runner_command])
    ):
        raise PortError("Controller CI must discover the real native accessibility runner regressions once and fail closed")
    for name in ("build", "external-oracle", "backup-oracle"):
        job = jobs[name]
        if job.get("if") == "${{ always() }}" or "scope" not in job.get("needs", []):
            raise PortError("Expensive jobs must be scope-selected and use default fail-closed dependencies")
    if jobs["external-oracle"].get("needs") != ["scope"]:
        raise PortError("The independent Swift codec oracle must not wait for controller tests")


def validate_setup(value: dict, text: str):
    validate_boundary(value, text)
    if set(value.get("jobs", {})) != {"copilot-setup-steps"}:
        raise PortError("Cloud setup requires exactly one copilot-setup-steps job")
    job = value["jobs"]["copilot-setup-steps"]
    if set(job) - {"steps", "permissions", "runs-on", "services", "snapshot", "timeout-minutes"}:
        raise PortError("Unsupported cloud setup job property would be ignored")
    if job.get("runs-on") != "ubuntu-24.04" or not 1 <= int(job.get("timeout-minutes", "0")) <= 59:
        raise PortError("Cloud setup needs supported Ubuntu x64 and a <=59 minute budget")
    runs = "\n".join(step.get("run", "") for step in job["steps"])
    if any(command not in runs for command in ("ci.py provision", "ci.py preflight", "ci.py run --stage prepare")):
        raise PortError("Cloud setup must install, check readiness and prepare real strict dependencies")


def validate_trusted(value: dict, text: str):
    validate_boundary(value, text)
    if set(value.get("on", {})) != {"workflow_dispatch"}:
        raise PortError("Privileged-duty wrappers must remain manual/default-branch-only")
    for job in value["jobs"].values():
        if job.get("if") != "github.ref == format('refs/heads/{0}', github.event.repository.default_branch)":
            raise PortError("Trusted duty must execute deployed default-branch code")
        if job.get("name") in ("parity-review", "gate-integrity", "android-ci"):
            raise PortError("Preview cannot impersonate a required gate")
        for step in job["steps"]:
            if step.get("uses", "").startswith("actions/checkout@") and step.get("with", {}).get("ref") != "${{ github.sha }}":
                raise PortError("Trusted duty checks out candidate code")
    if value.get("concurrency", {}).get("cancel-in-progress") != "false":
        raise PortError("Trusted controller operations require a serialized noncancelling lane")


def validate_publisher(value: dict, text: str):
    if set(value) != {"name", "on", "permissions", "concurrency", "jobs"}:
        raise PortError("Unexpected publisher execution property")
    if value.get("permissions") != {
        "contents": "read", "pull-requests": "read", "checks": "write", "actions": "read", "issues": "read",
    }:
        raise PortError("Native publisher requires only read facts and Checks write, never merge/dispatch authority")
    if set(value.get("on", {})) != {"workflow_dispatch"} or set(value.get("jobs", {})) != {"publish"}:
        raise PortError("Native publisher must be manual and contain only the trusted publication job")
    job = value["jobs"]["publish"]
    if (
        job.get("name") != "Native review ${{ inputs.work_package }} PR ${{ inputs.pull_request }} receipt ${{ inputs.review_id }}"
        or job.get("if") != "github.ref == format('refs/heads/{0}', github.event.repository.default_branch)"
        or job.get("runs-on") != "ubuntu-24.04" or job.get("timeout-minutes") != "15"
        or value.get("concurrency") != {"group": "android-port-controller", "cancel-in-progress": "false"}
        or set(job) != {"name", "if", "runs-on", "timeout-minutes", "env", "steps"}
    ):
        raise PortError("Publisher must use a bounded ephemeral default-branch serialized lane")
    if any(word in text for word in ("secrets.", "pull_request_target", "workflow_run", "self-hosted",
                                     "actions/cache", "actions/upload-artifact", "actions/download-artifact")):
        raise PortError("Publisher cannot consume privileged secrets, caches or candidate artifact/code execution")
    steps = job.get("steps", [])
    if len(steps) != 4:
        raise PortError("Unexpected trusted publisher execution step")
    if (
        [set(step) for step in steps] != [
            {"uses", "with"}, {"uses", "with"}, {"name", "run"}, {"name", "env", "run"},
        ]
        or steps[0].get("uses") != "actions/checkout@" + ACTION_PINS["actions/checkout"]
        or steps[1].get("uses") != "actions/setup-python@" + ACTION_PINS["actions/setup-python"]
    ):
        raise PortError("Unexpected publisher step execution property")
    for step in steps:
        if step.get("continue-on-error") or step.get("if"):
            raise PortError("Publisher steps cannot skip or ignore verification failures")
        if "uses" in step:
            name, _, pin = step["uses"].partition("@")
            if name not in ("actions/checkout", "actions/setup-python") or ACTION_PINS[name] != pin:
                raise PortError("Unexpected/unpinned publisher action")
    if steps[0].get("with") != {"ref": "${{ github.sha }}", "fetch-depth": "0", "persist-credentials": "false"}:
        raise PortError("Publisher must check out exact trusted code without persisted credentials")
    if steps[1].get("with") != {"python-version": "3.12.4"}:
        raise PortError("Publisher Python pin drift")
    if steps[2].get("run") != (
        '[[ "$PR_NUMBER" =~ ^[1-9][0-9]*$ ]] || exit 2\n'
        'git fetch --no-tags origin "refs/pull/$PR_NUMBER/head"\n'
    ):
        raise PortError("Candidate may be fetched only as Git data, never checked out or executed")
    if steps[3].get("env") != {"ANDROID_PORT_GITHUB_TOKEN": "${{ github.token }}"} or steps[3].get("run") != (
        'python tools/android-port/controller/native_review.py publish '
        '--trusted-base "$GITHUB_SHA" --wp "$WORK_PACKAGE" --pr "$PR_NUMBER" --review-id "$REVIEW_ID"'
    ):
        raise PortError("Only the trusted native publisher may receive the ephemeral Checks token")
    if job["env"] != {
        "WORK_PACKAGE": "${{ inputs.work_package }}", "PR_NUMBER": "${{ inputs.pull_request }}",
        "REVIEW_ID": "${{ inputs.review_id }}",
    }:
        raise PortError("Publisher inputs must be quoted environment data")


def validate_workflows(repo: Path):
    directory = repo / ".github" / "workflows"
    for name, validator in (
        ("android-ci.yml", validate_candidate), ("copilot-setup-steps.yml", validate_setup),
        ("android-parity-review.yml", validate_publisher),
        *((name, validate_trusted) for name in TRUSTED_WORKFLOWS),
    ):
        text = (directory / name).read_text(encoding="utf-8")
        validator(parse_yaml(text), text)
    return {"result": "valid", "scope": "YAML/trust-boundary assertions; no live publisher/cloud/branch-rule claim"}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[3])
    args = parser.parse_args(argv)
    try:
        print(__import__("json").dumps(validate_workflows(args.repo), indent=2))
        return 0
    except (PortError, OSError, ValueError, ImportError) as error:
        print(f"BLOCKED: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
