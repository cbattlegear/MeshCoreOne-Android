"""AndroidOnly: WP-003 Invocation-bound recovery fixtures; never host Gradle or runtime deployment."""

import contextlib
from concurrent.futures import ThreadPoolExecutor
import copy
import io
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

from controller import execution_recovery as recovery
from controller.errors import PortError
from controller.ledger import Ledger


class ExecutionRecoveryTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="recovery-fixture-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.ledger = Ledger(self.root / "ledger.sqlite3", "fixture/repository")
        self.token = "a" * 32
        self.completion = self.root / "cleanup.json"
        payload = {
            "commit": "b" * 40, "tree": "c" * 40, "pid": 2_147_483_647,
            "token": self.token, "completion": str(self.completion), "mode": "manual",
        }
        self.ledger.acquire_hard_lock("gradle-execution", "local-check:" + self.token, payload, 123.0)
        self.receipt, = self.ledger.hard_lock_records()
        self.admission = self.root / "admission.json"
        self.admission.write_text(json.dumps({
            "schema_version": 1, "interlock_version": 2, "owner": self.receipt["owner"],
            "token": self.token, "commit": payload["commit"], "tree": payload["tree"],
        }), encoding="utf-8")

    def proof(self, **overrides):
        value = {
            "schema_version": 1, "token": self.token,
            "status": "owned-children-reaped", "returncode": -9,
        }
        value.update(overrides)
        self.completion.write_text(json.dumps(value), encoding="utf-8")

    def audit_rows(self):
        with self.ledger.transaction() as connection:
            return [dict(row) for row in connection.execute("SELECT * FROM execution_recoveries")]

    def test_missing_cleanup_and_missing_pid_never_release(self):
        with self.assertRaisesRegex(PortError, "Missing or noncanonical"):
            recovery.recover(self.ledger, self.receipt)
        self.assertEqual(self.ledger.hard_lock_records(), [self.receipt])
        self.assertEqual(self.audit_rows(), [])

    def test_owner_recovery_needs_no_session_supervisor_or_exported_receipt(self):
        self.proof()
        self.admission.unlink()
        result = recovery.recover_owner(self.ledger, self.receipt["owner"])
        self.assertTrue(result["recovered"])
        again = recovery.recover_owner(self.ledger, self.receipt["owner"])
        self.assertTrue(again["already_recovered"])
        self.assertEqual(self.ledger.hard_lock_records(), [])

    def test_owner_lookup_never_adopts_another_invocation_or_unexplained_missing_owner(self):
        for owner in ("local-check:" + "d" * 32, "foreign", self.receipt["owner"] + "suffix"):
            with self.subTest(owner=owner), self.assertRaises(PortError):
                recovery.recover_owner(self.ledger, owner, cancel=True)
        self.assertEqual(self.ledger.hard_lock_records(), [self.receipt])

    def test_audited_recovery_preserves_original_raw_row_and_real_failed_status(self):
        self.proof()
        with self.ledger.transaction() as connection:
            original = dict(connection.execute("SELECT * FROM hard_locks").fetchone())
        result = recovery.recover(self.ledger, self.receipt)
        self.assertFalse(result["already_recovered"])
        audit, = self.audit_rows()
        self.assertEqual(json.loads(audit["original_row"]), original)
        self.assertEqual(json.loads(audit["cleanup"])["returncode"], -9)
        self.assertNotEqual(json.loads(audit["actor"])["principal"], self.receipt["owner"])
        self.assertEqual(self.ledger.hard_lock_records(), [])

    def test_idempotent_recovery_never_deletes_new_owner_or_requires_old_inputs(self):
        self.proof()
        first = recovery.recover(self.ledger, self.receipt)
        self.completion.unlink()
        self.ledger.acquire_hard_lock("gradle-execution", "new-check", {"next": True}, 999.0)
        current = self.ledger.hard_lock_records()
        second = recovery.recover(self.ledger, self.receipt, cancel=True)
        self.assertTrue(second["already_recovered"])
        self.assertEqual(first["audit"], second["audit"])
        self.assertEqual(self.ledger.hard_lock_records(), current)
        self.assertEqual(len(self.audit_rows()), 1)

    def test_concurrent_recovery_is_one_atomic_audit(self):
        self.proof()
        with ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(lambda _: recovery.recover(
                Ledger(self.ledger.path, self.ledger.repository), self.receipt
            ), range(2)))
        self.assertEqual(sorted(row["already_recovered"] for row in results), [False, True])
        self.assertEqual(len(self.audit_rows()), 1)

    def test_missing_receipt_cannot_be_recreated_even_with_matching_cleanup(self):
        self.proof()
        self.ledger.release_hard_lock(self.receipt["resource"], self.receipt["owner"])
        with self.assertRaisesRegex(PortError, "cannot recreate"):
            recovery.recover(self.ledger, self.receipt)
        self.assertEqual(self.audit_rows(), [])
        self.assertEqual(self.ledger.hard_lock_records(), [])

    def test_stale_wrong_invocation_and_foreign_receipts_leave_all_bytes_unchanged(self):
        self.proof()
        for field, value in (("acquired", 124.0), ("owner", "foreign"),
                             ("repository", "another/repository"), ("record_state", "advisory")):
            with self.subTest(field=field), self.assertRaises(PortError):
                recovery.recover(self.ledger, {**self.receipt, field: value})
        wrong = copy.deepcopy(self.receipt)
        wrong["payload"]["token"] = "d" * 32
        wrong["owner"] = "local-check:" + "d" * 32
        with self.assertRaisesRegex(PortError, "stale or foreign"):
            recovery.recover(self.ledger, wrong)
        self.assertEqual(self.ledger.hard_lock_records(), [self.receipt])
        self.assertEqual(self.audit_rows(), [])

    def test_malformed_wrong_invocation_and_truncated_completion_refuse(self):
        for override in ({"token": "wrong"}, {"schema_version": True}, {"returncode": False},
                         {"status": "exited"}, {"unexpected": True}):
            with self.subTest(override=override), self.assertRaises(PortError):
                self.proof(**override)
                recovery.recover(self.ledger, self.receipt)
        self.completion.write_text("{", encoding="utf-8")
        with self.assertRaises(PortError):
            recovery.recover(self.ledger, self.receipt)
        self.assertEqual(self.ledger.hard_lock_records(), [self.receipt])

    def test_live_launcher_refuses_completion_and_cancel_before_transport(self):
        self.proof()
        with patch.object(recovery, "launcher_alive", return_value=True), \
                patch.object(recovery, "cancel_supervisor") as cancel, \
                self.assertRaisesRegex(PortError, "active"):
            recovery.recover(self.ledger, self.receipt)
        cancel.assert_not_called()
        self.completion.unlink()
        with patch.object(recovery, "launcher_alive", return_value=True), \
                patch.object(recovery, "cancel_supervisor") as cancel, \
                self.assertRaisesRegex(PortError, "active"):
            recovery.recover(self.ledger, self.receipt, cancel=True)
        cancel.assert_not_called()
        self.assertEqual(self.ledger.hard_lock_records(), [self.receipt])

    def test_wrong_admission_is_refused_before_any_signal(self):
        self.admission.write_text('{"schema_version": 1}', encoding="utf-8")
        with patch.object(recovery, "cancel_supervisor") as cancel, self.assertRaises(PortError):
            recovery.cancel_owned(self.receipt)
        cancel.assert_not_called()

    def test_default_recovery_never_cancels_live_descendants(self):
        with patch.object(recovery, "cancel_owned") as cancel, self.assertRaises(PortError):
            recovery.recover(self.ledger, self.receipt)
        cancel.assert_not_called()
        self.assertEqual(self.ledger.hard_lock_records(), [self.receipt])

    def test_recovery_does_not_remove_another_jvm_slot(self):
        self.ledger.release_hard_lock("gradle-execution", self.receipt["owner"])
        host = "f" * 64
        receipts = []
        with self.ledger.transaction() as connection:
            for slot in (0, 1):
                payload = {
                    **self.receipt["payload"],
                    "jvm_lane": {"host": host, "slot": slot, "capacity": 2},
                }
                connection.execute("INSERT INTO hard_locks VALUES (?,?,?,?,?)", (
                    self.ledger.repository, "gradle-execution:" + host + ":" + str(slot),
                    self.receipt["owner"], json.dumps(payload), 123.0,
                ))
        receipts = self.ledger.hard_lock_records()
        self.proof()
        recovery.recover(self.ledger, receipts[0])
        self.assertEqual(self.ledger.hard_lock_records(), [receipts[1]])

    def test_windows_handoff_failure_retains_original_ownership(self):
        if os.name != "nt":
            # Windows transport must not be mocked into a Linux lifecycle proof.
            with self.assertRaisesRegex(PortError, "entire original"):
                recovery.receipt_key({})
            return
        with patch.object(recovery, "launcher_alive", return_value=False), \
                patch.object(recovery.subprocess, "check_output",
                             side_effect=lambda command, **kwargs: "/mnt/c/" + Path(command[-1]).name), \
                patch.object(recovery.subprocess, "run",
                             side_effect=subprocess.CalledProcessError(1, ["test-wsl"])) as transport, \
                self.assertRaises(subprocess.CalledProcessError):
            recovery.recover(self.ledger, self.receipt, cancel=True, distribution="Fixture-Distro")
        command = transport.call_args.args[0]
        self.assertEqual(command[:4], ["wsl.exe", "--distribution", "Fixture-Distro", "--"])
        request = json.loads(transport.call_args.kwargs["input"])
        self.assertEqual(request["receipt"], self.receipt)
        self.assertEqual(request["admission"], "/mnt/c/admission.json")
        self.assertEqual(request["repository"], "/mnt/c/" + Path.cwd().name)
        self.assertEqual(request["windows_launcher_pid"], os.getpid())
        self.assertEqual(self.ledger.hard_lock_records(), [self.receipt])
        self.assertEqual(self.audit_rows(), [])

    def test_linux_handoff_requires_trusted_source_and_exited_windows_launcher(self):
        request = {
            "receipt": self.receipt, "admission": str(self.admission),
            "repository": str(self.root), "windows_launcher_pid": 1234,
        }
        with patch.object(recovery, "trusted_context", side_effect=PortError("candidate source")), \
                patch.object(recovery, "windows_launcher_alive") as launcher, \
                patch.object(recovery, "cancel_supervisor") as cancel, \
                patch("sys.stdin", io.StringIO(json.dumps(request))), \
                patch.object(recovery.sys, "argv", ["execution_recovery.py", "--linux-cancel"]), \
                contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(recovery.main(), 1)
        launcher.assert_not_called()
        cancel.assert_not_called()

        with patch.object(recovery, "trusted_context", return_value=self.ledger), \
                patch.object(recovery, "windows_launcher_alive", return_value=True), \
                patch.object(recovery, "cancel_supervisor") as cancel, \
                patch("sys.stdin", io.StringIO(json.dumps(request))), \
                patch.object(recovery.sys, "argv", ["execution_recovery.py", "--linux-cancel"]), \
                contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(recovery.main(), 1)
        cancel.assert_not_called()

        with patch.object(recovery, "trusted_context", return_value=self.ledger), \
                patch.object(recovery, "windows_launcher_alive", return_value=False), \
                patch.object(recovery, "cancel_supervisor") as cancel, \
                patch("sys.stdin", io.StringIO(json.dumps(request))):
            with patch.object(recovery.sys, "argv", ["execution_recovery.py", "--linux-cancel"]):
                self.assertEqual(recovery.main(), 0)
        cancel.assert_called_once()

    def test_windows_receipt_is_valid_in_linux_handoff_without_opening_windows_database(self):
        receipt = copy.deepcopy(self.receipt)
        receipt["payload"]["completion"] = r"C:\Private\meshcore-execution-fixture\cleanup.json"
        self.assertRegex(recovery.receipt_key(receipt), r"^[0-9a-f]{64}$")

    def test_check_delegates_to_existing_launcher_only_after_successful_recovery(self):
        self.proof()
        path = self.root / "receipt.json"
        path.write_text(json.dumps(self.receipt), encoding="utf-8")
        with patch.object(recovery, "trusted_context", return_value=self.ledger), \
                patch.object(recovery, "operator_identity", return_value={"principal": "fixture"}), \
                patch.object(recovery.subprocess, "run", return_value=subprocess.CompletedProcess([], 17)) as run, \
                contextlib.redirect_stdout(io.StringIO()):
            result = recovery.main([
                "--repo", str(self.root), "check", "--receipt", str(path),
                "--commit", "d" * 40, "--affected-base", "e" * 40,
            ])
        self.assertEqual(result, 17)
        self.assertEqual(run.call_args.args[0], [
            sys.executable, str(self.root / "tools/android-port/local/check.py"),
            "--commit", "d" * 40, "--affected-base", "e" * 40,
        ])
        self.assertEqual(self.ledger.hard_lock_records(), [])

    def test_adapter_supervisor_uses_existing_reviewed_direct_launch_path(self):
        local_path = Path(recovery.__file__).parents[1] / "local"
        sys.path.insert(0, str(local_path))
        import check

        with tempfile.TemporaryDirectory(prefix="adapter-recovery-") as directory:
            root = Path(directory)
            state = root / "adapter.json"
            state.write_text(json.dumps({"schema_version": 1, "adapter": ["fixture-adapter"]}))
            completion, admission = root / "cleanup.json", root / "admission.json"
            command = {
                "schema_version": 1, "interlock_version": 2, "owner": "local-check:" + self.token,
                "token": self.token, "commit": "b" * 40, "tree": "c" * 40,
            }
            admission.write_text(json.dumps(command))
            with patch.object(check, "binding", return_value=("b" * 40, "c" * 40)), \
                    patch.object(recovery.subprocess, "run") as run:
                # Import the exact supported adapter launcher and inspect its invocation path.
                with patch.object(check, "validate_result", return_value={"result": "success"}):
                    check.run_adapter(
                        root, "b" * 40, state, completion=completion, token=self.token,
                        admission=admission, execution={"started": False},
                    )
            supervisor = Path(check.__file__).with_name("advisory.py")
            self.assertEqual(
                recovery.supervisor_script(admission, supervisor),
                supervisor,
            )
            self.assertEqual(run.call_args.args[0][1], str(supervisor))

    def test_unproven_recovery_never_starts_another_check(self):
        path = self.root / "receipt.json"
        path.write_text(json.dumps(self.receipt), encoding="utf-8")
        with patch.object(recovery, "trusted_context", return_value=self.ledger), \
                patch.object(recovery.subprocess, "run") as run, \
                contextlib.redirect_stderr(io.StringIO()):
            result = recovery.main(["check", "--receipt", str(path), "--commit", "d" * 40])
        self.assertEqual(result, 1)
        run.assert_not_called()
        self.assertEqual(self.ledger.hard_lock_records(), [self.receipt])

    def test_candidate_recovery_source_cannot_authorize_itself(self):
        with patch.object(recovery.subprocess, "check_output", side_effect=[
            b"refs/remotes/origin/main\n", b"a" * 40 + b"\n", b"unreviewed mismatch\n",
        ]), self.assertRaisesRegex(PortError, "reviewed default-base"):
            recovery.trusted_context(self.root)

    def test_recovery_audit_is_rolled_back_when_row_removal_fails(self):
        self.proof()
        with self.ledger.transaction() as connection:
            connection.execute("""
                CREATE TRIGGER fixture_abort BEFORE DELETE ON hard_locks
                BEGIN SELECT RAISE(ABORT, 'fixture removal refused'); END
            """)
        import sqlite3
        with self.assertRaisesRegex(sqlite3.IntegrityError, "fixture removal refused"):
            recovery.recover(self.ledger, self.receipt)
        self.assertEqual(self.audit_rows(), [])
        self.assertEqual(self.ledger.hard_lock_records(), [self.receipt])


