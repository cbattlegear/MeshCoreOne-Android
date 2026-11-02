"""AndroidOnly: WP-003 Native GitHub review transport, including hostile/stale server facts."""

import copy
import hashlib
import io
import json
import os
import subprocess
import sys
import tempfile
import unittest
from dataclasses import asdict, replace
from pathlib import Path
from unittest.mock import patch

from fixtures import BASE, HEAD, MERGE, FakeApi, bundle, policy, settings, test_manifest
from controller.authority import GitHubGateAuthority
from controller.backends import CloudBackend
from controller.errors import PortError
from controller.engine import Controller
from controller.gates import merge_decision, validate_evidence, verify_completion, verify_pr_gates
from controller.ledger import Identity, Ledger
from controller.native_review import (
    CI_PATH, COMPRESSED_REVIEW_MARKER, PUBLICATION_MARKER, PUBLICATION_STEP, RESULT_MARKER,
    REVIEW_MARKER, WORKFLOW_PATH, NativeReview, main,
)
from controller.publication import encode_structured_body
from controller.repair_acceptance import (
    INFRASTRUCTURE_ACCEPTANCE, INFRASTRUCTURE_SCOPE_ID, INFRASTRUCTURE_SCOPE_KIND,
    INFRASTRUCTURE_VERIFICATION,
)
from controller.render import marker
from controller.schema import digest
from controller.workflows import parse_yaml, validate_publisher


