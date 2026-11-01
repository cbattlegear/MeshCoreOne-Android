"""AndroidOnly: WP-003 Audited local-check recovery, not forced unlock or product acceptance."""

import argparse
import csv
import ctypes
import hashlib
import json
import math
import os
from pathlib import Path, PureWindowsPath
import re
import select
import signal
import socket
import sqlite3
import subprocess
import sys
import time

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from controller.errors import PortError

HEX = re.compile(r"[0-9a-f]{40}")
TOKEN = re.compile(r"[0-9a-f]{32}")
RECOVERY_SECONDS = 10


def receipt_key(receipt):
    fields = {"repository", "resource", "owner", "payload", "acquired", "record_state"}
    if not isinstance(receipt, dict) or set(receipt) != fields:
        raise PortError("Recovery requires the entire original hard-lock receipt")
    payload = receipt["payload"]
    if (
        not isinstance(receipt["repository"], str)
        or re.fullmatch(r"[\w.-]+/[\w.-]+", receipt["repository"]) is None
        or receipt["record_state"] != "hard"
        or type(receipt["acquired"]) not in (int, float)
        or not math.isfinite(receipt["acquired"])
        or not isinstance(payload, dict)
        or not {"commit", "tree", "pid", "token", "completion", "mode"} <= set(payload)
        or set(payload) - {"commit", "tree", "pid", "token", "completion", "mode",
                           "affected_base", "jvm_lane"}
        or any(not isinstance(payload.get(key), str) or HEX.fullmatch(payload[key]) is None
               for key in ("commit", "tree"))
        or not isinstance(payload.get("token"), str) or TOKEN.fullmatch(payload["token"]) is None
        or receipt["owner"] != "local-check:" + payload["token"]
        or type(payload.get("pid")) is not int or payload["pid"] <= 0
        or payload.get("mode") not in ("manual", "advisory")
        or not isinstance(payload.get("completion"), str)
        or not (Path(payload["completion"]).is_absolute()
                or PureWindowsPath(payload["completion"]).is_absolute())
        or PureWindowsPath(payload["completion"]).name != "cleanup.json"
        or "affected_base" in payload and (
            not isinstance(payload["affected_base"], str) or HEX.fullmatch(payload["affected_base"]) is None
        )
    ):
        raise PortError("Malformed invocation-bound local-check recovery receipt")
    resource = "gradle-execution"
    if "jvm_lane" in payload:
        lane = payload["jvm_lane"]
        if (
            not isinstance(lane, dict) or set(lane) != {"host", "slot", "capacity"}
            or not isinstance(lane["host"], str) or re.fullmatch(r"[0-9a-f]{64}", lane["host"]) is None
            or type(lane["slot"]) is not int or type(lane["capacity"]) is not int
            or not 0 <= lane["slot"] < lane["capacity"]
            or payload["mode"] != "manual"
        ):
            raise PortError("Malformed invocation-bound JVM lane")
        resource += ":" + lane["host"] + ":" + str(lane["slot"])
    if receipt["resource"] != resource:
        raise PortError("Recovery is limited to this exact local-check Gradle resource")
    return hashlib.sha256(json.dumps(receipt, sort_keys=True).encode("utf-8")).hexdigest()


def operator_identity():
    if os.name == "nt":
        whoami = Path(os.environ["SystemRoot"]) / "System32" / "whoami.exe"
        row, = csv.reader(subprocess.check_output(
            [str(whoami), "/user", "/fo", "csv", "/nh"], text=True
        ).splitlines())
        user, principal = row
    else:
        import pwd
        principal = str(os.geteuid())
        user = pwd.getpwuid(os.geteuid()).pw_name
    return {
        "host": socket.gethostname(), "user": user, "principal": principal, "pid": os.getpid(),
    }


def launcher_alive(pid):
    if os.name == "nt":
        kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel.OpenProcess.argtypes = [ctypes.c_ulong, ctypes.c_int, ctypes.c_ulong]
        kernel.OpenProcess.restype = ctypes.c_void_p
        kernel.CloseHandle.argtypes = [ctypes.c_void_p]
        handle = kernel.OpenProcess(0x1000, False, pid)
        if handle:
            kernel.CloseHandle(handle)
            return True
        if ctypes.get_last_error() == 87:  # ERROR_INVALID_PARAMETER: PID does not exist.
            return False
        raise PortError("Cannot establish original launcher liveness")
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError as error:
        raise PortError("Cannot inspect the original launcher principal") from error


