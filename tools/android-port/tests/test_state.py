import copy
import json
import sqlite3
import tempfile
import unittest
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
from pathlib import Path
from contextlib import closing
from unittest.mock import Mock, patch

from fixtures import (
    BASE, HEAD, MERGE, NOW, REPO, FakeApi, FakeAuthority, FakeBackend, base_manifest, bundle,
    policy, settings, test_manifest,
)
from controller.authority import GitHubGateAuthority
from controller.backends import CloudBackend, GitHubApi, LocalHostBackend, NativeHost, Observation
from controller.engine import Controller, TerminalMerge
from controller.errors import PortError, ReceiptError
from controller.gates import policy_revision
from controller.ledger import Identity, Ledger
from controller.merged_acceptance import BoundedRepair
from controller.native_review import trusted_inputs
from controller.render import marker
from controller.schema import digest
from controller.settings import Budget, Settings, Usage


class SettingsTests(unittest.TestCase):
    def test_authorized_policy_unpause_does_not_activate_dispatch_or_change_receipt_semantics(self):
        from fixtures import REPO
        from controller.gates import policy_revision
        from controller.schema import load_json

        rules = load_json(REPO / "docs/android/automation-policy.json")
        self.assertIs(rules["paused"], False)
        self.assertEqual(rules["dispatch_mode"], "off")
        self.assertIs(rules["activation_approved"], False)
        before = {**rules, "paused": True}
        self.assertEqual(policy_revision(base_manifest(), rules), policy_revision(base_manifest(), before))
        configured = Settings.from_env({"ANDROID_PORT_PAUSED": "false"}, rules)
        self.assertFalse(configured.paused)
        with self.assertRaisesRegex(PortError, "dispatch is off.*activation is absent"):
            configured.require_live(rules, "local")
        self.assertTrue(Settings.from_env({}, rules).paused)

    def test_defaults_off_paused_and_missing_limits_auth_block_live(self):
        rules = policy()
        rules.update(dispatch_mode="off", paused=True, activation_approved=False)
        value = Settings.from_env({}, rules)
        self.assertEqual(value.mode, "off")
        self.assertTrue(value.paused)
        self.assertIsNone(value.budget)
        self.assertIsNone(value.max_inflight)
        with self.assertRaisesRegex(PortError, "concurrency.*usage.*authentication"):
            value.require_live(rules, "cloud")

    def test_environment_cannot_override_trusted_pause_or_activation(self):
        rules = policy()
        rules["paused"] = True
        value = Settings.from_env({"ANDROID_PORT_DISPATCH_MODE": "cloud", "ANDROID_PORT_PAUSED": "false"}, rules)
        self.assertTrue(value.paused)
        with self.assertRaises(PortError):
            value.require_live(rules, "cloud")

    def test_invalid_settings_fail_instead_of_silent_defaults(self):
        for environment in (
            {"ANDROID_PORT_PAUSED": "maybe"}, {"ANDROID_PORT_DISPATCH_MODE": "all"},
            {"ANDROID_PORT_MAX_INFLIGHT": "0"}, {"ANDROID_PORT_MAX_INFLIGHT": "-1"},
            {"ANDROID_PORT_MAX_INFLIGHT": "many"}, {"ANDROID_PORT_LEASE_SECONDS": "0"},
            {"ANDROID_PORT_LEDGER": "relative.sqlite"},
        ):
            with self.subTest(environment=environment), self.assertRaises(PortError):
                Settings.from_env(environment, policy())

    def test_missing_budget_file_and_invalid_budget_policy_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "missing.json"
            with self.assertRaises(PortError):
                Settings.from_env({"ANDROID_PORT_USAGE_POLICY_FILE": str(path)}, policy())
        for value in (
            {}, {"unit": "launches", "limit": 1, "reserve_per_launch": 1, "window_start": "1970-01-01T00:00:00Z"},
            {"unit": "ai_credits", "limit": 0, "reserve_per_launch": 1, "window_start": "1970-01-01T00:00:00Z"},
            {"unit": "ai_credits", "limit": True, "reserve_per_launch": 1, "window_start": "1970-01-01T00:00:00Z"},
            {"unit": "ai_credits", "limit": 1, "reserve_per_launch": 0, "window_start": "1970-01-01T00:00:00Z"},
            {"unit": "ai_credits", "limit": 1, "reserve_per_launch": 1, "window_start": "1970-01-01"},
        ):
            with self.subTest(value=value), self.assertRaises(PortError):
                Budget.parse(value)

    def test_stale_incomplete_nonfinite_wrong_unit_usage_blocks(self):
        budget = Budget("ai_credits", 100, 5, 0)
        for value in (
            Usage("premium_requests", 0, NOW, True, (), ("local",)),
            Usage("ai_credits", 0, NOW, False, (), ("local",)),
            Usage("ai_credits", float("nan"), NOW, True, (), ("local",)),
            Usage("ai_credits", -1, NOW, True, (), ("local",)),
            Usage("ai_credits", 0, NOW - 301, True, (), ("local",)),
            Usage("ai_credits", 0, NOW + 1, True, (), ("local",)),
            Usage("ai_credits", 0, NOW, True, ("same", "same"), ("local",)),
            Usage("ai_credits", 0, NOW, True, ()),
        ):
            with self.subTest(value=value), self.assertRaises(PortError):
                value.validate(budget, NOW)
        Usage("ai_credits", 5, NOW, True, ("task-1",), ("cloud",)).validate(budget, NOW)


class StateTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="meshcore-port-tests-")
        self.addCleanup(self.temporary.cleanup)
        self.path = Path(self.temporary.name) / "ledger.sqlite"
        self.manifest = test_manifest("WP-101")
        self.policy = policy()
        self.settings = settings(self.path)
        self.ledger = Ledger(self.path, self.policy["repository"])
        self.backend = FakeBackend()
        self.controller = Controller(
            self.manifest, self.policy, self.settings, self.ledger, self.backend,
            attempt_factory=lambda: "attempt-1",
        )

    def claim(self, wp_id="WP-101", **values):
        return self.ledger.claim(
            self.manifest.wp(wp_id), self.controller.binding(wp_id, BASE), "local",
            values.get("attempt", "attempt-1"), values.get("now", NOW), values.get("lease_seconds", 1800),
            values.get("max_inflight", 5), values.get("used", 0), values.get("limit", 100),
            values.get("reservation", 5), values.get("external_active_ids", ()),
        )

    def running(self):
        self.claim()
        identity = Identity(session_id="session-1", pr_number=123)
        self.ledger.transition("WP-101", "attempt-1", "dispatching")
        self.ledger.transition("WP-101", "attempt-1", "running", identity)
        self.backend.observation = Observation(identity, "in_progress", True, True)
        return identity

    def reviewed(self):
        identity = self.running()
        self.backend.observation = Observation(identity, "completed", True, True)
        self.controller.observe("WP-101", "attempt-1", self.backend.observation)
        return identity

    def test_atomic_claim_idempotency_and_binding_mismatch(self):
        first, acquired = self.claim()
        self.assertTrue(acquired)
        second, acquired = self.claim(attempt="different-proposed-attempt")
        self.assertFalse(acquired)
        self.assertEqual(first["attempt"], second["attempt"])
        binding = self.controller.binding("WP-101", HEAD)
        with self.assertRaisesRegex(PortError, "different"):
            self.ledger.claim(self.manifest.wp("WP-101"), binding, "local", "new", NOW, 1800, 5, 0, 100, 5)

    def test_concurrent_overlapping_claims_are_advisory_and_both_acquire(self):
        shared = ["docs/android/port-manifest.json", "android/gradle/"]

        def acquire(wp_id):
            wp = {**self.manifest.wp(wp_id), "write_paths": shared}
            try:
                self.ledger.claim(wp, self.controller.binding(wp_id, BASE), "local", wp_id, NOW, 1800, 5, 0, 100, 5)
                return "acquired"
            except PortError:
                return "conflict"

        with ThreadPoolExecutor(max_workers=2) as executor:
            results = list(executor.map(acquire, ("WP-101", "WP-102")))
        self.assertCountEqual(results, ["acquired", "acquired"])
        self.assertEqual(len(self.ledger.overlap_report()), 1)

    def test_expiry_retains_locks_and_marks_uncertainty(self):
        self.claim(lease_seconds=10)
        with self.assertRaisesRegex(PortError, "Expired"):
            self.claim(now=NOW + 11)
        self.assertEqual(self.ledger.get("WP-101")["state"], "uncertain")
        with self.assertRaises(PortError):
            self.claim(now=NOW + 12)
        self.assertEqual(len(self.ledger.records()), 1)

    def test_concurrency_and_usage_reservations_block(self):
        self.claim(max_inflight=1)
        for options in ({"max_inflight": 1}, {"used": 96, "limit": 100}):
            with self.subTest(options=options), self.assertRaises(PortError):
                self.claim("WP-102", **options)
        self.assertEqual(len(self.ledger.records()), 1)

    def test_unknown_state_and_generic_completion_or_release_cannot_bypass(self):
        self.claim()
        for state in ("invented", "completed", "pending", "leased"):
            with self.subTest(state=state), self.assertRaises(PortError):
                self.ledger.transition("WP-101", "attempt-1", state)
        with closing(sqlite3.connect(self.path)) as connection:
            with connection:
                connection.execute("UPDATE leases SET state='invented'")
        with self.assertRaisesRegex(PortError, "Unknown persisted"):
            self.ledger.records()

    def test_identity_conflicts_are_not_replacements(self):
        identity = Identity(issue_number=1, task_id="task-1", session_id="session-1", pr_number=123)
        for other in (
            Identity(issue_number=2), Identity(task_id="task-2"),
            Identity(session_id="session-2"), Identity(pr_number=124),
        ):
            with self.subTest(other=other), self.assertRaises(PortError):
                identity.reconcile(other)
        self.assertEqual(Identity(issue_number=1).reconcile(Identity(pr_number=123)).pr_number, 123)

    def test_launch_records_identity_and_restart_reuses_worker(self):
        first = self.controller.launch("WP-101", NOW)
        second = self.controller.launch("WP-101", NOW)
        self.assertEqual(first["action"], "worker-receipt-confirmed")
        self.assertEqual(second["action"], "reconciled-existing-worker")
        self.assertEqual(len(self.backend.launches), 1)
        reopened = Ledger(self.path, self.policy["repository"])
        restarted = Controller(self.manifest, self.policy, self.settings, reopened, self.backend)
        restarted.launch("WP-101", NOW)
        self.assertEqual(len(self.backend.launches), 1)

    def test_unknown_external_worker_scope_blocks_new_claim(self):
        self.backend.usage_value = Usage("ai_credits", 0, NOW, True, ("unleased-worker",), ("local",))
        with self.assertRaisesRegex(PortError, "Unleased/unknown"):
            self.controller.launch("WP-101", NOW)
        self.assertFalse(self.backend.launches)
        self.assertFalse(self.ledger.records())

    def test_adopt_existing_worker_at_concurrency_limit_does_not_launch(self):
        identity = Identity(session_id="session-existing", pr_number=123)
        self.backend.observation = Observation(identity, "in_progress", True, True)
        self.backend.usage_value = Usage("ai_credits", 0, NOW, True, ("session-existing",), ("local",))
        self.controller.settings = replace(self.settings, max_inflight=1)
        result = self.controller.launch("WP-101", NOW)
        self.assertEqual(result["action"], "reused-existing-worker")
        self.assertFalse(self.backend.launches)
        self.assertEqual(self.ledger.get("WP-101")["identity"]["session_id"], "session-existing")

    def test_failed_launch_retains_intent_and_never_retries_ambiguous_outcome(self):
        self.backend.failure = PortError("fake transport timeout")
        with self.assertRaises(PortError):
            self.controller.launch("WP-101", NOW)
        self.assertEqual(self.ledger.get("WP-101")["state"], "uncertain")
        self.backend.failure = None
        with self.assertRaises(PortError):
            self.controller.launch("WP-101", NOW)
        self.assertEqual(len(self.backend.launches), 1)

    def test_failed_post_launch_verification_keeps_actual_returned_identity(self):
        self.backend.failure = ReceiptError("session started from unexpected base", Identity(session_id="actual-created-session"))
        with self.assertRaises(ReceiptError):
            self.controller.launch("WP-101", NOW)
        record = self.ledger.get("WP-101")
        self.assertEqual(record["state"], "uncertain")
        self.assertEqual(record["identity"]["session_id"], "actual-created-session")

    def test_cross_backend_history_requires_an_explicit_complete_aggregate_meter(self):
        self.claim("WP-102")
        with self.ledger.transaction() as connection:
            connection.execute("UPDATE leases SET backend='cloud'")
        self.backend.usage_value = Usage("ai_credits", 0, NOW, True, (), ("local",))
        with self.assertRaisesRegex(PortError, "Cross-backend"):
            self.controller.claim("WP-101", NOW)
        self.assertFalse(self.backend.launches)

    def test_merged_dependency_with_reconciled_evidence_unlocks_not_a_closed_issue(self):
        self.manifest.wp("WP-101")["depends_on"] = ["WP-004"]
        self.manifest.wp("WP-004")["verification"] = {
            "configured": True, "commands": [["python", "fixture-only"]], "blocker": "",
        }
        value = bundle(self.manifest, self.policy, "WP-004")
        self.controller.authority = FakeAuthority({"WP-004": value})
        self.claim("WP-004", attempt="dependency-attempt")
        identity = Identity(session_id="dependency-session", pr_number=123)
        self.ledger.transition("WP-004", "dependency-attempt", "dispatching")
        self.ledger.transition("WP-004", "dependency-attempt", "review_wait", identity)
        from controller.gates import verify_completion

        proof = verify_completion(value.pr, value.evidence, value.review, value.approvals,
                                  value.binding, self.manifest, self.policy, value.catalog)
        self.ledger.complete("WP-004", "dependency-attempt", proof, True)
        self.controller.launch("WP-101", NOW)
        self.assertEqual(len(self.backend.launches), 1)
        self.controller.authority = FakeAuthority({"WP-004": replace(value, pr={**value.pr, "merged": False})})
        with self.assertRaises(PortError):
            self.controller.launch("WP-101", NOW)
        self.assertEqual(len(self.backend.launches), 1)

    def test_authoritative_receipt_recovers_unknown_prior_launch_without_duplicate(self):
        self.backend.failure = PortError("response lost")
        with self.assertRaises(PortError):
            self.controller.launch("WP-101", NOW)
        self.backend.observation = Observation(Identity(session_id="actual-session", pr_number=123), "in_progress", True, True)
        result = self.controller.launch("WP-101", NOW)
        self.assertEqual(result["action"], "reconciled-existing-worker")
        self.assertEqual(self.ledger.get("WP-101")["state"], "running")
        self.assertEqual(len(self.backend.launches), 1)

    def test_assignment_confirmation_is_not_a_fabricated_launch(self):
        class AssignmentBackend(FakeBackend):
            name = "cloud"

            def launch(inner, wp_id, attempt):
                inner.launches.append((wp_id, attempt))
                return Observation(Identity(issue_number=7), "assignment_accepted", False, True)

        rules = policy("cloud")
        backend = AssignmentBackend()
        controller = Controller(self.manifest, rules, settings(self.path, "cloud"), self.ledger, backend)
        result = controller.launch("WP-101", NOW)
        self.assertEqual(result["action"], "assignment-accepted-task-unconfirmed")
        self.assertEqual(self.ledger.get("WP-101")["state"], "dispatching")
        self.assertIsNone(result["identity"]["task_id"])

    def test_idle_draft_or_completed_task_without_pr_is_not_completion(self):
        identity = self.running()
        self.controller.observe("WP-101", "attempt-1", Observation(identity, "idle", True, True))
        self.assertEqual(self.ledger.get("WP-101")["state"], "running")
        with self.assertRaises(PortError):
            self.controller.completed("WP-101")
        with self.assertRaisesRegex(PortError, "no implementation PR"):
            self.controller.observe("WP-101", "attempt-1", Observation(Identity(session_id="session-1"), "completed", False, True))
        self.assertNotEqual(self.ledger.get("WP-101")["state"], "completed")

    def test_supervised_and_missing_prerequisites_cannot_auto_dispatch(self):
        for wp_id in ("WP-000", "WP-001", "WP-002", "WP-003", "WP-102"):
            with self.subTest(wp_id=wp_id), self.assertRaises(PortError):
                self.controller.launch(wp_id, NOW)
        self.assertFalse(self.backend.launches)

    def test_empty_verification_entry_uses_shared_lane_without_waiving_dependencies(self):
        with patch.object(self.controller, "completed") as completed:
            wp = self.controller.require_ready("WP-312")
            self.assertFalse(wp["verification"]["configured"])
            self.assertFalse(wp["verification"]["commands"])
            self.assertEqual(completed.call_count, len(wp["depends_on"]))
        self.manifest.wp("WP-003")["verification"]["commands"] = []
        with self.assertRaisesRegex(PortError, "software verification lane"):
            self.controller.require_ready("WP-312")
        with self.assertRaisesRegex(PortError, "supervised"):
            self.controller.require_ready("WP-003")

    def test_explicit_partial_software_lane_does_not_remove_substantive_blocker(self):
        with patch.object(self.controller, "completed"):
            with self.assertRaisesRegex(PortError, "approved bounded split"):
                self.controller.require_ready("WP-202")

    def test_empty_verification_does_not_block_work_before_actual_dependency_checks(self):
        with patch.object(self.controller, "completed"):
            for wp_id in ("WP-004", "WP-006", "WP-503", "WP-505", "WP-506"):
                with self.subTest(wp_id=wp_id):
                    self.assertEqual(self.controller.require_ready(wp_id)["id"], wp_id)

    def test_empty_source_ownership_does_not_create_a_separate_dispatch_gate(self):
        with patch.object(self.controller, "completed"):
            self.assertEqual(self.controller.require_ready("WP-407")["id"], "WP-407")
            self.assertEqual(self.controller.require_ready("WP-102")["id"], "WP-102")

    def test_missing_dependency_is_not_replaced_by_closed_issue(self):
        self.manifest.wp("WP-101")["depends_on"] = ["WP-004"]
        with self.assertRaisesRegex(PortError, "no verified merged-PR"):
            self.controller.launch("WP-101", NOW)
        self.assertFalse(self.backend.launches)

    def test_pause_missing_budget_or_auth_prevents_any_backend_action(self):
        for kind in ("pause", "budget", "auth"):
            rules = copy.deepcopy(self.policy)
            value = replace(self.settings, mode="cloud")
            backend = FakeBackend()
            backend.name = "cloud"
            if kind == "pause":
                rules["paused"] = True
            elif kind == "budget":
                value = replace(value, budget=None)
            else:
                value = replace(value, auth_kind="installation", token=None)
            controller = Controller(self.manifest, rules, value, self.ledger, backend)
            with self.subTest(kind=kind), self.assertRaises(PortError):
                controller.launch("WP-101", NOW)
            self.assertFalse(backend.launches)
            self.assertFalse(backend.preflights)

    def test_release_requires_actual_terminal_identity_and_no_open_pr_even_when_paused(self):
        identity = self.running()
        for observation in (
            Observation(identity, "idle", False, True),
            Observation(identity, "completed", True, True),
            Observation(identity, "failed", False, False),
            Observation(Identity(session_id="wrong"), "failed", False, True),
        ):
            self.backend.observation = observation
            with self.subTest(observation=observation), self.assertRaises(PortError):
                self.controller.release("WP-101")
        self.policy["paused"] = True
        self.backend.observation = Observation(identity, "failed", False, True)
        self.controller.release("WP-101")
        self.assertEqual(self.ledger.get("WP-101")["state"], "pending")

    def test_bounded_repairs_reuse_identity_and_pending_repair_blocks_another(self):
        identity = self.reviewed()
        for round_number in range(1, 4):
            result = self.controller.repair("WP-101", f"bounded fix {round_number}", NOW)
            self.assertEqual(result["round"], round_number)
            self.assertEqual(self.backend.repairs[-1][0], identity)
            with self.assertRaises(PortError):
                self.controller.repair("WP-101", "not another while pending", NOW)
            self.controller.observe("WP-101", "attempt-1", self.backend.observation)
        with self.assertRaisesRegex(PortError, "needs-human"):
            self.controller.repair("WP-101", "fourth", NOW)
        self.assertEqual(len(self.backend.repairs), 3)
        self.assertEqual(self.ledger.get("WP-101")["state"], "blocked")

    def test_pause_and_budget_block_repairs_without_delivery(self):
        self.reviewed()
        self.policy["paused"] = True
        with self.assertRaises(PortError):
            self.controller.repair("WP-101", "fix", NOW)
        self.policy["paused"] = False
        self.backend.usage_value = Usage("ai_credits", 96, NOW, True, (), ("local",))
        with self.assertRaises(PortError):
            self.controller.repair("WP-101", "fix", NOW)
        self.assertFalse(self.backend.repairs)

    def test_current_base_repair_rebinding_preserves_worker_and_invalidates_old_binding(self):
        identity = self.reviewed()
        previous = self.ledger.get("WP-101")["binding"]
        self.backend.base = HEAD
        self.controller.repair("WP-101", "rebase same worker", NOW)
        current = self.ledger.get("WP-101")
        self.assertEqual(current["binding"]["base_sha"], HEAD)
        self.assertNotEqual(current["binding"], previous)
        self.assertEqual(current["identity"], identity.as_dict())
        self.assertIsNone(current["completion"])

    def prepare_successor(self):
        self.claim()
        previous_identity = Identity(session_id="previous-session", pr_number=123)
        self.ledger.transition("WP-101", "attempt-1", "dispatching")
        self.ledger.transition("WP-101", "attempt-1", "running", previous_identity)
        self.ledger.transition("WP-101", "attempt-1", "review_wait")
        paths = list(self.manifest.wp("WP-101")["write_paths"])
        _, created = self.ledger.reconcile_merged_intent(
            "previous-session", 123, HEAD, MERGE, paths, NOW,
        )
        self.assertTrue(created)
        self.backend.base = HEAD
        self.backend.observation = Observation(previous_identity, "completed", False, True)
        authority = FakeAuthority({})
        authority.terminal = TerminalMerge(
            self.policy["repository"], "WP-101", "previous-session", 123,
            BASE, HEAD, MERGE, tuple(sorted(paths)), True,
        )
        self.controller.authority = authority
        return previous_identity, paths

    def test_successor_admission_archives_predecessor_and_is_idempotent(self):
        self.prepare_successor()
        self.assert_successor_history_and_replay()

    def test_completed_predecessor_successor_preserves_completion_history_and_replays(self):
        previous_identity, _ = self.prepare_successor()
        self.controller.authority.bundles["WP-101"] = bundle(
            self.manifest, self.policy, "WP-101",
        )
        proof = self.controller.record_completion("WP-101")
        previous = self.ledger.get("WP-101")
        self.assertEqual(previous["state"], "completed")
        self.assertEqual(previous["completion"], proof)
        identity = Identity(session_id="successor-session")
        self.backend.observation = Observation(previous_identity, "in_progress", False, True)
        with self.assertRaisesRegex(PortError, "active"):
            self.controller.admit_successor_attempt(
                "WP-101", "attempt-1", previous["revision"], identity, NOW + 1,
            )
        self.backend.observation = Observation(previous_identity, "completed", False, True)
        terminal = self.controller.authority.terminal
        self.controller.authority.terminal = replace(terminal, authoritative=False)
        with self.assertRaisesRegex(PortError, "disagree"):
            self.controller.admit_successor_attempt(
                "WP-101", "attempt-1", previous["revision"], identity, NOW + 1,
            )
        self.controller.authority.terminal = terminal
        self.assertEqual(self.ledger.get("WP-101"), previous)
        self.assertEqual(self.ledger.historical_records("WP-101"), [])
        self.assert_successor_history_and_replay()
        self.assertEqual(self.ledger.get_attempt("WP-101", "attempt-1")["completion"], proof)

    def assert_successor_history_and_replay(self):
        previous = self.ledger.get("WP-101")
        receipt_before = self.ledger.terminal_receipt("previous-session", 123)
        with self.ledger.transaction() as connection:
            previous_bytes = tuple(connection.execute(
                "SELECT * FROM leases WHERE repository=? AND wp=?",
                (self.policy["repository"], "WP-101"),
            ).fetchone())
            receipt_bytes = tuple(connection.execute(
                "SELECT * FROM intent_reconciliations WHERE repository=? AND session_id=? AND pr_number=?",
                (self.policy["repository"], "previous-session", 123),
            ).fetchone())
        successor_identity = Identity(session_id="successor-session")
        first, created = self.controller.admit_successor_attempt(
            "WP-101", "attempt-1", previous["revision"], successor_identity, NOW + 1,
        )
        self.assertTrue(created)
        self.assertEqual(first["record_state"], "advisory-successor")
        self.assertEqual(first["binding"]["base_sha"], HEAD)
        self.assertEqual(first["binding"]["source_sha"], self.manifest.data["reference"]["commit"])
        self.assertEqual(first["binding"], self.controller.binding("WP-101", HEAD))
        self.assertEqual(first["backend"], self.backend.name)
        self.assertEqual(first["identity"], successor_identity.as_dict())
        self.assertEqual(first["predecessor_attempt"], "attempt-1")
        self.assertEqual(first["predecessor_receipt_sha256"], digest(receipt_before))
        self.assertEqual(self.ledger.get("WP-101")["attempt"], first["attempt"])
        self.assertEqual(self.ledger.get_attempt("WP-101", "attempt-1"), previous)
        self.assertEqual(self.ledger.historical_records("WP-101"), [previous])
        self.assertEqual(
            self.ledger.terminal_receipt("previous-session", 123), receipt_before,
        )
        with self.ledger.transaction() as connection:
            archived_bytes = tuple(connection.execute(
                "SELECT * FROM lease_history WHERE repository=? AND wp=? AND attempt=?",
                (self.policy["repository"], "WP-101", "attempt-1"),
            ).fetchone())
            preserved_receipt_bytes = tuple(connection.execute(
                "SELECT * FROM intent_reconciliations WHERE repository=? AND session_id=? AND pr_number=?",
                (self.policy["repository"], "previous-session", 123),
            ).fetchone())
        self.assertEqual(archived_bytes, previous_bytes)
        self.assertEqual(preserved_receipt_bytes, receipt_bytes)
        replay, created = self.controller.admit_successor_attempt(
            "WP-101", "attempt-1", previous["revision"], successor_identity, NOW + 2,
        )
        self.assertFalse(created)
        self.assertEqual(replay, first)

    def test_successor_admission_rejects_active_worker_stale_proof_and_identity_collisions(self):
        previous_identity, _ = self.prepare_successor()
        previous = self.ledger.get("WP-101")
        successor_identity = Identity(session_id="successor-session")
        self.backend.observation = Observation(previous_identity, "in_progress", True, True)
        with self.assertRaisesRegex(PortError, "active"):
            self.controller.admit_successor_attempt(
                "WP-101", "attempt-1", previous["revision"], successor_identity, NOW + 1,
            )
        self.backend.observation = Observation(previous_identity, "completed", False, True)
        with self.assertRaisesRegex(PortError, "stale"):
            self.controller.admit_successor_attempt(
                "WP-101", "attempt-1", previous["revision"] + 1, successor_identity, NOW + 1,
            )

        foreign = Identity(session_id="foreign-session")
        wp = self.manifest.wp("WP-102")
        self.ledger.claim(
            wp, self.controller.binding("WP-102", BASE), "local", "other-attempt",
            NOW, 1800, 5, 0, 100, 5,
        )
        self.ledger.transition("WP-102", "other-attempt", "dispatching")
        self.ledger.transition("WP-102", "other-attempt", "running", foreign)
        with self.assertRaisesRegex(PortError, "collides"):
            self.controller.admit_successor_attempt(
                "WP-101", "attempt-1", previous["revision"], foreign, NOW + 1,
            )

    def test_successor_admission_rejects_current_base_and_foreign_terminal_collisions(self):
        previous_identity, _ = self.prepare_successor()
        previous = self.ledger.get("WP-101")
        identity = Identity(session_id="successor-session")
        first, created = self.controller.admit_successor_attempt(
            "WP-101", "attempt-1", previous["revision"], identity, NOW + 1,
        )
        self.assertTrue(created)
        self.backend.base = "4" * 40
        with self.assertRaisesRegex(PortError, "different identity or binding"):
            self.controller.admit_successor_attempt(
                "WP-101", "attempt-1", previous["revision"], identity, NOW + 1,
            )
        self.backend.base = HEAD
        self.policy["repair_limit"] += 1
        with self.assertRaisesRegex(PortError, "different identity or binding"):
            self.controller.admit_successor_attempt(
                "WP-101", "attempt-1", previous["revision"], identity, NOW + 1,
            )
        self.policy["repair_limit"] -= 1
        self.controller.authority.terminal = TerminalMerge(
            self.policy["repository"], "WP-102", "previous-session", 123,
            BASE, HEAD, MERGE, tuple(sorted(self.manifest.wp("WP-101")["write_paths"])), True,
        )
        with self.assertRaisesRegex(PortError, "disagree"):
            self.controller.admit_successor_attempt(
                "WP-101", "attempt-1", previous["revision"], identity, NOW + 1,
            )
        self.assertEqual(self.ledger.get("WP-101"), first)
        self.assertEqual(self.ledger.historical_records("WP-101"), [previous])

    def test_successor_admission_rolls_back_archive_and_current_update_atomically(self):
        previous_identity, _ = self.prepare_successor()
        previous = self.ledger.get("WP-101")
        with self.ledger.transaction() as connection:
            connection.execute("""
                CREATE TRIGGER reject_successor_receipt
                BEFORE INSERT ON successor_transitions
                BEGIN SELECT RAISE(ABORT, 'forced successor rollback'); END
            """)
        with self.assertRaises(sqlite3.IntegrityError):
            self.controller.admit_successor_attempt(
                "WP-101", "attempt-1", previous["revision"],
                Identity(session_id="successor-session"), NOW + 1,
            )
        self.assertEqual(self.ledger.get("WP-101"), previous)
        self.assertEqual(self.ledger.historical_records("WP-101"), [])
        self.assertEqual(self.ledger.terminal_receipt("previous-session", 123)["merge_sha"], MERGE)
        with self.ledger.transaction() as connection:
            connection.execute("DROP TRIGGER reject_successor_receipt")

    def test_concurrent_successor_admission_replays_one_atomic_transition(self):
        self.prepare_successor()
        previous = self.ledger.get("WP-101")
        identity = Identity(session_id="successor-session")

        def admit(_):
            return self.controller.admit_successor_attempt(
                "WP-101", "attempt-1", previous["revision"], identity, NOW + 1,
            )

        with ThreadPoolExecutor(max_workers=2) as executor:
            results = list(executor.map(admit, range(2)))
        self.assertCountEqual([created for _, created in results], [True, False])
        self.assertEqual(len(self.ledger.historical_records("WP-101")), 1)
        self.assertEqual(self.ledger.get("WP-101")["identity"], identity.as_dict())

    def test_completion_requires_authoritative_bundle_actual_finished_worker_and_merged_evidence(self):
        identity = self.reviewed()
        value = bundle(self.manifest, self.policy, "WP-101")
        self.controller.authority = FakeAuthority({"WP-101": replace(value, authoritative=False)})
        with self.assertRaises(PortError):
            self.controller.record_completion("WP-101")
        self.controller.authority = FakeAuthority({"WP-101": replace(value, pr={**value.pr, "merged": False})})
        with self.assertRaises(PortError):
            self.controller.record_completion("WP-101")
        self.controller.authority = FakeAuthority({"WP-101": value})
        self.backend.observation = Observation(identity, "idle", True, True)
        with self.assertRaises(PortError):
            self.controller.record_completion("WP-101")
        self.backend.observation = Observation(identity, "completed", False, True)
        proof = self.controller.record_completion("WP-101")
        self.assertEqual(self.ledger.get("WP-101")["state"], "completed")
        self.assertEqual(self.controller.completed("WP-101"), proof)
        self.controller.authority = FakeAuthority({"WP-101": replace(value, pr={**value.pr, "merged": False})})
        with self.assertRaises(PortError):
            self.controller.completed("WP-101")

    def test_accepted_implementation_does_not_release_active_or_ambiguous_repair(self):
        identity = self.running()
        binding = {**self.controller.binding("WP-101", BASE), "head_sha": HEAD}
        proof = {
            "kind": "merged-implementation", "binding": binding, "pr_number": 122,
            "merge_commit_sha": MERGE, "merge_parent_sha": BASE,
            "ci": {"run_id": 1, "run_attempt": 1, "check_id": 100},
            "ci_run_ids": [1], "review_ids": [], "approval_id": None,
        }
        self.assertEqual(self.ledger.remember_completion("WP-101", proof, True), proof)
        authority = Mock()
        authority.confirm_merged.return_value = proof
        repair = {**proof, "kind": "merged-scope", "pr_number": 123}
        authority.merged_scope.return_value = repair
        self.controller.authority = authority
        self.assertEqual(self.controller.completed("WP-101"), proof)
        for observation in (
            Observation(identity, "in_progress", True, True),
            Observation(identity, "completed", False, True, ambiguous=True),
            Observation(identity, "completed", True, True),
        ):
            self.backend.observation = observation
            with self.subTest(observation=observation), self.assertRaises(PortError):
                self.controller.record_completion("WP-101")
            self.assertEqual(self.ledger.get("WP-101")["state"], "running")
            self.assertEqual(self.ledger.accepted_completion("WP-101"), proof)
        authority.merged_scope.assert_not_called()
        self.backend.observation = Observation(identity, "completed", False, True)
        self.assertEqual(self.controller.record_completion("WP-101"), proof)
        self.assertEqual(self.ledger.get("WP-101")["state"], "pending")
        self.assertEqual(self.ledger.accepted_completion("WP-101"), proof)
        authority.merged_scope.assert_called_once_with("WP-101", identity)

    def test_merged_bounded_repair_releases_lease_without_completing_wp_or_dependency(self):
        identity = self.running()
        record = self.ledger.get("WP-101")
        binding = {**record["binding"], "head_sha": HEAD}
        scope = {
            "kind": "merged-scope", "binding": binding, "pr_number": identity.pr_number,
            "merge_commit_sha": MERGE, "ci_run_ids": [1], "approval_id": None,
        }

        class Authority:
            def merged_implementation(self, *_):
                raise BoundedRepair("merged PR declares a bounded repair")

            def merged_scope(self, wp_id, actual_identity):
                self.request = (wp_id, actual_identity)
                return scope

        self.controller.authority = Authority()
        self.backend.observation = Observation(identity, "completed", False, True)
        self.assertEqual(self.controller.record_completion("WP-101"), scope)
        self.assertEqual(self.ledger.get("WP-101")["state"], "pending")
        self.assertEqual(self.ledger.get("WP-101")["reservation"], 0)
        self.assertIsNone(self.ledger.accepted_completion("WP-101"))
        with self.ledger.transaction() as connection:
            operation = connection.execute(
                "SELECT state,identity FROM operations WHERE key='merge-lane'",
            ).fetchone()
        self.assertEqual(operation["state"], "confirmed")
        self.assertEqual(json.loads(operation["identity"]), {"merged": True, "sha": MERGE})
        self.manifest.wp("WP-102")["depends_on"] = ["WP-101"]
        with self.assertRaises(BoundedRepair):
            self.controller.require_ready("WP-102")

    def test_merged_bounded_repair_recovers_matching_merge_intent_before_beginning_another(self):
        identity = self.running()
        observation = Observation(identity, "completed", False, True)
        self.backend.observation = observation
        self.controller.observe("WP-101", "attempt-1", observation)
        record = self.ledger.get("WP-101")
        binding = {**record["binding"], "head_sha": HEAD}
        scope = {
            "kind": "merged-scope", "binding": binding, "pr_number": identity.pr_number,
            "merge_commit_sha": MERGE, "ci_run_ids": [1], "approval_id": None,
        }
        payload = {
            "work_package": "WP-101", "pr_number": identity.pr_number, "action": "merge",
            "attempt": record["attempt"], "reservation_revision": record["revision"],
            "binding": record["binding"], "expected_head_sha": HEAD, "base_sha": BASE,
            "ci_run_ids": [1], "approval_id": None,
            "policy_revision": record["binding"]["policy_revision"],
        }
        self.ledger.begin_merge(payload)
        self.ledger.transition("WP-101", "attempt-1", "merge_wait")

        class Authority:
            def merged_implementation(self, *_):
                raise BoundedRepair("merged PR declares a bounded repair")

            def merged_scope(self, wp_id, actual_identity):
                self.request = (wp_id, actual_identity)
                return scope

        self.controller.authority = Authority()
        with patch.object(self.ledger, "begin_merge", wraps=self.ledger.begin_merge) as begin_merge:
            self.controller.record_completion("WP-101")
        begin_merge.assert_not_called()
        self.assertEqual(self.ledger.get("WP-101")["state"], "pending")
        self.assertIsNone(self.ledger.accepted_completion("WP-101"))
        with self.ledger.transaction() as connection:
            operation = connection.execute(
                "SELECT state,identity FROM operations WHERE key='merge-lane'",
            ).fetchone()
        self.assertEqual(operation["state"], "confirmed")
        self.assertEqual(json.loads(operation["identity"]), {"merged": True, "sha": MERGE})

    def test_accepted_completion_rejects_malformed_or_conflicting_proofs(self):
        binding = {**self.controller.binding("WP-101", BASE), "head_sha": HEAD}
        proof = {
            "kind": "merged-implementation", "binding": binding, "pr_number": 123,
            "merge_commit_sha": MERGE, "merge_parent_sha": BASE,
            "ci": {"run_id": 1, "run_attempt": 1, "check_id": 100},
            "ci_run_ids": [1], "review_ids": [], "approval_id": None,
        }
        for invalid in (
            {**proof, "kind": "merged-scope"}, {**proof, "merge_parent_sha": "main"},
            {**proof, "ci": {"run_id": True, "run_attempt": 1, "check_id": 100}},
            {**proof, "ci_run_ids": [2]}, {**proof, "approval_id": 100},
        ):
            with self.subTest(invalid=invalid), self.assertRaises(PortError):
                self.ledger.remember_completion("WP-101", invalid, True)
        self.ledger.remember_completion("WP-101", proof, True)
        self.assertEqual(self.ledger.remember_completion("WP-101", proof, True), proof)
        with self.assertRaisesRegex(PortError, "Conflicting"):
            self.ledger.remember_completion("WP-101", {**proof, "pr_number": 124}, True)
        reopened = Ledger(self.path, self.policy["repository"])
        self.assertEqual(reopened.accepted_completion("WP-101"), proof)
        with reopened.transaction() as connection:
            connection.execute(
                "UPDATE accepted_completions SET proof=? WHERE repository=? AND wp=?",
                (json.dumps({**proof, "ci_run_ids": []}), self.policy["repository"], "WP-101"),
            )
        with self.assertRaises(PortError):
            reopened.accepted_completion("WP-101")

    def test_conflicting_previously_accepted_implementations_are_not_silently_selected(self):
        binding = {**self.controller.binding("WP-101", BASE), "head_sha": HEAD}
        proof = {
            "kind": "merged-implementation", "binding": binding, "pr_number": 123,
            "merge_commit_sha": MERGE, "merge_parent_sha": BASE,
            "ci": {"run_id": 1, "run_attempt": 1, "check_id": 100},
            "ci_run_ids": [1], "review_ids": [], "approval_id": None,
        }
        self.ledger.remember_completion("WP-101", proof, True)
        self.controller.authority = Mock()
        self.controller.authority.confirm_merged.side_effect = lambda _, value: value
        with patch.object(self.ledger, "historical_records", return_value=[
            {"completion": {**proof, "pr_number": 124}},
        ]), self.assertRaisesRegex(PortError, "conflicting previously accepted"):
            self.controller.completed("WP-101")

    def test_supervised_receipt_import_is_explicit_idempotent_and_not_a_launch(self):
        manifest = test_manifest("WP-000")
        value = bundle(manifest, self.policy, "WP-000")
        controller = Controller(manifest, self.policy, self.settings, self.ledger, self.backend,
                                FakeAuthority({"WP-000": value}))
        identity = Identity(pr_number=123)
        first = controller.import_supervised_completion("WP-000", identity)
        self.assertEqual(controller.import_supervised_completion("WP-000", identity), first)
        self.assertEqual(self.ledger.get("WP-000")["backend"], "supervised")
        self.assertFalse(self.backend.launches)
        controller.authority = FakeAuthority({"WP-000": replace(value, pr={**value.pr, "merged": False})})
        with self.assertRaises(PortError):
            controller.import_supervised_completion("WP-000", identity)
        with self.assertRaises(PortError):
            controller.import_supervised_completion("WP-101", identity)

    def test_mutation_intent_is_persistent_and_cannot_blindly_repeat(self):
        payload = {"body": "fixture issue"}
        _, acquired = self.ledger.begin_operation("issue:WP-101", payload)
        self.assertTrue(acquired)
        with self.assertRaisesRegex(PortError, "Uncertain"):
            self.ledger.begin_operation("issue:WP-101", payload)
        self.ledger.confirm_operation("issue:WP-101", {"issue_number": 7})
        receipt, acquired = self.ledger.begin_operation("issue:WP-101", payload)
        self.assertFalse(acquired)
        self.assertEqual(receipt["issue_number"], 7)
        with self.assertRaises(PortError):
            self.ledger.begin_operation("issue:WP-101", {"body": "different"})

    def test_serialized_merge_lane_retains_uncertainty_and_reconciles_only_real_receipt(self):
        self.reviewed()
        payload = self.merge_payload()
        self.claim("WP-102", attempt="attempt-2")
        self.ledger.transition("WP-102", "attempt-2", "dispatching")
        self.ledger.transition("WP-102", "attempt-2", "running", Identity(pr_number=124))
        self.ledger.transition("WP-102", "attempt-2", "review_wait")
        next_payload = self.merge_payload("WP-102")
        _, acquired = self.ledger.begin_merge(payload)
        self.assertTrue(acquired)
        with self.assertRaisesRegex(PortError, "Serialized"):
            self.ledger.begin_merge(next_payload)
        self.ledger.confirm_operation("merge-lane", {"merged": True, "sha": BASE})
        receipt, acquired = self.ledger.begin_merge(payload)
        self.assertFalse(acquired)
        self.assertTrue(receipt["merged"])
        _, acquired = self.ledger.begin_merge(next_payload)
        self.assertTrue(acquired)

    def test_merge_lane_recovery_requires_verified_matching_real_merge(self):
        self.reviewed()
        payload = self.merge_payload()
        self.ledger.begin_merge(payload)
        for receipt, authoritative in (({"merged": False, "sha": BASE}, True),
                                       ({"merged": True, "sha": BASE}, False),
                                       ({"merged": True, "sha": "invented"}, True)):
            with self.subTest(receipt=receipt), self.assertRaises(PortError):
                self.ledger.recover_merge(payload, receipt, authoritative)
        with self.assertRaisesRegex(PortError, "Merge recovery disagrees"):
            self.ledger.recover_merge({"pr_number": 124}, {"merged": True, "sha": BASE}, True)
        with self.assertRaises(PortError):
            self.ledger.begin_merge({"pr_number": 124})
        self.assertTrue(self.ledger.recover_merge(payload, {"merged": True, "sha": BASE}, True))
        self.assertFalse(self.ledger.begin_merge(payload)[1])
        with self.assertRaisesRegex(PortError, "Conflicting confirmed merge receipt"):
            self.ledger.recover_merge(payload, {"merged": True, "sha": HEAD}, True)

    def test_merge_recovery_rejects_stale_lease_revision_atomically(self):
        self.reviewed()
        payload = self.merge_payload()
        self.ledger.begin_merge(payload)
        self.ledger.evolve_scope(
            "WP-101", "attempt-1", payload["reservation_revision"],
            {"wp-owned": ["fixture"]}, ["test"], ["fixture"],
        )
        with self.assertRaisesRegex(PortError, "Merge recovery disagrees"):
            self.ledger.recover_merge(payload, {"merged": True, "sha": BASE}, True)
        with self.ledger.transaction() as connection:
            operation = connection.execute(
                "SELECT state FROM operations WHERE key='merge-lane'",
            ).fetchone()
        self.assertEqual(operation["state"], "intent")

    def merge_payload(self, wp="WP-101"):
        record = self.ledger.get(wp)
        return {
            "work_package": wp, "pr_number": record["identity"]["pr_number"],
            "attempt": record["attempt"], "reservation_revision": record["revision"],
            "binding": record["binding"], "base_sha": record["binding"]["base_sha"],
            "policy_revision": record["binding"]["policy_revision"], "expected_head_sha": HEAD,
        }

    def test_merge_fence_rejects_wrong_attempt_pr_revision_and_binding_before_any_intent(self):
        self.reviewed()
        payload = self.merge_payload()
        original = self.ledger.get("WP-101")
        for field, value in (
            ("work_package", "WP-102"), ("attempt", "retired-attempt"),
            ("pr_number", 164), ("pr_number", True), ("reservation_revision", 99),
            ("reservation_revision", True), ("base_sha", HEAD), ("policy_revision", "a" * 64),
            ("binding", {**payload["binding"], "manifest_sha256": "a" * 64}),
            ("expected_head_sha", "main"),
        ):
            with self.subTest(field=field, value=value), self.assertRaisesRegex(PortError, "Merge intent"):
                self.ledger.begin_merge({**payload, field: value})
            with self.ledger.transaction() as connection:
                self.assertEqual(connection.execute("SELECT COUNT(*) FROM operations").fetchone()[0], 0)
            self.assertEqual(self.ledger.get("WP-101"), original)
        for field in payload:
            with self.subTest(missing=field), self.assertRaises(PortError):
                self.ledger.begin_merge({k: v for k, v in payload.items() if k != field})

    def test_merge_fence_and_evolution_race_leaves_stale_merge_without_intent(self):
        self.reviewed()
        payload = self.merge_payload()
        self.ledger.evolve_scope("WP-101", "attempt-1", payload["reservation_revision"],
                                {"wp-owned": ["fixture"]}, ["test"], ["fixture"])
        with self.assertRaisesRegex(PortError, "Merge intent"):
            self.ledger.begin_merge(payload)
        current = self.merge_payload()
        with ThreadPoolExecutor(max_workers=2) as executor:
            def acquire(_):
                try:
                    return self.ledger.begin_merge(current)[1]
                except PortError:
                    return False
            self.assertCountEqual(list(executor.map(acquire, range(2))), [True, False])

    def test_controller_scope_evolution_never_adopts_a_different_attempt(self):
        self.claim()
        before = self.ledger.get("WP-101")
        paths = self.manifest.wp("WP-101")["write_paths"]
        with self.assertRaisesRegex(PortError, "caller"):
            self.controller.evolve_reservation("WP-101", paths, ["modify"], before["revision"],
                                               attempt="retired-attempt")
        self.assertEqual(self.ledger.get("WP-101"), before)
        evolved = self.controller.evolve_reservation(
            "WP-101", paths, ["test"], before["revision"], attempt=before["attempt"],
        )
        self.assertEqual(evolved["attempt"], before["attempt"])

    def test_controller_merge_dispatch_carries_current_attempt_and_complete_binding(self):
        self.reviewed()
        value = bundle(self.manifest, self.policy, "WP-101", merged=False)
        self.controller.authority = FakeAuthority({"WP-101": value})
        backend = Mock()
        backend.merge_exact.return_value = {"merged": True, "sha": MERGE}
        with patch.object(self.controller, "record_completion", return_value={}) as completion, \
                patch.object(self.ledger, "begin_merge", wraps=self.ledger.begin_merge) as intent:
            result = self.controller.merge("WP-101", backend)
        self.assertEqual(result["action"], "real-merge-confirmed")
        backend.merge_exact.assert_called_once_with(123, HEAD, BASE)
        completion.assert_called_once_with("WP-101")
        payload = intent.call_args.args[0]
        self.assertEqual(payload["attempt"], "attempt-1")
        self.assertEqual(payload["binding"], self.controller.binding("WP-101", BASE))
        self.assertFalse(self.ledger.begin_merge(payload)[1])

    def test_controller_merge_rejects_reservation_change_during_gate_evaluation(self):
        self.reviewed()
        backend = Mock()
        def decide(_):
            self.ledger.evolve_scope("WP-101", "attempt-1", 1,
                                    {"wp-owned": ["fixture"]}, ["test"], ["fixture"])
            return {"action": "merge", "expected_head_sha": HEAD, "base_sha": BASE}
        with patch.object(self.controller, "decide_merge", side_effect=decide), \
                self.assertRaisesRegex(PortError, "changed during gate"):
            self.controller.merge("WP-101", backend)
        backend.merge_exact.assert_not_called()
        with self.ledger.transaction() as connection:
            self.assertEqual(connection.execute("SELECT COUNT(*) FROM operations").fetchone()[0], 0)

    def test_real_completion_recovers_receipt_bound_merge_lane_and_allows_next_wp(self):
        identity = self.reviewed()
        merged = bundle(self.manifest, self.policy, "WP-101")
        self.controller.authority = FakeAuthority({
            "WP-101": replace(merged, pr={**merged.pr, "merged": False, "merge_commit_sha": None}),
        })
        record = self.ledger.get("WP-101")
        payload = {
            "work_package": "WP-101", "pr_number": identity.pr_number,
            **self.controller.decide_merge("WP-101"),
            "attempt": record["attempt"], "reservation_revision": record["revision"],
            "binding": record["binding"], "policy_revision": record["binding"]["policy_revision"],
        }
        self.assertTrue(self.ledger.begin_merge(payload)[1])
        self.ledger.transition("WP-101", record["attempt"], "merge_wait")
        self.controller.authority.bundles["WP-101"] = merged
        self.backend.observation = Observation(identity, "completed", False, True)
        proof = self.controller.record_completion("WP-101")
        self.assertEqual(proof["merge_commit_sha"], MERGE)
        with self.ledger.transaction() as connection:
            operation = connection.execute(
                "SELECT state,identity FROM operations WHERE key='merge-lane'",
            ).fetchone()
        self.assertEqual(operation["state"], "confirmed")
        self.assertEqual(json.loads(operation["identity"]), {"merged": True, "sha": MERGE})
        self.assertEqual(self.controller.record_completion("WP-101"), proof)
        receipt, acquired = self.ledger.begin_merge(payload)
        self.assertFalse(acquired)
        self.assertEqual(receipt, {"merged": True, "sha": MERGE})
        self.claim("WP-102", attempt="attempt-2")
        self.ledger.transition("WP-102", "attempt-2", "dispatching")
        self.ledger.transition("WP-102", "attempt-2", "running", Identity(pr_number=124))
        self.ledger.transition("WP-102", "attempt-2", "review_wait")
        self.assertTrue(self.ledger.begin_merge(self.merge_payload("WP-102"))[1])

    def test_confirmed_merge_replay_cannot_restore_retired_attempt_authority(self):
        self.prepare_successor()
        payload = self.merge_payload()
        self.ledger.begin_merge(payload)
        self.ledger.confirm_operation("merge-lane", {"merged": True, "sha": MERGE})
        self.assert_successor_history_and_replay()
        with self.assertRaisesRegex(PortError, "Merge intent"):
            self.ledger.begin_merge(payload)