class SupervisorRecoveryTests(unittest.TestCase):
    def test_real_owned_cancel_or_actual_windows_rejection(self):
        if not sys.platform.startswith("linux"):
            with self.assertRaisesRegex(PortError, "actual supported Linux"):
                recovery.cancel_supervisor(Path("missing"), {}, Path("missing"))
            return
        with tempfile.TemporaryDirectory(prefix="owned-recovery-") as directory:
            root = Path(directory).resolve()
            control = root / "control"
            control.mkdir()
            advisory = Path(recovery.__file__).parents[1] / "local/advisory.py"
            token = "a" * 32
            admission, completion = root / "admission.json", root / "cleanup.json"
            receipt = {
                "repository": "fixture/repository", "resource": "gradle-execution",
                "owner": "local-check:" + token, "acquired": 123.0, "record_state": "hard",
                "payload": {
                    "commit": "b" * 40, "tree": "c" * 40, "pid": 2_147_483_647,
                    "token": token, "completion": str(completion), "mode": "manual",
                },
            }
            admission.write_text(json.dumps({
                "schema_version": 1, "interlock_version": 2, "owner": receipt["owner"],
                "token": token, "commit": "b" * 40, "tree": "c" * 40,
            }), encoding="utf-8")
            ready = root / "ready"
            child = (
                "import pathlib,subprocess,sys,time\n"
                "subprocess.Popen([sys.executable,'-c','import time; time.sleep(60)'],start_new_session=True)\n"
                "pathlib.Path(sys.argv[1]).touch()\n"
                "time.sleep(60)\n"
            )
            foreign = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(60)"])
            supervisor = subprocess.Popen([
                sys.executable, str(advisory),
                "--completion", str(completion), "--token", token, "--preserve-daemons",
                "--admission", str(admission), "--commit", "b" * 40, "--",
                sys.executable, "-c", child, str(ready),
            ])
            try:
                deadline = time.monotonic() + 10
                while not ready.exists() and time.monotonic() < deadline:
                    time.sleep(0.02)
                self.assertTrue(ready.is_file(), "Actual isolated supervisor did not start")
                wrong = copy.deepcopy(receipt)
                wrong["payload"]["token"] = "d" * 32
                with self.assertRaisesRegex(PortError, "Admission differs"):
                    recovery.cancel_supervisor(admission, wrong, advisory)
                self.assertIsNone(supervisor.poll())
                recovery.cancel_supervisor(admission, receipt, advisory)
                self.assertNotEqual(supervisor.wait(timeout=10), 0)
                self.assertEqual(recovery.cleanup_proof(receipt)["returncode"], -signal.SIGKILL)
                self.assertIsNone(foreign.poll(), "Recovery interfered with a foreign process")
                self.assertEqual(recovery.owned_pids(supervisor.pid), set())
            finally:
                try:
                    if supervisor.poll() is None:
                        # Even a failed cancellation fixture must reap its finite test-owned work.
                        try:
                            recovery.cancel_supervisor(admission, receipt, advisory)
                        finally:
                            supervisor.wait(timeout=70)
                finally:
                    foreign.terminate()
                    foreign.wait(timeout=5)