def regular_json(path):
    if not path.is_file() or path.is_symlink() or path.resolve() != path:
        raise PortError(f"Missing or noncanonical recovery input: {path}")
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        raise PortError(f"Malformed recovery input: {path}") from error


def cleanup_proof(receipt):
    receipt_key(receipt)
    if launcher_alive(receipt["payload"]["pid"]):
        raise PortError("Original launcher may still be active; recovery refuses live ownership")
    proof = regular_json(Path(receipt["payload"]["completion"]))
    if (
        not isinstance(proof, dict)
        or set(proof) != {"schema_version", "token", "status", "returncode"}
        or type(proof["schema_version"]) is not int or proof["schema_version"] != 1
        or proof["token"] != receipt["payload"]["token"]
        or proof["status"] != "owned-children-reaped" or type(proof["returncode"]) is not int
    ):
        raise PortError("Missing or wrong-invocation supervisor cleanup proof")
    return proof


def admission_binding(admission, receipt):
    data = regular_json(admission)
    payload = receipt["payload"]
    expected = {
        "schema_version": 1, "interlock_version": 2, "owner": receipt["owner"],
        "token": payload["token"], "commit": payload["commit"], "tree": payload["tree"],
        **({"jvm_lane": payload["jvm_lane"]} if "jvm_lane" in payload else {}),
    }
    if data != expected or any(type(data.get(key)) is not int for key in ("schema_version", "interlock_version")):
        raise PortError("Admission differs from the original execution invocation")


def process_stat(pid):
    try:
        values = Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()
    except FileNotFoundError as error:
        raise ProcessLookupError(f"Process {pid} exited during recovery") from error
    return {"state": values[0], "parent": int(values[1]), "start": int(values[19])}


def child_pids(pid):
    result = set()
    for path in Path(f"/proc/{pid}/task").glob("*/children"):
        try:
            result.update(int(value) for value in path.read_text().split())
        except FileNotFoundError:
            continue
    return result


def owned_pids(root):
    result, pending = set(), list(child_pids(root))
    while pending:
        pid = pending.pop()
        if pid not in result:
            result.add(pid)
            pending.extend(child_pids(pid))
    return result


def owned_by(pid, root):
    seen = set()
    while pid != root:
        if pid <= 1 or pid in seen:
            return False
        seen.add(pid)
        pid = process_stat(pid)["parent"]
    return True


def supervisor_script(admission, trusted_advisory):
    """Support snapshot and trusted-adapter launch paths without trusting candidate bytes."""
    snapshot = admission.parent / "control" / "advisory.py"
    for candidate in (snapshot, trusted_advisory):
        if (
            candidate.is_file() and not candidate.is_symlink() and candidate.resolve() == candidate
            and candidate.read_bytes().replace(b"\r\n", b"\n")
            == trusted_advisory.read_bytes().replace(b"\r\n", b"\n")
        ):
            return candidate
    raise PortError("No reviewed invocation-local or adapter supervisor source")


def supervisor_pid(admission, receipt, trusted_advisory):
    """Find a live trusted supervisor by its invocation AND actual open admission inode."""
    admission_binding(admission, receipt)
    control = supervisor_script(admission, trusted_advisory)
    found = []
    for entry in Path("/proc").iterdir():
        try:
            if not entry.name.isdecimal() or entry.stat().st_uid != os.geteuid():
                continue
            arguments = (entry / "cmdline").read_bytes().decode("utf-8").split("\0")
            if len(arguments) < 3 or arguments[1] != str(control):
                continue
            options = arguments[2:arguments.index("--")]
            expected = {
                "--admission": str(admission), "--completion": str(admission.parent / "cleanup.json"),
                "--commit": receipt["payload"]["commit"], "--token": receipt["payload"]["token"],
            }
            if not all(options.count(key) == 1 and options[options.index(key) + 1] == value
                       for key, value in expected.items()):
                continue
            if (entry / "fd/8").samefile(admission):
                found.append(int(entry.name))
        except (FileNotFoundError, ProcessLookupError):
            continue
        except (ValueError, IndexError, UnicodeError):
            continue
    if len(found) != 1:
        raise PortError("No unique live invocation-bound supervisor; missing PIDs are not cleanup proof")
    return found[0]