class FixturePolicyApi(GitHubApi):
    def __init__(self, routes):
        self.fake = FakeApi(routes)

    def request(self, method, path, payload=None, *, readonly=False):
        return self.fake.request(method, path, payload, readonly=readonly)


class PolicyReconciliationTests(unittest.TestCase):
    OLD_BASE = "d7f35bbe938ca79d3bc8ab5510fb5cfa9cf8a808"
    CURRENT_BASE = "d85ac9f8a5367615099bc215acc8d022c11eb75b"
    ATTEMPT = "same-attempt"
    WP = "WP-302"

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="policy-reconcile-")
        self.addCleanup(self.temporary.cleanup)
        self.path = Path(self.temporary.name) / "ledger.sqlite"
        self.worktree = str(Path(self.temporary.name) / "worker")
        self.branch = "fixture-navigation"
        self.manifest, self.policy = trusted_inputs(REPO, self.CURRENT_BASE)
        old_manifest, self.old_policy = trusted_inputs(REPO, self.OLD_BASE)
        self.settings = settings(self.path)
        self.ledger = Ledger(self.path, self.policy["repository"])
        owner = self.manifest.wp(self.WP)["owner"]
        profile = next(item["sha256"] for item in self.manifest.data["agents"] if item["name"] == owner)
        self.capabilities = {
            "repository": self.policy["repository"], "default_branch_sha": self.CURRENT_BASE,
            "profiles": {owner: profile}, "branch": self.branch, "worktree": self.worktree,
            "caller_session_id": "session-1", "ledger_path": str(self.path),
            "isolated_worktrees": True, "shared_ledger": True, "advisory_reservations": True,
            "read_only_reference": True, "credential_isolation": True, "authenticated": True,
        }
        self.session = {
            "session_id": "session-1", "repository": self.policy["repository"],
            "branch": self.branch, "worktree": self.worktree, "pr_number": 123,
            "pr_open": True, "worker_state": "in_progress",
        }
        self.observation = Observation(Identity(session_id="session-1", pr_number=123), "in_progress", True, True)
        self.create = Mock(side_effect=AssertionError("policy reconciliation must not create sessions"))
        self.send = Mock(side_effect=AssertionError("policy reconciliation must not send repair messages"))
        self.host = NativeHost(
            lambda: self.capabilities, lambda wp, identity: self.observation, self.create,
            lambda session: copy.deepcopy(self.session), self.send,
            Mock(side_effect=AssertionError("policy reconciliation must not spend a worker budget")),
        )
        self.backend = LocalHostBackend(self.host, self.manifest, self.policy)
        prefix = f"/repos/{self.policy['repository']}"
        self.pr_path = prefix + "/pulls/123"
        self.pr = {
            "number": 123, "state": "open", "merged": False, "body": marker(self.WP),
            "head": {"ref": self.branch, "sha": HEAD, "repo": {"full_name": self.policy["repository"]}},
            "base": {
                "ref": self.policy["default_branch"], "sha": self.CURRENT_BASE,
                "repo": {"full_name": self.policy["repository"]},
            },
        }
        self.api = FixturePolicyApi({
            ("GET", "/user"): {"login": self.settings.user_login, "type": "User"},
            ("GET", prefix): {
                "full_name": self.policy["repository"], "default_branch": self.policy["default_branch"],
                "permissions": {"push": True},
            },
            ("GET", prefix + "/git/ref/heads/main"): {"object": {"sha": self.CURRENT_BASE}},
            ("GET", self.pr_path): self.pr,
        })
        self.cloud = CloudBackend(self.api, self.manifest, self.policy, self.settings)
        self.controller = Controller(
            self.manifest, self.policy, self.settings, self.ledger, self.backend,
            GitHubGateAuthority(self.cloud, self.manifest, self.policy),
        )
        scoped = {
            "wp-owned": ["android/app/src/main/kotlin/com/meshcoreone/android/app/navigation/Fixture.kt"],
            "app-build-launcher": ["android/app/src/main/kotlin/com/meshcoreone/android/app/AppContainer.kt"],
            "reviewed-support-consumer": [
                "android/feature/chats/src/main/kotlin/com/meshcoreone/android/chats/Fixture.kt",
            ],
            "reviewed-support-test": [
                "android/feature/chats/src/test/kotlin/com/meshcoreone/android/chats/FixtureTest.kt",
            ],
        }
        paths = sorted(path for values in scoped.values() for path in values)
        operations = ["modify", "add-direct-support-wiring", "modify-related-consumer", "add-or-update-related-test"]
        binding = {
            "repository": self.policy["repository"], "work_package": self.WP, "base_sha": self.OLD_BASE,
            "source_sha": old_manifest.data["reference"]["commit"], "manifest_sha256": old_manifest.sha256,
            "policy_revision": policy_revision(old_manifest, self.old_policy),
        }
        self.ledger.claim(
            {**self.manifest.wp(self.WP), "write_paths": paths}, binding, "local", self.ATTEMPT,
            NOW, 1800, 5, 0, 100, 5, capabilities=scoped, operations=operations,
            authorization_context={"original": "immutable user authorization"}, branch=self.branch,
            worktree=self.worktree, initial_identity=Identity(session_id="session-1"),
        )
        self.trusted = patch("controller.dispatch.require_trusted_controller", return_value=self.CURRENT_BASE)
        self.trusted.start()
        self.addCleanup(self.trusted.stop)
        self.attestation = patch.object(LocalHostBackend, "require_policy_owner", return_value=None)
        self.attestation.start()
        self.addCleanup(self.attestation.stop)

    def reconcile(self):
        return self.controller.reconcile_policy(self.WP, self.ATTEMPT, 1, NOW)

    def audits(self):
        with closing(sqlite3.connect(self.path)) as connection:
            exists = connection.execute(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='policy_reconciliations'"
            ).fetchone()[0]
            return list(connection.execute(
                "SELECT previous_record,new_binding,witness FROM policy_reconciliations"
            )) if exists else []

    def test_same_task_preserves_record_scope_and_audits_without_launch_or_old_gate_authority(self):
        before = self.ledger.get(self.WP)
        result = self.reconcile()
        after = self.ledger.get(self.WP)
        self.assertEqual(result["action"], "same-task-policy-reconciled")
        self.assertFalse(result["previous_binding_evidence_valid"])
        self.assertEqual(after, {
            **before, "binding": self.controller.binding(self.WP, self.CURRENT_BASE), "revision": 2,
            "identity": {**before["identity"], "pr_number": 123},
        })
        self.assertEqual(len(self.audits()), 1)
        previous, binding, witness = map(json.loads, self.audits()[0])
        self.assertEqual(previous, before)
        self.assertEqual(binding, after["binding"])
        self.assertEqual(witness["session_id"], "session-1")
        self.assertEqual(witness["pr_number"], 123)
        self.assertEqual(witness["pr_head"], HEAD)
        self.create.assert_not_called()
        self.send.assert_not_called()
        self.assertTrue(all(call[0] == "GET" for call in self.api.fake.calls))
        old_review = {"binding": {**before["binding"], "head_sha": HEAD}, "ci_run_ids": [1], "approval_id": None}
        with patch.dict(self.policy, {"dispatch_mode": "local", "activation_approved": True}), \
                patch.object(Settings, "require_live"), \
                patch.object(self.controller.authority, "reviewed_candidate", return_value=old_review), \
                self.assertRaisesRegex(PortError, "disagrees"):
            self.controller.decide_merge(self.WP)
        with self.assertRaisesRegex(PortError, "stale"):
            self.reconcile()
        self.assertEqual(len(self.audits()), 1)

    def test_stale_attempt_revision_unknown_authority_and_paused_policy_refuse_without_writes(self):
        before = self.ledger.get(self.WP)
        for attempt, revision in (("other-attempt", 1), (self.ATTEMPT, 2)):
            with self.subTest(attempt=attempt, revision=revision), self.assertRaises(PortError):
                self.controller.reconcile_policy(self.WP, attempt, revision, NOW)
        self.controller.authority = None
        with self.assertRaisesRegex(PortError, "authorities"):
            self.reconcile()
        self.controller.authority = GitHubGateAuthority(self.cloud, self.manifest, self.policy)
        self.controller.settings = replace(self.settings, paused=True)
        with self.assertRaisesRegex(PortError, "Paused"):
            self.reconcile()
        self.assertEqual(self.ledger.get(self.WP), before)
        self.assertEqual(self.audits(), [])

    def test_injected_callbacks_and_authority_flags_cannot_impersonate_a_native_owner(self):
        before = self.ledger.get(self.WP)
        self.attestation.stop()
        try:
            with self.assertRaisesRegex(PortError, "caller attestation is unavailable"):
                self.reconcile()
        finally:
            self.attestation.start()
        self.assertEqual(self.api.fake.calls, [])
        self.assertEqual(self.ledger.get(self.WP), before)
        self.assertEqual(self.audits(), [])

    def test_native_entry_refuses_before_network_or_ledger_initialization(self):
        from controller.dispatch import native_policy_controller

        before = self.ledger.get(self.WP)
        self.attestation.stop()
        try:
            with patch("controller.dispatch.GitHubApi", side_effect=AssertionError("network before attestation")), \
                    patch.object(Ledger, "__init__", side_effect=AssertionError("ledger initialized")), \
                    self.assertRaisesRegex(PortError, "caller attestation is unavailable"):
                native_policy_controller(
                    self.manifest, self.policy, self.settings, self.host, self.WP, self.ATTEMPT, 1,
                )
        finally:
            self.attestation.start()
        self.assertEqual(self.ledger.get(self.WP), before)
        self.assertEqual(self.audits(), [])

    def test_manual_reconciliation_does_not_activate_dispatch_or_consume_a_budget(self):
        self.controller.settings = replace(self.settings, mode="off", max_inflight=None, budget=None)
        self.cloud.settings = self.controller.settings
        result = self.reconcile()
        self.assertFalse(result["dispatched"])
        self.assertFalse(result["merged"])
        self.assertEqual(self.policy["dispatch_mode"], "off")
        self.assertFalse(self.policy["activation_approved"])
        self.create.assert_not_called()
        self.send.assert_not_called()

    def test_github_authority_must_use_the_same_settings_and_complete_manifest(self):
        before = self.ledger.get(self.WP)
        self.cloud.settings = replace(self.settings, user_login="other-user")
        with self.assertRaisesRegex(PortError, "authority differs"):
            self.reconcile()
        self.cloud.settings = self.settings
        self.cloud.manifest = replace(self.manifest, exclusions={"reference_sha": HEAD})
        with self.assertRaisesRegex(PortError, "authority differs"):
            self.reconcile()
        self.cloud.manifest = replace(self.manifest, repo=self.path.parent)
        with self.assertRaisesRegex(PortError, "authority differs"):
            self.reconcile()
        self.assertEqual(self.ledger.get(self.WP), before)
        self.assertEqual(self.audits(), [])

    def test_foreign_or_missing_owner_session_pr_and_host_refuse(self):
        before = self.ledger.get(self.WP)
        for key, bad in (
            ("caller_session_id", "other-session"), ("branch", "other-branch"),
            ("worktree", self.worktree + "-other"), ("ledger_path", str(self.path.parent / "missing.sqlite")),
            ("authenticated", False), ("profiles", None), ("profiles", []),
        ):
            original = self.capabilities[key]
            self.capabilities[key] = bad
            with self.subTest(key=key), self.assertRaises((PortError, OSError)):
                self.reconcile()
            self.capabilities[key] = original
        for key, bad in (
            ("session_id", "other-session"), ("repository", "other/repo"), ("branch", "other-branch"),
            ("worktree", self.worktree + "-other"), ("pr_number", 999), ("pr_open", False),
            ("worker_state", "completed"),
        ):
            original = self.session[key]
            self.session[key] = bad
            with self.subTest(key=key), self.assertRaises(PortError):
                self.reconcile()
            self.session[key] = original
        for observation in (
            Observation(Identity(session_id="other", pr_number=123), "in_progress", True, True),
            Observation(Identity(session_id="session-1", pr_number=123), "in_progress", True, False),
            Observation(Identity(session_id="session-1", pr_number=123), "in_progress", True, True, True),
            Observation(Identity(session_id="session-1", pr_number=123), "completed", True, True),
            Observation(Identity(session_id="session-1"), "in_progress", True, True),
        ):
            self.observation = observation
            with self.subTest(observation=observation), self.assertRaises(PortError):
                self.reconcile()
        self.assertEqual(self.ledger.get(self.WP), before)
        self.assertEqual(self.audits(), [])

    def test_github_pr_identity_and_head_are_independently_rechecked(self):
        before = self.ledger.get(self.WP)
        route = ("GET", self.pr_path)
        for change in (
            {"state": "closed"}, {"merged": True}, {"body": ""},
            {"head": {**self.pr["head"], "ref": "foreign"}}, {"base": {**self.pr["base"], "ref": "foreign"}},
            {"head": {**self.pr["head"], "repo": None}}, {"base": {**self.pr["base"], "sha": HEAD}},
        ):
            self.api.fake.routes[route] = {**self.pr, **change}
            with self.subTest(change=change), self.assertRaises(PortError):
                self.reconcile()
        heads = iter((HEAD, MERGE))
        self.api.fake.routes[route] = lambda _: {**self.pr, "head": {**self.pr["head"], "sha": next(heads)}}
        with self.assertRaisesRegex(PortError, "head changed"):
            self.reconcile()
        self.assertEqual(self.ledger.get(self.WP), before)
        self.assertEqual(self.audits(), [])

    def test_unchanged_mixed_scope_is_validated_without_cartesian_admission(self):
        record = self.ledger.get(self.WP)
        self.controller._validate_reconciliation_scope(self.WP, record, self.manifest, self.old_policy, self.policy)
        changed_policy = copy.deepcopy(self.policy)
        changed_policy["reservation_policy"]["capabilities"][0]["operations"].append("new-operation")
        with self.assertRaisesRegex(PortError, "capability scope"):
            self.controller._validate_reconciliation_scope(
                self.WP, record, self.manifest, self.old_policy, changed_policy,
            )
        for mutate in (
            lambda row: row["capabilities"]["wp-owned"].append("android/unowned/Fixture.kt"),
            lambda row: row["reservation_operations"].append("invented-operation"),
            lambda row: row["paths"].append("android/unowned/Fixture.kt"),
            lambda row: row["capabilities"].update({"invented": [row["paths"][0]]}),
        ):
            bad = copy.deepcopy(record)
            mutate(bad)
            with self.subTest(bad=bad), self.assertRaises(PortError):
                self.controller._validate_reconciliation_scope(self.WP, bad, self.manifest, self.old_policy, self.policy)

    def test_unreviewed_policy_source_or_manifest_drift_refuses(self):
        before = self.ledger.get(self.WP)
        with patch.dict("controller.engine.REVIEWED_POLICY_TRANSITIONS", {}, clear=True), \
                self.assertRaisesRegex(PortError, "Unreviewed"):
            self.reconcile()
        old_manifest, old_policy = trusted_inputs(REPO, self.OLD_BASE)
        for kind in ("source", "manifest", "policy"):
            altered_manifest = copy.deepcopy(old_manifest.data)
            altered_policy = copy.deepcopy(old_policy)
            if kind == "source":
                altered_manifest["reference"]["commit"] = HEAD
            elif kind == "manifest":
                altered_manifest["work_packages"][0]["title"] = "Unreviewed fixture"
            else:
                altered_policy["repair_limit"] += 1
            changed = replace(old_manifest, data=altered_manifest)
            def inputs(repo, revision):
                return (changed, altered_policy) if revision == self.OLD_BASE else (self.manifest, self.policy)
            with self.subTest(kind=kind), patch("controller.native_review.trusted_inputs", side_effect=inputs), \
                    self.assertRaises(PortError):
                self.reconcile()
        self.assertEqual(self.ledger.get(self.WP), before)
        self.assertEqual(self.audits(), [])

    def test_complete_record_race_and_inflight_merge_refuse_without_overwriting(self):
        before = self.ledger.get(self.WP)
        actual_get = self.host.get_session
        def transition_during_lookup(session):
            self.ledger.transition(self.WP, self.ATTEMPT, "running")
            return actual_get(session)
        self.host.get_session = transition_during_lookup
        with self.assertRaisesRegex(PortError, "record changed"):
            self.reconcile()
        raced = self.ledger.get(self.WP)
        self.assertEqual(raced, {**before, "state": "running"})
        self.assertEqual(self.audits(), [])
        self.host.get_session = actual_get
        with self.ledger.transaction() as connection:
            connection.execute(
                "INSERT INTO operations VALUES (?,?,?,?,NULL)",
                (self.policy["repository"], "merge-lane", "intent", "fixture-hash"),
            )
        with self.assertRaisesRegex(PortError, "in-flight merge"):
            self.reconcile()
        self.assertEqual(self.ledger.get(self.WP), raced)
        self.assertEqual(self.audits(), [])

    def test_default_branch_race_refuses_before_mutation(self):
        before = self.ledger.get(self.WP)
        with patch.object(self.cloud, "current_base", return_value=HEAD), \
                self.assertRaisesRegex(PortError, "advanced"):
            self.reconcile()
        self.assertEqual(self.ledger.get(self.WP), before)
        self.assertEqual(self.audits(), [])

    def test_failed_update_rolls_back_original_record_and_audit(self):
        before = self.ledger.get(self.WP)
        with self.ledger.transaction() as connection:
            connection.execute("""
                CREATE TRIGGER fixture_abort_policy_update BEFORE UPDATE OF binding ON leases
                BEGIN SELECT RAISE(ABORT, 'fixture policy update failure'); END
            """)
        with self.assertRaisesRegex(sqlite3.IntegrityError, "fixture policy update failure"):
            self.reconcile()
        self.assertEqual(self.ledger.get(self.WP), before)
        self.assertEqual(self.audits(), [])

    def test_shared_ledger_transaction_never_waits_on_network_or_native_host(self):
        commit = self.ledger._reconcile_policy_binding
        def without_external_calls(*args):
            with patch.object(self.api, "request", side_effect=AssertionError("network under ledger lock")), \
                    patch.object(self.host, "capabilities", side_effect=AssertionError("host under ledger lock")), \
                    patch.object(self.host, "get_session", side_effect=AssertionError("host under ledger lock")):
                return commit(*args)
        with patch.object(self.ledger, "_reconcile_policy_binding", side_effect=without_external_calls):
            self.reconcile()
        self.assertEqual(self.ledger.get(self.WP)["revision"], 2)

    def test_concurrent_replays_allow_only_one_atomic_transition(self):
        def run(_):
            try:
                self.reconcile()
                return "reconciled"
            except PortError:
                return "stale"
        with ThreadPoolExecutor(max_workers=2) as executor:
            results = list(executor.map(run, range(2)))
        self.assertCountEqual(results, ["reconciled", "stale"])
        self.assertEqual(self.ledger.get(self.WP)["revision"], 2)
        self.assertEqual(len(self.audits()), 1)

    def test_candidate_controller_cannot_authorize_its_own_policy_transition(self):
        before = self.ledger.get(self.WP)
        self.trusted.stop()
        try:
            with self.assertRaisesRegex(PortError, "current default-branch|immutable Git input"):
                self.reconcile()
        finally:
            self.trusted.start()
        self.assertEqual(self.ledger.get(self.WP), before)
        self.assertEqual(self.audits(), [])