class NativeReviewTests(unittest.TestCase):
    def test_isolated_file_and_module_loading_reach_deferred_staging_imports(self):
        source = Path(__file__).resolve().parents[1] / "controller" / "native_review.py"
        script = """
import importlib, runpy, socket, sys
from unittest.mock import patch
def no_network(*args, **kwargs):
    raise AssertionError("entrypoint fixture must not use the network")
with patch.object(socket, "socket", no_network):
    sys.path.insert(0, str(__import__("pathlib").Path(sys.argv[1]).parents[1] / "tests"))
    for mode in ("file", "module"):
        if mode == "file":
            namespace = runpy.run_path(sys.argv[1], run_name="entrypoint_fixture")
        else:
            namespace = vars(importlib.import_module("controller.native_review"))
        cls = namespace["NativeReview"]
        native = cls.__new__(cls)
        for action, args in (("stage_repair", ("WP-003", 0, 0)),
                             ("stage_whole", ("WP-003", 0, {}))):
            try:
                getattr(native, action)(*args)
            except namespace["PortError"]:
                pass
            else:
                raise AssertionError("expected validation after deferred imports")
        from test_native_review import NativeReviewTests
        fixture_native, api, prefix, value = NativeReviewTests().fixture(current=True)
        native = cls(fixture_native.backend, fixture_native.manifest, fixture_native.policy)
        with patch.object(native, "source_receipt", return_value=(
            "", {"schema_version": 2, "scope_comment_id": 1},
        )), patch("controller.repair_acceptance.authenticated_scope",
                  side_effect=namespace["PortError"]("deferred repair receipt import reached")):
            try:
                native.evaluate("WP-003", 123, 90)
            except namespace["PortError"] as error:
                assert str(error) == "deferred repair receipt import reached", str(error)
            else:
                raise AssertionError("expected stop immediately after deferred receipt import")
            assert all(method == "GET" for method, *rest in api.calls), api.calls
print("file/module deferred imports reached; no authority or network")
"""
        environment = dict(os.environ)
        for name in tuple(environment):
            if "TOKEN" in name.upper() or name.startswith("GITHUB_"):
                environment.pop(name)
        result = subprocess.run(
            [sys.executable, "-I", "-B", "-c", script, str(source)], env=environment,
            cwd=source.parent, capture_output=True, text=True, timeout=30,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("file/module deferred imports reached", result.stdout)

    def setUp(self):
        stage = patch("controller.native_review.stage_review", side_effect=lambda *args: self.staged)
        stage.start()
        self.addCleanup(stage.stop)
        candidate_tree = patch("controller.native_review.tree", return_value={})
        candidate_tree.start()
        self.addCleanup(candidate_tree.stop)

    def fixture(self, *, current=False):
        manifest = test_manifest("WP-003")
        rules = policy()
        rules.update(trusted_gate_publisher_app_id=None, trusted_check_app_ids=[],
                     trusted_workflow_ids=[], branch_rules_proven=False,
                     paused=True, activation_approved=False, dispatch_mode="off")
        if current:
            rules["required_checks"] = ["android-ci", "parity-review"]
        value = bundle(manifest, rules, "WP-003", changed_paths=["tools/android-port/controller/native_review.py"],
                       merged=False)
        run_url = f"https://github.com/{rules['repository']}/actions/runs/1/attempts/1"
        acceptance = [{**entry, "evidence": [run_url]} for entry in value.evidence["acceptance"]]
        evidence = {
            "schema_version": 2, **asdict(value.binding), "changed_paths": value.pr["changed_paths"],
            "runs": [{"check": "android-ci", "run_id": 1, "run_attempt": 1}],
            "retained_artifacts": [], "acceptance": acceptance, "source_tests": [],
        }
        review = {**value.review, "acceptance": copy.deepcopy(acceptance)}
        value = replace(value, evidence=evidence, review=review, approvals=[])
        self.staged = {"binding": asdict(value.binding), "changed_paths": value.pr["changed_paths"],
                       "fixture_only": "Real immutable staging is separately covered by test_gate_integration."}
        receipt = {
            "schema_version": 1, "binding": asdict(value.binding),
            "method": "authenticated-coordinator-read-only", "staging_sha256": digest(self.staged),
            "evidence": evidence, "review": review, "approvals": [],
        }
        body = REVIEW_MARKER + json.dumps(receipt)
        result = {
            "binding": asdict(value.binding), "pr_number": 123, "review_id": 90,
            "review_body_sha256": hashlib.sha256(body.encode()).hexdigest(),
            "publisher_run_id": 2, "publisher_run_attempt": 1,
        }
        summary = RESULT_MARKER + json.dumps(result)
        prefix = "/repos/" + rules["repository"]
        ci = {"id": 100, "name": "android-ci", "head_sha": HEAD, "status": "completed",
              "conclusion": "success", "app": {"id": 10}, "check_suite": {"id": 71}}
        checks = [ci] + [{
            "id": 101 + i, "name": name, "head_sha": HEAD, "status": "completed", "conclusion": "success",
            "app": {"id": 10}, "external_id": f"{value.binding.key}:2:1",
            "details_url": f"https://github.com/{rules['repository']}/actions/runs/2",
            "output": {"summary": summary},
        } for i, name in enumerate(("parity-review", "gate-integrity"))]
        api = FakeApi({
            ("GET", prefix + "/git/ref/heads/main"): {"object": {"sha": BASE}},
            ("GET", prefix + "/pulls/123"): {
                "number": 123, "body": marker("WP-003"), "merged": False, "state": "open", "draft": False,
                "merge_commit_sha": None, "base": {"sha": BASE, "ref": "main", "repo": {"full_name": rules["repository"]}},
                "head": {"sha": HEAD}, "user": {"login": "cbattlegear"}, "labels": [],
            },
            ("GET", prefix + "/pulls/123/reviews?per_page=100&page=1"): [{
                "id": 90, "state": "COMMENTED", "commit_id": HEAD,
                "user": {"login": "cbattlegear", "type": "User"}, "body": body,
                "submitted_at": "2026-10-10T11:00:00Z",
            }],
            ("GET", prefix + "/pulls/123/files?per_page=100&page=1"): [
                {"filename": path} for path in value.pr["changed_paths"]
            ],
            ("GET", prefix + f"/commits/{HEAD}/check-runs?per_page=100&page=1"): {"check_runs": checks},
            ("POST", "/graphql"): {"data": {"repository": {"pullRequest": {
                "reviewThreads": {"nodes": [], "pageInfo": {"hasNextPage": False, "endCursor": None}},
            }}}},
            ("GET", prefix + "/actions/runs/2/attempts/1/jobs?per_page=100&page=1"): {"jobs": [{
                "id": 80, "name": "Native review WP-003 PR 123 receipt 90", "run_id": 2,
                "head_sha": BASE, "status": "completed", "conclusion": "success",
            }]},
        })
        for run_id, path, head, event in ((1, CI_PATH, HEAD, "pull_request"),
                                         (2, WORKFLOW_PATH, BASE, "workflow_dispatch")):
            api.routes[("GET", prefix + f"/actions/runs/{run_id}")] = {
                "id": run_id, "run_attempt": 1, "workflow_id": 20 + run_id, "path": path,
                "head_sha": head, "event": event, "status": "completed", "conclusion": "success",
                "repository": {"full_name": rules["repository"]}, "head_branch": "main",
                "actor": {"login": "cbattlegear"}, "triggering_actor": {"login": "cbattlegear"},
                "check_suite_id": 70 + run_id, "pull_requests": [{"number": 123, "base": {"sha": BASE}}],
            }
            api.routes[("GET", prefix + "/actions/workflows/" + path.rsplit("/", 1)[-1])] = {
                "id": 20 + run_id, "path": path,
            }
            api.routes[("GET", prefix + f"/check-suites/{70 + run_id}")] = {
                "id": 70 + run_id, "head_sha": head, "app": {"id": 10, "slug": "github-actions"},
            }
        backend = CloudBackend(api, manifest, rules, settings(None))
        return NativeReview(backend, manifest, rules), api, prefix, value

    def infrastructure_fixture(self):
        native, api, prefix, original = self.fixture(current=True)
        paths = sorted((
            ".github/workflows/android-ci.yml",
            "tools/android-port/controller/native_review.py",
            "tools/android-port/oracle/test_ci.py",
            "tools/android-port/oracle/test_workflow_scope.py",
            "tools/android-port/oracle/workflow_scope.py",
            "tools/android-port/tests/test_native_review.py",
        ))
        scope = {
            "schema_version": 1, "kind": INFRASTRUCTURE_SCOPE_KIND, "scope_id": INFRASTRUCTURE_SCOPE_ID,
            "binding": asdict(original.binding), "paths": paths,
            "verification": copy.deepcopy(INFRASTRUCTURE_VERIFICATION),
        }
        url = f"https://github.com/{native.policy['repository']}/actions/runs/1/attempts/1"
        acceptance = [
            {"id": item["id"], "result": "PASS", "evidence": [url]}
            for item in INFRASTRUCTURE_ACCEPTANCE
        ]
        evidence = {
            "schema_version": 5, **asdict(original.binding), "changed_paths": paths,
            "runs": [{"check": "android-ci", "run_id": 1, "run_attempt": 1}],
            "retained_artifacts": [], "acceptance": acceptance,
            "verification_scope": scope,
        }
        review = {**original.review, "acceptance": copy.deepcopy(acceptance), "human_gate_required": True}
        approval = {
            "kind": "human-acceptance", "review_id": 99, "reviewer": "cbattlegear", "state": "COMMENTED",
            "head_sha": HEAD, "base_sha": BASE, "policy_revision": original.binding.policy_revision,
            "binding_key": original.binding.key,
        }
        value = replace(
            original, pr={**original.pr, "changed_paths": paths},
            evidence=evidence, review=review, approvals=[approval],
        )
        self.staged = {"binding": asdict(value.binding), "changed_paths": paths}
        receipt = {
            "schema_version": 4, "binding": asdict(value.binding),
            "method": "authenticated-coordinator-read-only", "staging_sha256": digest(self.staged),
            "review": review, "evidence": evidence, "approvals": [approval],
        }
        body = REVIEW_MARKER + json.dumps(receipt)
        self.reviews(api, prefix)[0]["body"] = body
        self.reviews(api, prefix).append({
            "id": 99, "state": "COMMENTED", "commit_id": HEAD,
            "user": {"login": "cbattlegear", "type": "User"},
            "body": f"<!-- android-port-acceptance:{value.binding.key} -->",
            "submitted_at": "2026-10-10T12:00:00Z",
        })
        api.routes[("GET", prefix + "/pulls/123/files?per_page=100&page=1")] = [
            {"filename": path} for path in paths
        ]
        api.routes[("GET", prefix + "/actions/runs/1/attempts/1/jobs?per_page=100&page=1")] = {"jobs": [{
            "id": 81, "name": INFRASTRUCTURE_VERIFICATION["job_name"], "run_id": 1,
            "head_sha": HEAD, "status": "completed", "conclusion": "success",
        }]}
        return native, api, prefix, value, scope

    @staticmethod
    def infrastructure_controller_log(**overrides):
        report = {
            "discovered": 9, "run": 9, "passed": 9, "failed": 0, "errors": 0, "skipped": 0,
            "scope": "controller fixtures only; not Android/iOS/hardware parity",
            "discovered_by_root": {
                "tools/android-port/tests": 3, "tools/android-port/local": 2,
                "android/parity-tests": 1, "tools/android-port/oracle": 3,
            },
        }
        report.update(overrides)
        return (
            f"job\tRun controller tests once\t2026-10-10T12:00:00Z {json.dumps(report)}\n"
        )

    def reviews(self, api, prefix):
        return api.routes[("GET", prefix + "/pulls/123/reviews?per_page=100&page=1")]

    def mutate_receipt(self, api, prefix, change):
        reviews = self.reviews(api, prefix)
        payload = json.loads(reviews[0]["body"][len(REVIEW_MARKER):])
        change(payload)
        reviews[0]["body"] = REVIEW_MARKER + json.dumps(payload)

    def test_real_shaped_commented_author_coordinator_receipt_without_custom_app_or_formal_approval(self):
        native, api, _, value = self.fixture()
        actual, result = native.evaluate("WP-003", 123, 90)
        self.assertEqual(actual.binding, value.binding)
        self.assertTrue(actual.authoritative)
        self.assertEqual(result["review_id"], 90)
        self.assertFalse(any(call[0] != "GET" and not call[3] for call in api.calls))
        proof = verify_pr_gates(actual.pr, actual.evidence, actual.review, actual.approvals,
                                actual.binding, native.manifest, native.policy)
        self.assertIsNone(proof["approval_id"])
        self.assertEqual(proof["ci_run_ids"], [1])

    def test_compressed_authenticated_receipt_retains_the_same_review_and_binding(self):
        native, api, prefix, value = self.fixture()
        review = self.reviews(api, prefix)[0]
        receipt = json.loads(review["body"][len(REVIEW_MARKER):])
        receipt["review"]["summary"] = "reviewed " + "x" * 70000
        review["body"] = encode_structured_body(receipt, REVIEW_MARKER, COMPRESSED_REVIEW_MARKER)
        self.assertTrue(review["body"].startswith(COMPRESSED_REVIEW_MARKER))
        actual, _ = native.evaluate("WP-003", 123, 90)
        self.assertEqual(actual.binding, value.binding)
        self.assertEqual(actual.review["summary"], receipt["review"]["summary"])

    def test_bounded_infrastructure_route_uses_exact_candidate_review_and_original_controller_oracle_log(self):
        native, api, prefix, value, _ = self.infrastructure_fixture()
        with patch(
            "controller.repair_acceptance.read_job_log",
            return_value=self.infrastructure_controller_log(),
        ) as read_log:
            actual, result = native.evaluate("WP-003", 123, 90)
        self.assertEqual(actual.evidence["schema_version"], 5)
        self.assertEqual(result["acceptance_kind"], "bounded-infrastructure-verification")
        self.assertFalse(result["wp_complete"])
        self.assertFalse(result["dependency_unlock"])
        self.assertEqual(read_log.call_args.args[1], 81)
        self.assertEqual(result["ci_run_ids"], [1])
        self.assertEqual(value.binding, actual.binding)
        self.assertFalse(any(call[0] != "GET" and not call[3] for call in api.calls))

    def test_infrastructure_staging_is_data_only_and_binds_current_paths(self):
        native, api, _, _, scope = self.infrastructure_fixture()
        with patch("controller.native_review.stage_review", return_value=self.staged):
            staged = native.stage_infrastructure("WP-003", 123, scope)
        self.assertEqual(staged["state"], "staged-data-only")
        self.assertEqual(staged["staging_sha256"], digest(self.staged))
        self.assertEqual(staged["verification_scope"], scope)
        self.assertFalse(staged["authoritative"])
        self.assertFalse(any(call[0] != "GET" and not call[3] for call in api.calls))
        scope["paths"].append("tools/android-port/controller/not-admitted.py")
        scope["paths"].sort()
        with self.assertRaises(PortError):
            native.stage_infrastructure("WP-003", 123, scope)

    def test_bounded_infrastructure_route_rejects_stale_runs_unauthorized_apps_and_bad_logs(self):
        for mutation in (
            "stale-attempt", "wrong-workflow", "wrong-app", "skipped-job",
            "duplicate-job", "zero-oracle-tests", "malformed-discovery", "candidate-path",
        ):
            native, api, prefix, _, scope = self.infrastructure_fixture()
            source = self.reviews(api, prefix)[0]
            receipt = json.loads(source["body"][len(REVIEW_MARKER):])
            log = self.infrastructure_controller_log()
            if mutation == "stale-attempt":
                receipt["evidence"]["runs"][0]["run_attempt"] = 2
            elif mutation == "wrong-workflow":
                api.routes[("GET", prefix + "/actions/runs/1")]["workflow_id"] = 999
            elif mutation == "wrong-app":
                checks = api.routes[("GET", prefix + f"/commits/{HEAD}/check-runs?per_page=100&page=1")]["check_runs"]
                next(check for check in checks if check["name"] == "android-ci")["app"]["id"] = 999
            elif mutation == "skipped-job":
                api.routes[("GET", prefix + "/actions/runs/1/attempts/1/jobs?per_page=100&page=1")]["jobs"][0]["conclusion"] = "skipped"
            elif mutation == "duplicate-job":
                jobs = api.routes[("GET", prefix + "/actions/runs/1/attempts/1/jobs?per_page=100&page=1")]["jobs"]
                jobs.append(copy.deepcopy(jobs[0]))
            elif mutation == "zero-oracle-tests":
                log = self.infrastructure_controller_log(discovered_by_root={
                    "tools/android-port/tests": 4, "tools/android-port/local": 2,
                    "android/parity-tests": 1, "tools/android-port/oracle": 0,
                })
            elif mutation == "malformed-discovery":
                log = self.infrastructure_controller_log(discovered_by_root=["not", "a", "mapping"])
            else:
                scope["paths"].append("tools/android-port/controller/untrusted_reader.py")
                scope["paths"].sort()
                receipt["evidence"]["changed_paths"] = scope["paths"]
            if mutation in ("stale-attempt", "candidate-path"):
                source["body"] = REVIEW_MARKER + json.dumps(receipt)
            with self.subTest(mutation=mutation), patch(
                "controller.repair_acceptance.read_job_log", return_value=log,
            ), self.assertRaises(PortError):
                native.evaluate("WP-003", 123, 90)

    def test_infrastructure_source_receipt_rejects_missing_altered_and_duplicate_receipts(self):
        for mutation in ("missing-scope", "stale-evidence-schema", "duplicate-review"):
            native, api, prefix, _, _ = self.infrastructure_fixture()
            source = self.reviews(api, prefix)[0]
            receipt = json.loads(source["body"][len(REVIEW_MARKER):])
            if mutation == "missing-scope":
                del receipt["evidence"]["verification_scope"]
                source["body"] = REVIEW_MARKER + json.dumps(receipt)
            elif mutation == "stale-evidence-schema":
                receipt["evidence"]["schema_version"] = 4
                source["body"] = REVIEW_MARKER + json.dumps(receipt)
            else:
                self.reviews(api, prefix).append(copy.deepcopy(source))
            with self.subTest(mutation=mutation), patch(
                "controller.repair_acceptance.read_job_log",
                return_value=self.infrastructure_controller_log(),
            ), self.assertRaises(PortError):
                native.evaluate("WP-003", 123, 90)

    def test_infrastructure_publisher_receipt_requires_one_exact_original_log_record(self):
        native, api, prefix, value, _ = self.infrastructure_fixture()
        checks = api.routes[("GET", prefix + f"/commits/{HEAD}/check-runs?per_page=100&page=1")]["check_runs"]
        selected = [check for check in checks if check["name"] in ("parity-review", "gate-integrity")]
        summary = RESULT_MARKER + json.dumps({"acceptance_kind": "bounded-infrastructure-verification"})
        expected = {
            "check_ids": sorted(check["id"] for check in selected),
            "result_sha256": hashlib.sha256(summary.encode("utf-8")).hexdigest(),
        }

        def publisher_log(records):
            return "\n".join(
                f"job\t{PUBLICATION_STEP}\t2026-10-10T12:00:00Z "
                f"{PUBLICATION_MARKER}{json.dumps(record)}"
                for record in records
            )

        run = {"id": 2, "run_attempt": 1}
        suite = {"app": {"id": 10}}
        with patch("controller.repair_acceptance.read_job_log", return_value=publisher_log([expected])):
            native.verify_published_checks(selected, summary, value.binding, run, suite, 123, 90)
        for records in ([], [expected, expected], [{**expected, "result_sha256": "0" * 64}]):
            with self.subTest(records=records), patch(
                "controller.repair_acceptance.read_job_log", return_value=publisher_log(records),
            ), self.assertRaisesRegex(PortError, "publisher check receipt"):
                native.verify_published_checks(selected, summary, value.binding, run, suite, 123, 90)

    def test_infrastructure_publisher_rejects_candidate_ref_actor_and_workflow(self):
        for mutation in ("candidate-ref", "unauthorized-actor", "wrong-workflow"):
            native, api, prefix, _, _ = self.infrastructure_fixture()
            run = api.routes[("GET", prefix + "/actions/runs/2")]
            if mutation == "candidate-ref":
                run["head_branch"] = "candidate-branch"
            elif mutation == "unauthorized-actor":
                run["actor"]["login"] = "untrusted"
            else:
                run["path"] = ".github/workflows/candidate.yml"
            with self.subTest(mutation=mutation), self.assertRaises(PortError):
                native.workflow_run(
                    2, 1, WORKFLOW_PATH, BASE, ("workflow_dispatch",),
                    review_identity=("WP-003", 123, 90),
                )

    def test_infrastructure_authority_uses_bounded_non_completing_consumer(self):
        from controller.native_review import NativeReview

        native, _, _, value, _ = self.infrastructure_fixture()
        authority = GitHubGateAuthority(native.backend, native.manifest, native.policy)
        with patch.object(NativeReview, "bundle", return_value=value):
            proof = authority.reviewed_candidate("WP-003", Identity(pr_number=123), BASE)
        self.assertEqual(proof["binding"], asdict(value.binding))
        self.assertEqual(proof["ci_run_ids"], [1])

    def test_infrastructure_publisher_and_bundle_consumer_must_agree_on_scope_kind(self):
        native, api, prefix, value, _ = self.infrastructure_fixture()
        reviews = self.reviews(api, prefix)
        body = reviews[0]["body"]
        summary = RESULT_MARKER + json.dumps({
            "binding": asdict(value.binding), "pr_number": 123, "review_id": 90,
            "review_body_sha256": hashlib.sha256(body.encode()).hexdigest(),
            "publisher_run_id": 2, "publisher_run_attempt": 1,
            "acceptance_kind": "bounded-repair", "wp_complete": False, "dependency_unlock": False,
        })
        checks = api.routes[("GET", prefix + f"/commits/{HEAD}/check-runs?per_page=100&page=1")]["check_runs"]
        for check in checks:
            if check["name"] in ("parity-review", "gate-integrity"):
                check["output"]["summary"] = summary
        with patch(
            "controller.repair_acceptance.read_job_log",
            return_value=self.infrastructure_controller_log(),
        ), self.assertRaisesRegex(PortError, "stale review/binding provenance"):
            native.bundle("WP-003", Identity(pr_number=123))

    def test_authority_reconciles_native_checks_without_invented_application_ids(self):
        native, api, _, value = self.fixture()
        authority = GitHubGateAuthority(native.backend, native.manifest, native.policy)
        actual = authority.bundle("WP-003", Identity(pr_number=123))
        self.assertEqual(actual.evidence, value.evidence)
        self.assertFalse(any(call[0] != "GET" and not call[3] for call in api.calls))
        with self.assertRaisesRegex(PortError, "merged implementation"):
            verify_completion(actual.pr, actual.evidence, actual.review, actual.approvals,
                              actual.binding, native.manifest, native.policy)

    def test_current_policy_requires_one_authentic_published_review_check(self):
        native, api, prefix, value = self.fixture(current=True)
        checks = api.routes[("GET", prefix + f"/commits/{HEAD}/check-runs?per_page=100&page=1")]["check_runs"]
        checks[:] = [check for check in checks if check["name"] != "gate-integrity"]
        authority = GitHubGateAuthority(native.backend, native.manifest, native.policy)
        proof = authority.reviewed_candidate("WP-003", Identity(pr_number=123), BASE)
        self.assertEqual(proof["binding"], asdict(value.binding))
        self.assertEqual(proof["ci_run_ids"], [1])
        checks[-1]["conclusion"] = "failure"
        with self.assertRaisesRegex(PortError, "authenticated default-branch publisher"):
            authority.reviewed_candidate("WP-003", Identity(pr_number=123), BASE)
        checks.pop()
        with self.assertRaisesRegex(PortError, "Missing native project check: parity-review"):
            authority.reviewed_candidate("WP-003", Identity(pr_number=123), BASE)

    def test_current_review_check_rejects_forged_and_stale_publisher_provenance(self):
        for kind in ("app", "binding", "run", "review"):
            native, api, prefix, _ = self.fixture(current=True)
            checks = api.routes[("GET", prefix + f"/commits/{HEAD}/check-runs?per_page=100&page=1")]["check_runs"]
            checks[:] = [check for check in checks if check["name"] != "gate-integrity"]
            if kind == "app":
                checks[-1]["app"]["id"] = 999
            elif kind == "binding":
                checks[-1]["external_id"] = "stale"
            elif kind == "run":
                api.routes[("GET", prefix + "/actions/runs/2")]["head_sha"] = HEAD
            else:
                self.mutate_receipt(api, prefix, lambda receipt: receipt["review"].update(summary="edited"))
            authority = GitHubGateAuthority(native.backend, native.manifest, native.policy)
            with self.subTest(kind=kind), self.assertRaises(PortError):
                authority.reviewed_candidate("WP-003", Identity(pr_number=123), BASE)

    def test_current_publisher_creates_only_the_review_check(self):
        native, api, prefix, _ = self.fixture(current=True)
        api.routes[("GET", prefix + "/actions/runs/2")]["status"] = "in_progress"
        api.routes[("POST", prefix + "/check-runs")] = {"id": 201}
        api.routes[("PATCH", prefix + "/check-runs/201")] = {}
        with patch("controller.native_review.git", return_value=BASE.encode()):
            native.publish("WP-003", 123, 90, 2, 1)
        writes = [call for call in api.calls if call[0] == "POST" and call[1].endswith("/check-runs")]
        self.assertEqual([call[2]["name"] for call in writes], ["parity-review"])
        self.assertEqual(
            [call[2]["conclusion"] for call in api.calls if call[0] == "PATCH"], ["success"],
        )

    def normalized_publication(self, *, current=True):
        native, api, prefix, value = self.fixture(current=current)
        run = api.routes[("GET", prefix + "/actions/runs/2")]
        run["status"] = "in_progress"
        checks = api.routes[("GET", prefix + f"/commits/{HEAD}/check-runs?per_page=100&page=1")]["check_runs"]
        checks[:] = [check for check in checks if check["name"] == "android-ci"]
        next_id = iter((201, 202))

        def create(payload):
            check_id = next(next_id)
            check = {
                **copy.deepcopy(payload), "id": check_id, "app": {"id": 10},
                "details_url": f"https://github.com/{native.policy['repository']}/runs/{check_id}",
            }
            checks.append(check)
            api.routes[("PATCH", prefix + f"/check-runs/{check_id}")] = (
                lambda update: check.update(copy.deepcopy(update)) or copy.deepcopy(check)
            )
            return copy.deepcopy(check)

        api.routes[("POST", prefix + "/check-runs")] = create
        stdout = io.StringIO()
        with patch("controller.native_review.git", return_value=BASE.encode()), patch("sys.stdout", stdout):
            native.publish("WP-003", 123, 90, 2, 1)
        run["status"] = "completed"
        log = f"Native review WP-003 PR 123 receipt 90\t{PUBLICATION_STEP}\t2026-11-02T18:25:20Z {stdout.getvalue()}"
        return native, api, prefix, value, checks, log

    def test_normalized_server_checks_roundtrip_only_with_authenticated_job_receipt(self):
        for current in (True, False):
            with self.subTest(current=current):
                native, api, _, value, checks, log = self.normalized_publication(current=current)
                self.assertIn(PUBLICATION_MARKER, log)
                self.assertTrue(all("/runs/" in check["details_url"] for check in checks[1:]))
                with patch("controller.repair_acceptance.read_job_log", return_value=log) as reader:
                    actual = GitHubGateAuthority(native.backend, native.manifest, native.policy).reviewed_candidate(
                        "WP-003", Identity(pr_number=123), BASE,
                    )
                reader.assert_called_once()
                self.assertIs(reader.call_args.args[0].backend, native.backend)
                self.assertEqual(reader.call_args.args[1], 80)
                self.assertEqual(actual["binding"], asdict(value.binding))
                post = next(call for call in api.calls if call[0] == "POST" and call[1].endswith("/check-runs"))
                self.assertEqual(post[2]["details_url"],
                                 f"https://github.com/{value.binding.repository}/actions/runs/2")

    def test_normalized_url_alone_or_forged_job_receipt_cannot_establish_provenance(self):
        for kind in ("missing", "duplicate", "wrong-step", "wrong-check", "wrong-result",
                     "wrong-job", "wrong-url", "foreign-url", "wrong-app", "wrong-external",
                     "wrong-head", "failed-run", "stale-attempt", "changed-summary", "unreadable"):
            with self.subTest(kind=kind):
                native, api, prefix, _, checks, log = self.normalized_publication()
                if kind == "missing":
                    log = ""
                elif kind == "duplicate":
                    log *= 2
                elif kind == "wrong-step":
                    log = log.replace(PUBLICATION_STEP, "Fetch candidate objects as data only")
                elif kind == "wrong-check":
                    log = log.replace('"check_ids":[201]', '"check_ids":[202]')
                elif kind == "wrong-result":
                    log = log.replace('"result_sha256":"', '"result_sha256":"0')
                elif kind == "wrong-job":
                    api.routes[("GET", prefix + "/actions/runs/2/attempts/1/jobs?per_page=100&page=1")]["jobs"][0]["run_id"] = 3
                elif kind in ("wrong-url", "foreign-url"):
                    checks[-1]["details_url"] = (
                        f"https://github.com/{native.policy['repository']}/runs/202" if kind == "wrong-url"
                        else "https://github.com/other/repo/runs/201"
                    )
                elif kind == "wrong-app":
                    checks[-1]["app"]["id"] = 999
                elif kind == "wrong-external":
                    checks[-1]["external_id"] = "copied:3:1"
                elif kind == "wrong-head":
                    checks[-1]["head_sha"] = BASE
                elif kind == "failed-run":
                    api.routes[("GET", prefix + "/actions/runs/2")]["conclusion"] = "failure"
                elif kind == "stale-attempt":
                    api.routes[("GET", prefix + "/actions/runs/2")]["run_attempt"] = 2
                elif kind == "changed-summary":
                    checks[-1]["output"]["summary"] += " "
                with patch("controller.repair_acceptance.read_job_log", return_value=log,
                           side_effect=PortError("Hosted log reader unavailable") if kind == "unreadable" else None), \
                        self.assertRaises((PortError, json.JSONDecodeError)):
                    native.bundle("WP-003", Identity(pr_number=123))

    def test_partial_terminal_publication_never_emits_a_check_receipt(self):
        native, api, prefix, _ = self.fixture()
        api.routes[("GET", prefix + "/actions/runs/2")]["status"] = "in_progress"
        ids = iter((201, 202))
        api.routes[("POST", prefix + "/check-runs")] = lambda payload: {"id": next(ids)}
        api.routes[("PATCH", prefix + "/check-runs/201")] = {}

        def failed_write(payload):
            raise PortError("Terminal check write refused")

        api.routes[("PATCH", prefix + "/check-runs/202")] = failed_write
        stdout = io.StringIO()
        with patch("controller.native_review.git", return_value=BASE.encode()), patch("sys.stdout", stdout), \
                self.assertRaisesRegex(PortError, "Terminal check write refused"):
            native.publish("WP-003", 123, 90, 2, 1)
        self.assertNotIn(PUBLICATION_MARKER, stdout.getvalue())

    def test_outstanding_review_request_cannot_publish_success(self):
        native, api, prefix, _ = self.fixture(current=True)
        self.reviews(api, prefix).append({
            "id": 91, "state": "CHANGES_REQUESTED", "commit_id": HEAD,
            "user": {"login": "other-reviewer", "type": "User"},
            "body": "Please fix this.", "submitted_at": "2026-10-10T10:00:00Z",
        })
        with self.assertRaisesRegex(PortError, "outstanding requested changes"):
            native.evaluate("WP-003", 123, 90)
        api.routes[("GET", prefix + "/actions/runs/2")]["status"] = "in_progress"
        api.routes[("POST", prefix + "/check-runs")] = {"id": 201}
        api.routes[("PATCH", prefix + "/check-runs/201")] = {}
        with patch("controller.native_review.git", return_value=BASE.encode()), \
                self.assertRaisesRegex(PortError, "outstanding requested changes"):
            native.publish("WP-003", 123, 90, 2, 1)
        writes = [call for call in api.calls if call[0] == "PATCH"]
        self.assertEqual([call[2]["conclusion"] for call in writes], ["failure"])

    def test_plain_feedback_bot_unregistered_dismissed_wrong_head_and_wrong_pr_are_not_receipts(self):
        for kind in ("plain", "bot", "login", "dismissed", "head", "id", "marker"):
            native, api, prefix, _ = self.fixture()
            review = self.reviews(api, prefix)[0]
            if kind == "plain":
                review["body"] = "Honest bounded source feedback; not project acceptance."
            elif kind == "bot":
                review["user"]["type"] = "Bot"
            elif kind == "login":
                review["user"]["login"] = "candidate"
            elif kind == "dismissed":
                review["state"] = "DISMISSED"
            elif kind == "head":
                review["commit_id"] = BASE
            elif kind == "id":
                review["id"] = 91
            else:
                api.routes[("GET", prefix + "/pulls/123")]["body"] = marker("WP-002")
            with self.subTest(kind=kind), self.assertRaises(PortError):
                native.evaluate("WP-003", 123, 90)

    def test_all_binding_dimensions_and_complete_staging_are_enforced(self):
        for field in ("repository", "work_package", "base_sha", "head_sha", "source_sha",
                      "manifest_sha256", "policy_revision", "staging"):
            native, api, prefix, _ = self.fixture()
            def mutate(payload):
                if field == "staging":
                    payload["staging_sha256"] = "0" * 64
                else:
                    payload["binding"][field] = {
                        "repository": "wrong/repo", "work_package": "WP-002",
                    }.get(field, "0" * (64 if field in ("manifest_sha256", "policy_revision") else 40))
            self.mutate_receipt(api, prefix, mutate)
            with self.subTest(field=field), self.assertRaises(PortError):
                native.evaluate("WP-003", 123, 90)

    def test_failed_skipped_cancelled_in_progress_zero_suite_wrong_workflow_base_and_attempt_block(self):
        for kind in ("failure", "skipped", "cancelled", "in_progress", "suite", "workflow", "base", "attempt",
                     "repository", "check", "check-suite", "event"):
            native, api, prefix, _ = self.fixture()
            run = api.routes[("GET", prefix + "/actions/runs/1")]
            if kind in ("failure", "skipped", "cancelled"):
                run["conclusion"] = kind
            elif kind == "in_progress":
                run["status"] = kind
            elif kind == "suite":
                run["check_suite_id"] = 0
            elif kind == "workflow":
                run["path"] = WORKFLOW_PATH
            elif kind == "base":
                run["pull_requests"][0]["base"]["sha"] = MERGE
            elif kind == "attempt":
                run["run_attempt"] = 2
            elif kind == "repository":
                run["repository"]["full_name"] = "wrong/repo"
            elif kind == "event":
                run["event"] = "push"
            else:
                checks = api.routes[("GET", prefix + f"/commits/{HEAD}/check-runs?per_page=100&page=1")]["check_runs"]
                checks[0]["conclusion" if kind == "check" else "check_suite"] = "failure" if kind == "check" else {"id": 999}
            with self.subTest(kind=kind), self.assertRaises(PortError):
                native.evaluate("WP-003", 123, 90)

    def test_stale_current_base_open_draft_fork_and_server_path_omission_block(self):
        for kind in ("current-base", "draft", "closed", "fork", "paths"):
            native, api, prefix, _ = self.fixture()
            pr = api.routes[("GET", prefix + "/pulls/123")]
            if kind == "current-base":
                api.routes[("GET", prefix + "/git/ref/heads/main")]["object"]["sha"] = MERGE
            elif kind == "draft":
                pr["draft"] = True
            elif kind == "closed":
                pr["state"] = "closed"
            elif kind == "fork":
                pr["base"]["repo"]["full_name"] = "wrong/repo"
            else:
                api.routes[("GET", prefix + "/pulls/123/files?per_page=100&page=1")] = []
            with self.subTest(kind=kind), self.assertRaises(PortError):
                native.evaluate("WP-003", 123, 90)

    def test_new_feedback_and_unresolved_inline_findings_are_not_silently_accepted(self):
        for kind in ("COMMENTED", "CHANGES_REQUESTED", "thread", "graphql-error", "missing-threads"):
            native, api, prefix, _ = self.fixture()
            if kind in ("COMMENTED", "CHANGES_REQUESTED"):
                self.reviews(api, prefix).append({
                    "id": 100, "state": kind, "commit_id": HEAD, "user": {"login": "cbattlegear"},
                    "body": "Unresolved native behavior finding.", "submitted_at": "2026-10-10T12:00:00Z",
                })
            elif kind == "thread":
                api.routes[("POST", "/graphql")]["data"]["repository"]["pullRequest"]["reviewThreads"]["nodes"] = [
                    {"isResolved": False},
                ]
            else:
                api.routes[("POST", "/graphql")] = {"errors": ["unavailable"]} if kind == "graphql-error" else {}
            with self.subTest(kind=kind), self.assertRaises(PortError):
                native.evaluate("WP-003", 123, 90)

    def test_conditional_human_acceptance_is_actual_bound_and_not_replaced_by_source_review(self):
        native, api, prefix, value = self.fixture()
        api.routes[("GET", prefix + "/pulls/123")]["labels"] = [{"name": "needs-human"}]
        with self.assertRaisesRegex(PortError, "human acceptance"):
            native.evaluate("WP-003", 123, 90)
        approval = {
            "kind": "human-acceptance", "review_id": 99, "reviewer": "cbattlegear", "state": "COMMENTED",
            "head_sha": HEAD, "base_sha": BASE, "policy_revision": value.binding.policy_revision,
            "binding_key": value.binding.key,
        }
        self.mutate_receipt(api, prefix, lambda p: p.update(approvals=[approval]))
        self.reviews(api, prefix).append({
            "id": 99, "state": "COMMENTED", "commit_id": HEAD, "user": {"login": "cbattlegear"},
            "body": f"<!-- android-port-acceptance:{value.binding.key} -->",
            "submitted_at": "2026-10-10T12:00:00Z",
        })
        self.assertTrue(native.evaluate("WP-003", 123, 90)[0].authoritative)
        self.reviews(api, prefix)[1]["body"] = "Not an acceptance."
        with self.assertRaises(PortError):
            native.evaluate("WP-003", 123, 90)

    def test_missing_acceptance_native_failures_and_uninventoried_original_cases_block(self):
        for kind in ("acceptance", "finding", "blocker", "case", "unknown-evidence", "duplicate-ci-results"):
            native, api, prefix, _ = self.fixture()
            def mutate(payload):
                if kind == "acceptance":
                    payload["evidence"]["acceptance"] = []
                elif kind == "finding":
                    payload["review"]["verdict"] = "CHANGES_REQUESTED"
                elif kind == "blocker":
                    payload["review"]["blockers"] = ["Native evidence is missing"]
                elif kind == "case":
                    payload["evidence"]["source_tests"] = [{"path": "fake.swift"}]
                elif kind == "unknown-evidence":
                    payload["review"]["acceptance"][0]["evidence"] = ["made-up-native-result"]
                else:
                    payload["evidence"]["runs"][0]["test_count"] = 1
            self.mutate_receipt(api, prefix, mutate)
            with self.subTest(kind=kind), self.assertRaises(PortError):
                native.evaluate("WP-003", 123, 90)

    def test_counterfeit_checks_failed_publisher_candidate_branch_and_edited_review_are_rejected(self):
        for kind in ("app", "external", "summary", "run", "branch", "actor", "rerun", "head", "edited",
                     "another-pr-job", "failed-job"):
            native, api, prefix, _ = self.fixture()
            checks = api.routes[("GET", prefix + f"/commits/{HEAD}/check-runs?per_page=100&page=1")]["check_runs"]
            run = api.routes[("GET", prefix + "/actions/runs/2")]
            if kind == "app":
                checks[-1]["app"]["id"] = 999
            elif kind == "external":
                checks[-1]["external_id"] = "invented"
            elif kind == "summary":
                checks[-1]["output"]["summary"] = '{"verdict":"PASS"}'
            elif kind == "run":
                run["conclusion"] = "failure"
            elif kind == "branch":
                run["head_branch"] = "candidate"
            elif kind in ("actor", "rerun"):
                run["actor" if kind == "actor" else "triggering_actor"]["login"] = "candidate"
            elif kind == "head":
                run["head_sha"] = HEAD
            elif kind == "edited":
                self.mutate_receipt(api, prefix, lambda p: p["review"].update(summary="Edited after publication"))
            else:
                job = api.routes[("GET", prefix + "/actions/runs/2/attempts/1/jobs?per_page=100&page=1")]["jobs"][0]
                job["name" if kind == "another-pr-job" else "conclusion"] = (
                    "Native review WP-003 PR 124 receipt 90" if kind == "another-pr-job" else "failure"
                )
            with self.subTest(kind=kind), self.assertRaises(PortError):
                native.bundle("WP-003", Identity(pr_number=123))

    def test_publication_is_exact_default_branch_and_rechecks_freshness_before_success(self):
        native, api, prefix, _ = self.fixture()
        api.routes[("GET", prefix + "/actions/runs/2")]["status"] = "in_progress"
        next_id = iter((201, 202))
        api.routes[("POST", prefix + "/check-runs")] = lambda p: {"id": next(next_id)}
        for check_id in (201, 202):
            api.routes[("PATCH", prefix + f"/check-runs/{check_id}")] = {}
        with patch("controller.native_review.git", return_value=BASE.encode()):
            result = native.publish("WP-003", 123, 90, 2, 1)
        writes = [call for call in api.calls if call[0] == "PATCH"]
        self.assertEqual(len(writes), 2)
        self.assertTrue(all(call[2]["conclusion"] == "success" for call in writes))
        self.assertEqual(result["publisher_run_id"], 2)
        self.assertNotIn("test_count", json.dumps(result))
        self.assertNotIn("catalog", json.dumps(result))

    def test_invalid_receipt_publishes_failed_not_green_checks(self):
        native, api, prefix, _ = self.fixture()
        api.routes[("GET", prefix + "/actions/runs/2")]["status"] = "in_progress"
        self.reviews(api, prefix)[0]["body"] = "Bounded source feedback only"
        next_id = iter((201, 202))
        api.routes[("POST", prefix + "/check-runs")] = lambda p: {"id": next(next_id)}
        for check_id in (201, 202):
            api.routes[("PATCH", prefix + f"/check-runs/{check_id}")] = {}
        with patch("controller.native_review.git", return_value=BASE.encode()), self.assertRaises(PortError):
            native.publish("WP-003", 123, 90, 2, 1)
        writes = [call for call in api.calls if call[0] == "PATCH"]
        self.assertEqual(len(writes), 2)
        self.assertTrue(all(call[2]["conclusion"] == "failure" for call in writes))

    def test_deferred_import_failure_completes_created_checks_and_preserves_exception(self):
        native, api, prefix, _ = self.fixture(current=True)
        api.routes[("GET", prefix + "/actions/runs/2")]["status"] = "in_progress"
        ids = iter((201,))
        api.routes[("POST", prefix + "/check-runs")] = lambda payload: {"id": next(ids)}
        api.routes[("PATCH", prefix + "/check-runs/201")] = {}
        error = ImportError("attempted relative import with no known parent package")

        def deferred_import(*args):
            raise error

        traceback_names = []
        with patch("controller.native_review.git", return_value=BASE.encode()), \
                patch.object(native, "evaluate", side_effect=deferred_import):
            try:
                native.publish("WP-003", 123, 90, 2, 1)
            except ImportError as raised:
                self.assertIs(raised, error)
                traceback = raised.__traceback__
                while traceback is not None:
                    traceback_names.append(traceback.tb_frame.f_code.co_name)
                    traceback = traceback.tb_next
            else:
                self.fail("Publisher must rethrow the original import failure")
        self.assertIn("deferred_import", traceback_names)
        created = [call for call in api.calls if call[0] == "POST"]
        writes = [call for call in api.calls if call[0] == "PATCH"]
        self.assertEqual(len(created), 1)
        self.assertEqual({call[1] for call in writes}, {prefix + "/check-runs/201"})
        self.assertEqual(len(writes), 1)
        for call in writes:
            self.assertEqual(call[2]["status"], "completed")
            self.assertEqual(call[2]["conclusion"], "failure")
            self.assertEqual(call[2]["output"]["summary"], str(error))

    def test_publisher_authority_is_server_bound_not_a_worktree_lease(self):
        native, api, prefix, _ = self.fixture()
        api.routes[("GET", prefix + "/actions/runs/2")]["status"] = "in_progress"
        ids = iter((201, 202))
        api.routes[("POST", prefix + "/check-runs")] = lambda payload: {"id": next(ids)}
        for number in (201, 202):
            api.routes[("PATCH", prefix + f"/check-runs/{number}")] = {}
        with patch("controller.native_review.git", return_value=BASE.encode()), \
                patch.object(Ledger, "__init__", side_effect=AssertionError("Local ledger is not hosted authority")):
            native.publish("WP-003", 123, 90, 2, 1)
        self.assertEqual(len([call for call in api.calls
                              if call[0] == "PATCH" and call[2]["conclusion"] == "success"]), 2)

    def test_changed_server_head_base_or_closed_candidate_cannot_receive_success(self):
        for kind in ("head", "base", "closed"):
            native, api, prefix, _ = self.fixture()
            api.routes[("GET", prefix + "/actions/runs/2")]["status"] = "in_progress"
            ids = iter((201, 202))
            api.routes[("POST", prefix + "/check-runs")] = lambda payload: {"id": next(ids)}
            for number in (201, 202):
                api.routes[("PATCH", prefix + f"/check-runs/{number}")] = {}
            reader = native.evaluate
            def evaluate(*args):
                result = reader(*args)
                pr = api.routes[("GET", prefix + "/pulls/123")]
                if kind == "head":
                    pr["head"]["sha"] = "a" * 40
                elif kind == "base":
                    api.routes[("GET", prefix + "/git/ref/heads/main")]["object"]["sha"] = "a" * 40
                else:
                    pr["state"] = "closed"
                return result
            with self.subTest(kind=kind), patch("controller.native_review.git", return_value=BASE.encode()), \
                    patch.object(native, "evaluate", side_effect=evaluate), self.assertRaises(PortError):
                native.publish("WP-003", 123, 90, 2, 1)
            self.assertFalse(any(call[0] == "PATCH" and call[2]["conclusion"] == "success"
                                 for call in api.calls))

    def test_mid_publication_new_feedback_never_publishes_success(self):
        native, api, prefix, _ = self.fixture()
        api.routes[("GET", prefix + "/actions/runs/2")]["status"] = "in_progress"
        next_id = iter((201, 202))
        api.routes[("POST", prefix + "/check-runs")] = lambda p: {"id": next(next_id)}
        for check_id in (201, 202):
            api.routes[("PATCH", prefix + f"/check-runs/{check_id}")] = {}
        reader = native.evaluate
        def evaluate(*args):
            value = reader(*args)
            self.reviews(api, prefix).append({
                "id": 100, "state": "COMMENTED", "commit_id": HEAD,
                "user": {"login": "cbattlegear"}, "body": "New blocker",
                "submitted_at": "2026-10-10T12:00:00Z",
            })
            return value
        with patch("controller.native_review.git", return_value=BASE.encode()), \
                patch.object(native, "evaluate", side_effect=evaluate), self.assertRaises(PortError):
            native.publish("WP-003", 123, 90, 2, 1)
        self.assertFalse(any(call[0] == "PATCH" and call[2]["conclusion"] == "success" for call in api.calls))

    def test_manual_publication_outside_actions_is_blocked_before_mutation(self):
        native, api, _, _ = self.fixture()
        with patch("controller.native_review.trusted_inputs", return_value=(native.manifest, native.policy)), \
                patch("controller.native_review.CloudBackend", return_value=native.backend), \
                patch.dict("os.environ", {"ANDROID_PORT_GITHUB_TOKEN": "fixture-not-a-credential",
                                          "GITHUB_ACTIONS": "false"}):
            self.assertEqual(main(["publish", "--trusted-base", BASE, "--wp", "WP-003",
                                   "--pr", "123", "--review-id", "90"]), 2)
        self.assertFalse(any(call[0] != "GET" for call in api.calls))

    def test_merged_native_completion_preserves_existing_accepted_receipt(self):
        from pathlib import Path

        native, api, prefix, _ = self.fixture()
        api.routes[("GET", prefix + "/pulls/123")].update(merged=True, state="closed", merge_commit_sha=MERGE)
        for parents in ([{"sha": BASE}, {"sha": HEAD}], [{"sha": BASE}]):
            api.routes[("GET", prefix + "/git/commits/" + MERGE)] = {"sha": MERGE, "parents": parents}
            with self.subTest(parents=parents), tempfile.TemporaryDirectory() as directory:
                ledger = Ledger(Path(directory) / "ledger.sqlite3", native.policy["repository"])
                authority = GitHubGateAuthority(native.backend, native.manifest, native.policy)
                controller = Controller(native.manifest, native.policy, settings(ledger.path),
                                        ledger, native.backend, authority)
                proof = controller.import_supervised_completion("WP-003", Identity(pr_number=123))
                self.assertEqual(controller.completed("WP-003"), proof)
                self.assertEqual(ledger.get("WP-003")["state"], "completed")
                original_body = self.reviews(api, prefix)[0]["body"]
                self.mutate_receipt(api, prefix, lambda p: p["review"].update(summary="Changed"))
                self.assertEqual(controller.completed("WP-003"), proof)
                self.reviews(api, prefix)[0]["body"] = original_body

    def test_native_acceptance_does_not_enable_paused_merges_or_waive_branch_rule_proof(self):
        native, _, _, _ = self.fixture()
        value, _ = native.evaluate("WP-003", 123, 90)
        with self.assertRaisesRegex(PortError, "Paused/off"):
            merge_decision(value.pr, value.evidence, value.review, value.approvals,
                           value.binding, native.manifest, native.policy, BASE)
        reasons = settings(None).blockers(native.policy, "local", needs_cloud_auth=False)
        self.assertTrue(any("no-bypass" in reason for reason in reasons))
        self.assertTrue(any("paused" in reason for reason in reasons))

    def test_retained_artifacts_bind_actual_candidate_bytes(self):
        native, api, prefix, _ = self.fixture()
        evidence_bytes = b"fixture-only retained human observation\n"
        item = {"path": "docs/android/evidence/WP-003/human.json",
                "sha256": hashlib.sha256(evidence_bytes).hexdigest()}
        self.mutate_receipt(api, prefix, lambda p: p["evidence"].update(retained_artifacts=[item]))
        with patch("controller.native_review.tree", return_value={item["path"]: "4" * 40}), \
                patch("controller.native_review.object_bytes", return_value=evidence_bytes):
            self.assertTrue(native.evaluate("WP-003", 123, 90)[0].authoritative)
        with patch("controller.native_review.tree", return_value={item["path"]: "4" * 40}), \
                patch("controller.native_review.object_bytes", return_value=b"different"):
            with self.assertRaisesRegex(PortError, "evidence bytes"):
                native.evaluate("WP-003", 123, 90)


class HostedAcceptanceTests(unittest.TestCase):
    def test_original_case_parameter_families_cannot_be_dropped_or_replaced_by_green_ci(self):
        manifest = test_manifest("WP-101")
        rules = policy()
        value = bundle(manifest, rules, "WP-101")
        url = f"https://github.com/{rules['repository']}/actions/runs/1/attempts/1"
        evidence = {
            "schema_version": 2, **asdict(value.binding), "changed_paths": value.pr["changed_paths"],
            "runs": [{"check": "android-ci", "run_id": 1, "run_attempt": 1}], "retained_artifacts": [],
            "acceptance": [{**entry, "evidence": [url]} for entry in value.evidence["acceptance"]],
            "source_tests": copy.deepcopy(value.evidence["source_tests"]),
        }
        for original in evidence["source_tests"]:
            for case in original["cases"]:
                case["evidence"] = [url]
        validate_evidence(evidence, value.binding, manifest, rules, value.catalog)
        for kind in ("missing-original", "missing-family", "failed", "skipped", "catalog"):
            changed = copy.deepcopy(evidence)
            catalog = copy.deepcopy(value.catalog)
            if kind == "missing-original":
                changed["source_tests"] = []
            elif kind == "catalog":
                catalog["source_sha"] = "0" * 40
            else:
                case = next(case for result in changed["source_tests"] for case in result["cases"])
                if kind == "missing-family":
                    case["parameter_family"] = "invented"
                else:
                    case["result"] = kind.upper()
            with self.subTest(kind=kind), self.assertRaises(PortError):
                validate_evidence(changed, value.binding, manifest, rules, catalog)


class NativePublisherWorkflowTests(unittest.TestCase):
    def test_exact_manual_workflow_and_execution_boundary_mutations(self):
        from fixtures import REPO

        text = (REPO / WORKFLOW_PATH).read_text(encoding="utf-8")
        original = parse_yaml(text)
        validate_publisher(original, text)
        for kind in ("event", "permissions", "candidate-checkout", "command", "token", "cache",
                     "condition", "cancel", "defaults", "step-env"):
            value = copy.deepcopy(original)
            job = value["jobs"]["publish"]
            if kind == "event":
                value["on"]["pull_request"] = {}
            elif kind == "permissions":
                value["permissions"]["contents"] = "write"
            elif kind == "candidate-checkout":
                job["steps"][0]["with"]["ref"] = "${{ inputs.candidate_sha }}"
            elif kind == "command":
                job["steps"][2]["run"] += "git checkout FETCH_HEAD\n"
            elif kind == "token":
                job["env"]["ANDROID_PORT_GITHUB_TOKEN"] = "${{ github.token }}"
            elif kind == "cache":
                job["steps"].append({"uses": "actions/cache@v4"})
            elif kind == "condition":
                job["if"] = "always()"
            elif kind == "cancel":
                value["concurrency"]["cancel-in-progress"] = "true"
            elif kind == "defaults":
                value["defaults"] = {"run": {"working-directory": "candidate"}}
            else:
                job["steps"][2]["env"] = {"BASH_ENV": "candidate.sh"}
            with self.subTest(kind=kind), self.assertRaises(PortError):
                validate_publisher(value, text)