def cancel_supervisor(admission, receipt, trusted_advisory):
    """Cancel only pidfd-pinned descendants; leave the real subreaper alive to emit its proof."""
    if (
        not sys.platform.startswith("linux") or not hasattr(os, "pidfd_open")
        or not hasattr(signal, "pidfd_send_signal")
    ):
        raise PortError("Owned cancellation requires the actual supported Linux pidfd host")
    root = supervisor_pid(admission, receipt, trusted_advisory)
    descriptors, stopped = {}, set()
    resume_root = False
    deadline = time.monotonic() + RECOVERY_SECONDS
    try:
        root_fd = os.pidfd_open(root)
    except ProcessLookupError:
        wait_cleanup(admission, deadline)
        return

    def pin(pid):
        descriptor = os.pidfd_open(pid)
        try:
            if not owned_by(pid, root):
                raise PortError("Recovery descendant no longer belongs to the pinned supervisor")
            identity = process_stat(pid)["start"]
        except BaseException:
            os.close(descriptor)
            raise
        descriptors[pid] = (descriptor, identity)
        return descriptor

    try:
        initial = process_stat(root)
        # Pin before revalidating the live authority; never signal a recycled numeric PID.
        if supervisor_pid(admission, receipt, trusted_advisory) != root:
            raise PortError("Supervisor identity changed during recovery")
        # Record the obligation before stopping, so an interruption cannot strand the verified root.
        resume_root = True
        signal.pidfd_send_signal(root_fd, signal.SIGSTOP)
        while process_stat(root)["state"] not in ("T", "t"):
            if time.monotonic() >= deadline:
                raise PortError("Supervisor could not be paused; ownership retained")
            time.sleep(0.01)
        if process_stat(root)["start"] != initial["start"]:
            raise PortError("Supervisor start identity changed")
        while True:
            children = owned_pids(root)
            if not children:
                break
            for pid in children:
                if pid not in descriptors:
                    try:
                        descriptor = pin(pid)
                        stopped.add(pid)
                        signal.pidfd_send_signal(descriptor, signal.SIGSTOP)
                    except ProcessLookupError:
                        continue
            try:
                frozen = all(process_stat(pid)["state"] in ("T", "t", "Z")
                             for pid in owned_pids(root))
            except ProcessLookupError:
                frozen = False
            if frozen and owned_pids(root) <= set(descriptors):
                break
            if time.monotonic() >= deadline:
                raise PortError("Owned descendants could not be bounded; ownership retained")
            time.sleep(0.01)
        for pid, (descriptor, start) in descriptors.items():
            while True:
                try:
                    if process_stat(pid)["start"] != start or not owned_by(pid, root):
                        raise PortError("Descendant identity changed; ownership retained")
                    signal.pidfd_send_signal(descriptor, signal.SIGKILL)
                    break
                except ProcessLookupError:
                    if select.select([descriptor], [], [], 0)[0]:
                        break
                    if time.monotonic() >= deadline:
                        raise PortError("Descendant snapshot remained unstable; ownership retained")
                    time.sleep(0.01)
        for descriptor, _ in descriptors.values():
            if not select.select([descriptor], [], [], max(0, deadline - time.monotonic()))[0]:
                raise PortError("Owned process termination unproven; ownership retained")
    except ProcessLookupError:
        if not select.select([root_fd], [], [], 0)[0]:
            raise PortError("Supervisor snapshot remained unstable; ownership retained")
    finally:
        for pid in stopped:
            try:
                signal.pidfd_send_signal(descriptors[pid][0], signal.SIGCONT)
            except ProcessLookupError:
                pass
        if resume_root:
            try:
                signal.pidfd_send_signal(root_fd, signal.SIGCONT)
            except ProcessLookupError:
                pass
        for descriptor, _ in descriptors.values():
            os.close(descriptor)
        os.close(root_fd)
    wait_cleanup(admission, deadline)


def wait_cleanup(admission, deadline):
    # Only the existing supervisor writes cleanup.json, including the real failed/cancelled status.
    while not (admission.parent / "cleanup.json").is_file():
        if time.monotonic() >= deadline:
            raise PortError("Supervisor did not emit cleanup proof; ownership retained")
        time.sleep(0.02)


def advisory_source():
    root = Path(__file__).parents[1]
    source = root / "local/advisory.py"
    return source if source.is_file() else root / "advisory.py"


def cancel_owned(receipt, distribution=None, *, repo=None):
    receipt_key(receipt)
    if launcher_alive(receipt["payload"]["pid"]):
        raise PortError("Original launcher may still be active; recovery refuses live ownership")
    admission = Path(receipt["payload"]["completion"]).with_name("admission.json")
    admission_binding(admission, receipt)
    if os.name != "nt":
        cancel_supervisor(admission, receipt, advisory_source())
        return
    if not distribution:
        raise PortError("Windows recovery requires the explicit original WSL distribution")
    prefix = ["wsl.exe", "--distribution", distribution, "--exec"]

    def linux_path(path):
        return subprocess.check_output(
            [*prefix, "wslpath", "-u", str(path)], text=True, encoding="utf-8"
        ).strip()

    request = {
        "receipt": receipt,
        "repository": linux_path((repo or Path.cwd()).resolve()),
    }
    command = [
        *prefix, "bash", "-c",
        'work="${ANDROID_LOCAL_HOME:-$HOME/meshcoreone-work}"; '
        'exec "${ANDROID_LOCAL_PYTHON:-$work/venv312/bin/python}" "$1" --linux-cancel',
        "--", linux_path(Path(__file__).resolve()),
    ]
    environment = os.environ.copy()
    if environment.get("ANDROID_PORT_LEDGER"):
        entries = [entry for entry in environment.get("WSLENV", "").split(":")
                   if entry and entry.split("/", 1)[0].upper() != "ANDROID_PORT_LEDGER"]
        environment["WSLENV"] = ":".join([*entries, "ANDROID_PORT_LEDGER"])
    subprocess.run(
        command, input=json.dumps(request), text=True, check=True,
        timeout=RECOVERY_SECONDS + 30, env=environment,
    )


def authorize_cancellation(ledger, receipt):
    receipt_key(receipt)
    if ledger.repository != receipt["repository"] or receipt not in ledger.hard_lock_records():
        raise PortError("Cancellation receipt differs from canonical execution ownership")
    if launcher_alive(receipt["payload"]["pid"]):
        raise PortError("Original launcher may still be active; recovery refuses live ownership")
    admission_binding(Path(receipt["payload"]["completion"]).with_name("admission.json"), receipt)


def authorize_windows_handoff(receipt, repo):
    def windows_path(path):
        return subprocess.check_output(
            ["wslpath", "-w", str(path)], text=True, encoding="utf-8"
        ).strip()

    request = {
        "receipt": receipt, "repository": windows_path(repo),
        "script": windows_path(Path(__file__).resolve()),
    }
    command = (
        "$ErrorActionPreference = 'Stop'; "
        "[Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false); "
        "[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false); "
        "$OutputEncoding = [System.Text.UTF8Encoding]::new($false); "
        "$json = [Console]::In.ReadToEnd(); "
        "$request = ConvertFrom-Json -InputObject $json; "
        "$python = Get-Command python.exe -CommandType Application -ErrorAction Stop | Select-Object -First 1; "
        "$json | & $python.Source -X utf8 $request.script --windows-authorize; "
        "exit $LASTEXITCODE"
    )
    try:
        result = subprocess.run(
            ["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command],
            input=json.dumps(request), text=True, encoding="utf-8",
            capture_output=True, check=True, timeout=15,
        )
    except subprocess.CalledProcessError as error:
        detail = (error.stderr or "").strip() or f"exit {error.returncode}"
        raise PortError(f"Windows ownership/source verification failed: {detail}") from error
    except (OSError, subprocess.SubprocessError) as error:
        raise PortError(f"Cannot invoke Windows ownership/source verification: {error}") from error
    try:
        response = json.loads(result.stdout)
    except ValueError as error:
        raise PortError("Malformed Windows ownership authorization response") from error
    if response != {
        "receipt_sha256": receipt_key(receipt), "repository": receipt["repository"],
    }:
        raise PortError("Windows authorization differs from this exact recovery invocation")


def recover(ledger, receipt, *, cancel=False, distribution=None, repo=None):
    receipt_key(receipt)
    # Borrow the exact receipt without relabelling it or releasing an outer context.
    if receipt not in ledger.hard_lock_records():
        return ledger.recover_execution(receipt)  # Only an exact prior audit permits idempotent replay.
    if cancel and not Path(receipt["payload"]["completion"]).exists():
        cancel_owned(receipt, distribution, repo=repo)
    return ledger.recover_execution(receipt)


def recover_owner(ledger, owner, *, cancel=False, distribution=None, repo=None):
    if not isinstance(owner, str) or re.fullmatch(r"local-check:[0-9a-f]{32}", owner) is None:
        raise PortError("Recovery requires the exact local-check invocation owner from status")
    receipts = [row for row in ledger.hard_lock_records() if row["owner"] == owner]
    if len(receipts) > 1:
        raise PortError("Ambiguous execution ownership; select an exact receipt rather than unrelated resources")
    if receipts:
        return recover(ledger, receipts[0], cancel=cancel, distribution=distribution, repo=repo)
    with ledger.transaction() as connection:
        audits = [dict(row) for row in connection.execute(
            "SELECT * FROM execution_recoveries WHERE repository=?",
            (ledger.repository,),
        ) if json.loads(row["original_row"])["owner"] == owner]
    if len(audits) == 1:
        return {"recovered": True, "already_recovered": True, "audit": audits[0]}
    raise PortError("Owner is not held and has no unique recovery audit; cleanup is not established")


def validate_trusted_source(repo):
    """Validate reviewed default-base source and policy without opening the execution ledger."""
    repo = repo.resolve()

    def git(*args):
        return subprocess.check_output(["git", "-C", str(repo), *args])

    reference = git("symbolic-ref", "--quiet", "refs/remotes/origin/HEAD").decode().strip()
    if not reference.startswith("refs/remotes/origin/"):
        raise PortError("Missing trusted origin default-base authority")
    revision = git("rev-parse", reference + "^{commit}").decode().strip()
    names = {
        name for name in git(
            "ls-tree", "--name-only", revision + ":tools/android-port/controller"
        ).decode("utf-8").splitlines() if name.endswith(".py")
    }
    controllers = {Path(__file__).parent.resolve(), repo / "tools/android-port/controller"}
    if "execution_recovery.py" not in names or any(
        {path.name for path in directory.glob("*.py")} != names for directory in controllers
    ):
        raise PortError("Recovery requires reviewed default-base controller sources in both checkouts")
    files = {
        "tools/android-port/controller/" + name: {directory / name for directory in controllers}
        for name in sorted(names)
    }
    for name in ("reserve.py", "check.py", "advisory.py"):
        relative = "tools/android-port/local/" + name
        files[relative] = {repo / relative}
    files["tools/android-port/local/advisory.py"].add(advisory_source())
    for relative, paths in files.items():
        expected = git("show", revision + ":" + relative)
        for path in paths:
            if path.read_bytes().replace(b"\r\n", b"\n") != expected.replace(b"\r\n", b"\n"):
                raise PortError(f"Recovery requires reviewed default-base source: {relative}")
    sys.path.insert(0, str(repo / "tools/android-port/local"))
    import reserve
    current_revision, policy = reserve.trusted_default_policy(repo)
    if current_revision != revision:
        raise PortError("Trusted recovery default-base authority changed during validation")
    repository = reserve.repository_identity(policy, "Trusted default-base policy")
    return repository


def trusted_context(repo):
    repo = repo.resolve()
    repository = validate_trusted_source(repo)
    import reserve
    from controller.ledger import Ledger
    return Ledger(reserve.shared_ledger(repo), repository)


def main(argv=None):
    if argv is None and sys.argv[1:] == ["--windows-authorize"]:
        try:
            if sys.platform != "win32":
                raise PortError("Canonical Windows ownership must be verified on Windows")
            request = json.load(sys.stdin)
            if (
                not isinstance(request, dict) or set(request) != {"receipt", "repository", "script"}
                or not isinstance(request["repository"], str)
                or not isinstance(request["script"], str)
                or Path(request["script"]).resolve() != Path(__file__).resolve()
            ):
                raise PortError("Malformed Windows ownership verification request")
            ledger = trusted_context(Path(request["repository"]))
            authorize_cancellation(ledger, request["receipt"])
            print(json.dumps({
                "receipt_sha256": receipt_key(request["receipt"]), "repository": ledger.repository,
            }))
            return 0
        except (PortError, OSError, ValueError, sqlite3.Error, subprocess.SubprocessError) as error:
            print(f"BLOCKED: {error}", file=sys.stderr)
            return 1
    if argv is None and sys.argv[1:] == ["--linux-cancel"]:
        # A Windows-ledger handoff never opens or substitutes a Linux database.
        try:
            request = json.load(sys.stdin)
            if (
                not isinstance(request, dict)
                or set(request) != {"receipt", "repository"}
                or not isinstance(request["repository"], str)
            ):
                raise PortError("Malformed or wrong-invocation Windows-to-Linux recovery handoff")
            receipt_key(request["receipt"])
            authorize_windows_handoff(request["receipt"], Path(request["repository"]))
            admission = Path(subprocess.check_output([
                "wslpath", "-u",
                str(PureWindowsPath(request["receipt"]["payload"]["completion"]).with_name("admission.json")),
            ], text=True, encoding="utf-8").strip())
            cancel_supervisor(admission, request["receipt"], advisory_source())
            return 0
        except (PortError, OSError, ValueError, sqlite3.Error, subprocess.SubprocessError) as error:
            print(f"BLOCKED: {error}", file=sys.stderr)
            return 1
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    commands = parser.add_subparsers(dest="command", required=True)
    status = commands.add_parser("status", help="Inspect receipts and genuine completion without transport")
    status.add_argument("--resource")
    status.add_argument("--receipt-output", type=Path)
    for name in ("recover", "check"):
        command = commands.add_parser(name)
        target = command.add_mutually_exclusive_group(required=name == "recover")
        target.add_argument("--owner", help="Exact invocation owner from status; no receipt file needed")
        target.add_argument("--receipt", type=Path, help="Optional exact prior receipt for stale-CAS diagnostics")
        command.add_argument("--cancel-owned", action="store_true")
        command.add_argument("--distribution")
        if name == "check":
            command.add_argument("--commit", required=True)
            command.add_argument("--affected-base")
            command.add_argument("--parallel-jvm", action="store_true")
    args = parser.parse_args(argv)
    try:
        if args.command == "check":
            if (
                HEX.fullmatch(args.commit) is None
                or args.affected_base and HEX.fullmatch(args.affected_base) is None
                or args.parallel_jvm and not args.affected_base
            ):
                raise PortError("Check requires immutable candidate/base and the existing JVM lane contract")
        repo = args.repo.resolve()
        ledger = trusted_context(repo)
        if args.command == "status":
            rows = ledger.hard_lock_records()
            if args.resource:
                rows = [row for row in rows if row["resource"] == args.resource]
            inspections = []
            for row in rows:
                try:
                    proof = cleanup_proof(row)
                    inspections.append({"receipt": row, "cleanup": proof, "state": "recoverable"})
                except PortError as error:
                    inspections.append({"receipt": row, "state": "retained", "reason": str(error)})
            if args.receipt_output:
                if not args.resource or len(rows) != 1 or not args.receipt_output.is_absolute():
                    raise PortError("Receipt export requires one explicit held resource and an absolute new file")
                with args.receipt_output.open("x", encoding="utf-8") as output:
                    json.dump(rows[0], output, indent=2)
            print(json.dumps({"executions": inspections}, indent=2))
            return 0
        if args.cancel_owned and not (args.receipt or args.owner):
            raise PortError("Cancellation requires the exact invocation owner or receipt")
        if args.owner:
            result = recover_owner(
                ledger, args.owner, cancel=args.cancel_owned, distribution=args.distribution, repo=repo
            )
            print(json.dumps(result, indent=2))
        elif args.receipt:
            result = recover(ledger, regular_json(args.receipt.absolute()),
                             cancel=args.cancel_owned, distribution=args.distribution, repo=repo)
            print(json.dumps(result, indent=2))
        if args.command == "recover":
            return 0
        command = [
            sys.executable, str(args.repo.resolve() / "tools/android-port/local/check.py"),
            "--commit", args.commit,
        ]
        if args.distribution:
            command += ["--distribution", args.distribution]
        if args.affected_base:
            command += ["--affected-base", args.affected_base]
        if args.parallel_jvm:
            command.append("--parallel-jvm")
        return subprocess.run(command, cwd=args.repo.resolve()).returncode
    except (PortError, OSError, ValueError, sqlite3.Error, subprocess.SubprocessError) as error:
        print(f"BLOCKED: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
