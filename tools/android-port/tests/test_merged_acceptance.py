"""AndroidOnly: WP-003 Actual-merge acceptance and exact-candidate checks."""

import copy
import hashlib
import json
import os
import subprocess
import tempfile
import unittest
from contextlib import nullcontext
from dataclasses import replace
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from fixtures import BASE, HEAD, MERGE, FakeApi, policy, settings, test_manifest
from controller.authority import GitHubGateAuthority
from controller.backends import CloudBackend
from controller.errors import PortError
from controller.gates import Binding, policy_revision
from controller.ledger import Identity, validate_merged_implementation
from controller.merged_acceptance import (
    BoundedRepair, HumanGateRequired, MergedAcceptance, NoImplementationFound,
    declares_completion,
)
from controller.model import REFERENCE_SHA, git
from controller.native_review import PUBLICATION_MARKER, PUBLICATION_STEP, RESULT_MARKER, REVIEW_MARKER, WORKFLOW_PATH
from controller.render import marker


class MergedAcceptanceTests(unittest.TestCase):
    def fixture(self, *, merged=True, current=False, legacy_native=False, human_required=False):
        manifest = test_manifest("WP-101")
        rules = policy()
        if current or not merged:
            rules["required_checks"] = ["android-ci", "parity-review"]
        rules["trusted_gate_publisher_app_id"] = None
        if human_required:
            rules["review_policy"]["human_acceptance_work_packages"].append("WP-101")
        prefix = "/repos/" + rules["repository"]
        pr = {
            "number": 123, "title": "[WP-101] Port fixture behavior",
            "body": "A port of **WP-101**: fixture behavior.\n"
                    "This targets WP-101's behavior, boundaries and source-test-parity acceptance.\n"
                    + ", ".join(item["id"] for item in manifest.wp("WP-101")["acceptance"])
                    + "\n" + marker("WP-101"),
            "state": "closed" if merged else "open", "draft": False,
            "merged": merged, "merged_at": "2026-10-10T12:00:00Z" if merged else None,
            "merged_by": {"login": "cbattlegear", "type": "User"} if merged else None,
            "merge_commit_sha": MERGE if merged else None,
            "base": {"sha": BASE, "ref": "main", "repo": {"full_name": rules["repository"]}},
            "head": {"sha": HEAD}, "labels": [], "user": {"login": "fixture-worker", "type": "User"},
        }
        review = {
            "id": 90, "state": "APPROVED", "commit_id": HEAD,
            "submitted_at": "2026-10-10T11:00:00Z",
            "user": {"login": "cbattlegear", "type": "User"}, "body": "Reviewed the implementation.",
        }
        check = {
            "id": 100, "name": "android-ci", "head_sha": HEAD, "status": "completed",
            "conclusion": "success", "started_at": "2026-10-10T11:15:00Z",
            "completed_at": "2026-10-10T11:30:00Z",
            "details_url": f"https://github.com/{rules['repository']}/actions/runs/1/job/200",
            "app": {"id": 10}, "check_suite": {"id": 71},
        }
        run = {
            "id": 1, "run_attempt": 1, "workflow_id": 21, "check_suite_id": 71,
            "path": ".github/workflows/android-ci.yml", "head_sha": HEAD, "event": "pull_request",
            "status": "completed", "conclusion": "success",
            "repository": {"full_name": rules["repository"]}, "pull_requests": [],
        }
        api = FakeApi({
            ("GET", prefix + "/pulls/123"): pr,
            ("POST", "/graphql"): lambda _: {"data": {"repository": {"pullRequest": {
                "number": 123, "body": pr["body"], "lastEditedAt": None,
            }}}},
            ("GET", prefix + "/pulls?state=closed&per_page=100&page=1"): [pr] if merged else [],
            ("GET", prefix + "/git/commits/" + MERGE): {
                "sha": MERGE, "parents": [{"sha": BASE}, {"sha": HEAD}],
            },
            ("GET", prefix + "/pulls/123/files?per_page=100&page=1"): [
                {"filename": "android/core/protocol/src/main/Fixture.kt"},
            ],
            ("GET", prefix + "/pulls/123/reviews?per_page=100&page=1"): [review],
            ("GET", prefix + f"/commits/{HEAD}/check-runs?filter=all&per_page=100&page=1"): {
                "check_runs": [check],
            },
            ("GET", prefix + f"/commits/{HEAD}/check-runs?per_page=100&page=1"): {
                "check_runs": [check],
            },
            ("GET", prefix + f"/commits/{HEAD}/pulls?per_page=100&page=1"): [{"number": 123}],
            ("GET", prefix + "/actions/jobs/200"): {
                "id": 200, "run_id": 1, "run_attempt": 1, "name": "android-ci",
                "head_sha": HEAD, "status": "completed", "conclusion": "success",
                "check_run_url": f"https://api.github.com{prefix}/check-runs/100",
            },
            ("GET", prefix + "/actions/runs/1/attempts/1"): run,
            ("GET", prefix + "/check-suites/71"): {
                "id": 71, "head_sha": HEAD, "app": {"id": 10, "slug": "github-actions"},
            },
            ("GET", prefix + "/actions/runs/1/attempts/1/jobs?per_page=100&page=1"): {"jobs": []},
        })
        if current or legacy_native:
            binding = Binding(rules["repository"], "WP-101", BASE, HEAD, REFERENCE_SHA,
                              manifest.sha256, policy_revision(manifest, rules))
            receipt = {
                "schema_version": 1, "binding": binding.__dict__,
                "method": "authenticated-coordinator-read-only", "staging_sha256": "b" * 64,
                "review": {}, "evidence": {"schema_version": 2}, "approvals": [],
            }
            if human_required:
                receipt["approvals"].append({
                    "kind": "human-acceptance", "review_id": 91, "reviewer": "cbattlegear",
                    "state": "COMMENTED", "head_sha": HEAD, "base_sha": BASE,
                    "policy_revision": binding.policy_revision, "binding_key": binding.key,
                })
                api.routes[("GET", prefix + "/pulls/123/reviews?per_page=100&page=1")].append({
                    "id": 91, "state": "COMMENTED", "commit_id": HEAD,
                    "submitted_at": "2026-10-10T11:10:00Z",
                    "user": {"login": "cbattlegear", "type": "User"},
                    "body": f"<!-- android-port-acceptance:{binding.key} -->",
                })
            review.update(state="COMMENTED", body=REVIEW_MARKER + json.dumps(receipt))
            published = {
                "binding": binding.__dict__, "pr_number": 123, "review_id": 90,
                "review_body_sha256": hashlib.sha256(review["body"].encode()).hexdigest(),
                "publisher_run_id": 2, "publisher_run_attempt": 1,
            }
            api.routes[("GET", prefix + f"/commits/{HEAD}/check-runs?filter=all&per_page=100&page=1")]["check_runs"].append({
                "id": 101, "name": "parity-review", "head_sha": HEAD, "status": "completed",
                "conclusion": "success", "started_at": "2026-10-10T11:35:00Z",
                "completed_at": "2026-10-10T11:45:00Z", "app": {"id": 10},
                "external_id": f"{binding.key}:2:1",
                "details_url": f"https://github.com/{rules['repository']}/actions/runs/2",
                "output": {"summary": RESULT_MARKER + json.dumps(published)},
            })
            if legacy_native:
                checks = api.routes[("GET", prefix + f"/commits/{HEAD}/check-runs?filter=all&per_page=100&page=1")]["check_runs"]
                checks.append({**copy.deepcopy(checks[-1]), "id": 103, "name": "gate-integrity"})
            api.routes[("GET", prefix + "/actions/runs/2/attempts/1")] = {
                "id": 2, "run_attempt": 1, "workflow_id": 22, "check_suite_id": 72,
                "path": WORKFLOW_PATH, "head_sha": BASE, "head_branch": "main", "event": "workflow_dispatch",
                "status": "completed", "conclusion": "success",
                "actor": {"login": "cbattlegear"}, "triggering_actor": {"login": "cbattlegear"},
                "repository": {"full_name": rules["repository"]},
            }
            api.routes[("GET", prefix + "/actions/workflows/android-parity-review.yml")] = {
                "id": 22, "path": WORKFLOW_PATH,
            }
            api.routes[("GET", prefix + "/actions/runs/2/attempts/1/jobs?per_page=100&page=1")] = {"jobs": [{
                "id": 102, "name": "Native review WP-101 PR 123 receipt 90", "run_id": 2,
                "head_sha": BASE, "status": "completed", "conclusion": "success",
            }]}
            api.routes[("GET", prefix + "/check-suites/72")] = {
                "id": 72, "head_sha": BASE, "app": {"id": 10, "slug": "github-actions"},
            }
        backend = CloudBackend(api, manifest, rules, settings(None))
        return MergedAcceptance(backend, manifest, rules), api, prefix, manifest, rules

    def evaluate(self, reader, manifest, rules, *, merged=True, completion=True):
        with patch("controller.native_review.trusted_inputs", return_value=(manifest, rules)), \
                patch.object(reader, "verify_trusted_readers"), patch.object(reader, "verify_legacy_run"), \
                patch.object(reader, "candidate_history", return_value=nullcontext(manifest.repo)):
            return reader.evaluate("WP-101", 123, merged=merged, completion=completion)

    def test_missing_merged_head_uses_disposable_history_without_changing_checkout(self):
        reader, _, _, manifest, _ = self.fixture()
        with tempfile.TemporaryDirectory() as directory:
            source, checkout = Path(directory) / "source", Path(directory) / "checkout"
            subprocess.run(["git", "init", "--quiet", str(source)], check=True)
            (source / "file.txt").write_text("base\n", encoding="utf-8")
            git(source, "add", "file.txt")
            git(source, "-c", "user.name=Fixture", "-c", "user.email=fixture@example.test",
                "commit", "--quiet", "-m", "base")
            base = git(source, "rev-parse", "HEAD").decode().strip()
            subprocess.run(["git", "clone", "--quiet", "--no-hardlinks", str(source), str(checkout)], check=True)
            git(source, "switch", "--quiet", "-c", "candidate")
            (source / "file.txt").write_text("candidate\n", encoding="utf-8")
            git(source, "add", "file.txt")
            git(source, "-c", "user.name=Fixture", "-c", "user.email=fixture@example.test",
                "commit", "--quiet", "-m", "candidate")
            head = git(source, "rev-parse", "HEAD").decode().strip()
            reader.manifest = replace(manifest, repo=checkout)
            with self.assertRaises(PortError):
                git(checkout, "cat-file", "-e", head + "^{commit}")

            def fetch_candidate(temporary, number, tested_base):
                self.assertEqual(number, 123)
                self.assertEqual(tested_base, base)
                git(temporary, "fetch", "--no-tags", "--no-write-fetch-head", f"--negotiation-tip={base}",
                    str(source), "refs/heads/candidate")

            with patch.object(reader, "fetch_candidate", side_effect=fetch_candidate):
                with reader.candidate_history(123, base, head) as temporary:
                    self.assertNotEqual(temporary, checkout)
                    self.assertEqual(git(temporary, "rev-parse", head + "^{commit}").decode().strip(), head)
                    self.assertEqual(git(temporary, "rev-parse", base + "^{commit}").decode().strip(), base)
                    self.assertFalse(git(temporary, "status", "--short").strip())
                self.assertFalse(temporary.exists())
            self.assertEqual(git(checkout, "rev-parse", "HEAD").decode().strip(), base)
            with self.assertRaises(PortError):
                git(checkout, "cat-file", "-e", head + "^{commit}")
            with patch.object(reader, "fetch_candidate"):
                with self.assertRaisesRegex(PortError, "exact merged candidate"):
                    with reader.candidate_history(123, base, head):
                        self.fail("An unrelated fetched head must not be accepted")

    def test_local_merged_head_still_rejects_replaced_grafted_or_shallow_history(self):
        reader, _, _, manifest, _ = self.fixture()
        with tempfile.TemporaryDirectory() as directory:
            for kind in ("replace", "graft", "shallow"):
                repository = Path(directory) / kind
                subprocess.run(["git", "init", "--quiet", str(repository)], check=True)
                (repository / "file.txt").write_text("base\n", encoding="utf-8")
                git(repository, "add", "file.txt")
                git(repository, "-c", "user.name=Fixture", "-c", "user.email=fixture@example.test",
                    "commit", "--quiet", "-m", "base")
                base = git(repository, "rev-parse", "HEAD").decode().strip()
                (repository / "file.txt").write_text("candidate\n", encoding="utf-8")
                git(repository, "add", "file.txt")
                git(repository, "-c", "user.name=Fixture", "-c", "user.email=fixture@example.test",
                    "commit", "--quiet", "-m", "candidate")
                head = git(repository, "rev-parse", "HEAD").decode().strip()
                reader.manifest = replace(manifest, repo=repository)
                if kind == "replace":
                    git(repository, "replace", head, base)
                elif kind == "graft":
                    grafts = repository / ".git" / "info" / "grafts"
                    grafts.parent.mkdir(parents=True, exist_ok=True)
                    grafts.write_text(f"{head} {base}\n", encoding="utf-8")
                else:
                    (repository / ".git" / "shallow").write_text(f"{head}\n", encoding="utf-8")
                with self.subTest(kind=kind), self.assertRaisesRegex(PortError, "complete, unreplaced"):
                    with reader.candidate_history(123, base, head):
                        self.fail("Untrusted history must not be used, even when the head is local")

    def test_candidate_fetch_keeps_credentials_out_of_command_and_git_configuration(self):
        reader, api, _, manifest, _ = self.fixture()
        api.token = "fixture-token"
        with patch("controller.merged_acceptance.subprocess.run") as run:
            run.return_value.returncode = 0
            reader.fetch_candidate(manifest.repo, 123, BASE)
        command = run.call_args.args[0]
        environment = run.call_args.kwargs["env"]
        self.assertNotIn(api.token, " ".join(command))
        self.assertEqual(command[-1], "refs/pull/123/head")
        self.assertIn("--no-write-fetch-head", command)
        self.assertIn("--negotiation-tip=" + BASE, command)
        self.assertEqual(environment["GIT_CONFIG_GLOBAL"], os.devnull)
        self.assertEqual(environment["GIT_TERMINAL_PROMPT"], "0")
        self.assertTrue(environment["GIT_CONFIG_VALUE_1"].startswith("Authorization: Basic "))
        self.assertNotIn(api.token, environment["GIT_CONFIG_VALUE_1"])
        with patch("controller.merged_acceptance.subprocess.run") as run:
            run.return_value.returncode = 1
            with self.assertRaisesRegex(PortError, "repository read access") as error:
                reader.fetch_candidate(manifest.repo, 123, BASE)
        self.assertNotIn(api.token, str(error.exception))

    def test_candidate_checks_use_disposable_history_without_replacing_manifest(self):
        reader, _, _, manifest, _ = self.fixture()
        temporary = Path(tempfile.gettempdir()) / "android-port-fixture-history"
        with patch.object(reader, "candidate_history", return_value=nullcontext(temporary)), \
                patch.object(MergedAcceptance, "verify_trusted_readers", autospec=True) as trusted, \
                patch.object(MergedAcceptance, "verify_legacy_run", autospec=True) as hosted:
            reader.check(123, BASE, HEAD, None)
        validator = trusted.call_args.args[0]
        self.assertEqual(validator.manifest.repo, temporary)
        self.assertIs(hosted.call_args.args[0], validator)
        self.assertIs(reader.manifest, manifest)

    def test_merged_implementation_uses_real_merge_and_passing_head_check(self):
        reader, api, prefix, manifest, rules = self.fixture()
        api.routes[("GET", prefix + "/pulls/123/reviews?per_page=100&page=1")] = []
        result = self.evaluate(reader, manifest, rules)
        self.assertEqual(result["kind"], "merged-implementation")
        self.assertEqual(result["binding"]["head_sha"], HEAD)
        self.assertEqual(result["merge_commit_sha"], MERGE)
        self.assertEqual(result["ci_run_ids"], [1])
        self.assertEqual(result["review_ids"], [])
        self.assertFalse(any(method != "GET" and not readonly for method, _, _, readonly in api.calls))

    def test_empty_actions_associations_use_server_commit_to_pr_association(self):
        reader, api, prefix, manifest, rules = self.fixture()
        result = self.evaluate(reader, manifest, rules)
        self.assertEqual(result["ci"]["check_id"], 100)
        api.routes[("GET", prefix + f"/commits/{HEAD}/pulls?per_page=100&page=1")] = [{"number": 999}]
        with self.assertRaisesRegex(PortError, "associated"):
            self.evaluate(reader, manifest, rules)

    def test_failed_missing_stale_or_untrusted_ci_and_merge_are_rejected(self):
        for kind in (
            "missing", "failed", "after-merge", "wrong-head", "wrong-base", "wrong-merge",
            "wrong-merger", "wrong-run", "wrong-suite", "wrong-workflow", "unmerged",
        ):
            reader, api, prefix, manifest, rules = self.fixture()
            check = api.routes[("GET", prefix + f"/commits/{HEAD}/check-runs?filter=all&per_page=100&page=1")]["check_runs"][0]
            pr = api.routes[("GET", prefix + "/pulls/123")]
            if kind == "missing":
                check["name"] = "other"
            elif kind == "failed":
                check["conclusion"] = "failure"
            elif kind == "after-merge":
                check["completed_at"] = "2026-10-10T13:00:00Z"
            elif kind == "wrong-head":
                check["head_sha"] = BASE
            elif kind == "wrong-base":
                pr["base"]["sha"] = HEAD
                api.routes[("GET", prefix + f"/compare/{HEAD}...{BASE}")] = {"status": "diverged"}
            elif kind == "wrong-merge":
                api.routes[("GET", prefix + "/git/commits/" + MERGE)]["parents"][1]["sha"] = BASE
            elif kind == "wrong-merger":
                pr["merged_by"]["login"] = "untrusted"
            elif kind == "wrong-run":
                api.routes[("GET", prefix + "/actions/runs/1/attempts/1")]["head_sha"] = BASE
            elif kind == "wrong-suite":
                api.routes[("GET", prefix + "/check-suites/71")]["app"]["slug"] = "other"
            elif kind == "wrong-workflow":
                api.routes[("GET", prefix + "/actions/runs/1/attempts/1")]["path"] = ".github/workflows/other.yml"
            else:
                pr["merged"] = False
            with self.subTest(kind=kind), self.assertRaises(PortError):
                self.evaluate(reader, manifest, rules)

    def test_newer_failure_before_merge_blocks_but_later_feedback_does_not_reopen(self):
        reader, api, prefix, manifest, rules = self.fixture()
        checks = api.routes[("GET", prefix + f"/commits/{HEAD}/check-runs?filter=all&per_page=100&page=1")]["check_runs"]
        checks.append({**checks[0], "id": 101, "conclusion": "failure",
                       "started_at": "2026-10-10T12:30:00Z", "completed_at": "2026-10-10T13:00:00Z"})
        reviews = api.routes[("GET", prefix + "/pulls/123/reviews?per_page=100&page=1")]
        reviews.append({**reviews[0], "id": 91, "state": "CHANGES_REQUESTED",
                        "submitted_at": "2026-10-10T13:00:00Z"})
        self.evaluate(reader, manifest, rules)
        checks[-1].update(started_at="2026-10-10T11:40:00Z", completed_at="2026-10-10T11:45:00Z")
        with self.assertRaisesRegex(PortError, "did not pass"):
            self.evaluate(reader, manifest, rules)
        checks.pop()
        reviews[-1]["submitted_at"] = "2026-10-10T11:45:00Z"
        with self.assertRaisesRegex(PortError, "requested changes"):
            self.evaluate(reader, manifest, rules)

    def test_latest_decisive_review_uses_submission_time_before_review_id(self):
        reader, api, prefix, manifest, rules = self.fixture()
        reviews = api.routes[("GET", prefix + "/pulls/123/reviews?per_page=100&page=1")]
        reviews[0].update(state="APPROVED", submitted_at="2026-10-10T11:00:00Z")
        reviews.append({
            **reviews[0], "id": 80, "state": "CHANGES_REQUESTED",
            "submitted_at": "2026-10-10T11:30:00Z",
        })
        with self.assertRaisesRegex(PortError, "outstanding requested changes"):
            self.evaluate(reader, manifest, rules)

    def test_check_runs_include_postmerge_reruns_without_replacing_premerge_check(self):
        reader, api, prefix, manifest, rules = self.fixture()
        checks = api.routes[("GET", prefix + f"/commits/{HEAD}/check-runs?filter=all&per_page=100&page=1")]["check_runs"]
        checks.append({
            **checks[0], "id": 105, "started_at": "2026-10-10T12:10:00Z",
            "completed_at": "2026-10-10T12:20:00Z", "conclusion": "failure",
            "details_url": f"https://github.com/{rules['repository']}/actions/runs/5/job/205",
        })
        proof = self.evaluate(reader, manifest, rules)
        self.assertEqual(proof["ci"]["check_id"], 100)
        self.assertTrue(any(
            method == "GET" and path == prefix + f"/commits/{HEAD}/check-runs?filter=all&per_page=100&page=1"
            for method, path, _, _ in api.calls
        ))

    def test_android_check_run_must_match_actions_job_check_run_url(self):
        reader, api, prefix, manifest, rules = self.fixture()
        api.routes[("GET", prefix + "/actions/jobs/200")]["check_run_url"] = (
            f"https://api.github.com{prefix}/check-runs/999"
        )
        with self.assertRaisesRegex(PortError, "linked to the selected successful Actions job"):
            self.evaluate(reader, manifest, rules)

    def test_a_newer_check_pending_at_merge_cannot_be_hidden_by_an_older_success(self):
        reader, api, prefix, manifest, rules = self.fixture()
        checks = api.routes[("GET", prefix + f"/commits/{HEAD}/check-runs?filter=all&per_page=100&page=1")]["check_runs"]
        checks.append({**checks[0], "id": 101, "status": "in_progress", "conclusion": None,
                       "started_at": "2026-10-10T11:45:00Z", "completed_at": None})
        with self.assertRaisesRegex(PortError, "did not pass"):
            self.evaluate(reader, manifest, rules)
        checks[-1].update(status="completed", conclusion="success", completed_at="2026-10-10T12:15:00Z")
        with self.assertRaisesRegex(PortError, "had not completed before merge"):
            self.evaluate(reader, manifest, rules)
        checks[-1]["started_at"] = "2026-10-10T12:10:00Z"
        self.evaluate(reader, manifest, rules)

    def test_plain_review_cannot_bypass_staged_review_or_capability_validation(self):
        reader, api, prefix, manifest, rules = self.fixture(merged=False)
        reviews = api.routes[("GET", prefix + "/pulls/123/reviews?per_page=100&page=1")]
        authority = GitHubGateAuthority(reader.backend, manifest, rules)
        with self.assertRaisesRegex(PortError, "authenticated staged review"):
            self.evaluate(reader, manifest, rules, merged=False)
        for state, body in (("APPROVED", "Approved"), ("COMMENTED", "No blocking findings.")):
            reviews[0].update(state=state, body=body)
            with self.subTest(state=state), self.assertRaisesRegex(PortError, "Missing native project check"):
                authority.reviewed_candidate("WP-101", Identity(pr_number=123), BASE)

    def test_one_published_review_check_is_revalidated_without_duplicate_gate(self):
        reader, api, prefix, manifest, rules = self.fixture(merged=False)
        review = api.routes[("GET", prefix + "/pulls/123/reviews?per_page=100&page=1")][0]
        review.update(state="COMMENTED", body=REVIEW_MARKER + "{}")
        binding = Binding(rules["repository"], "WP-101", BASE, HEAD, REFERENCE_SHA,
                          manifest.sha256, policy_revision(manifest, rules))
        value = SimpleNamespace(binding=binding, evidence={"schema_version": 2}, pr={}, review={},
                                approvals=[], catalog=None)
        authority = GitHubGateAuthority(reader.backend, manifest, rules)
        with patch("controller.native_review.NativeReview.bundle", return_value=value) as bundle, \
                patch("controller.authority.verify_pr_gates", return_value={"ci_run_ids": [1], "approval_id": None}):
            proof = authority.reviewed_candidate("WP-101", Identity(pr_number=123), BASE)
            self.assertEqual(proof["binding"]["head_sha"], HEAD)
            self.assertEqual(proof["ci_run_ids"], [1])
            bundle.assert_called_once_with("WP-101", Identity(pr_number=123))
            with self.assertRaisesRegex(PortError, "current default-branch base"):
                authority.reviewed_candidate("WP-101", Identity(pr_number=123), HEAD)
        reviews = api.routes[("GET", prefix + "/pulls/123/reviews?per_page=100&page=1")]
        reviews.append({**review, "id": 91, "state": "CHANGES_REQUESTED",
                        "user": {"login": "another-reviewer", "type": "User"}})
        with self.assertRaisesRegex(PortError, "outstanding requested changes"):
            authority.reviewed_candidate("WP-101", Identity(pr_number=123), BASE)

    def test_scope_edited_after_merge_is_not_retroactively_expanded(self):
        reader, api, _, manifest, rules = self.fixture()
        body = api.routes[("GET", reader.prefix + "/pulls/123")]["body"]
        api.routes[("POST", "/graphql")] = {"data": {"repository": {"pullRequest": {
            "number": 123, "body": body, "lastEditedAt": "2026-10-10T12:01:00Z",
        }}}}
        with self.assertRaisesRegex(PortError, "edited after merge"):
            self.evaluate(reader, manifest, rules)

    def test_published_review_recognizes_full_scope_without_description_boilerplate(self):
        reader, api, prefix, manifest, rules = self.fixture(current=True)
        pr = api.routes[("GET", prefix + "/pulls/123")]
        pr["body"] = marker("WP-101") + "\nImplemented the assigned services."
        result = self.evaluate(reader, manifest, rules)
        self.assertEqual(result["kind"], "merged-implementation")
        self.assertEqual(result["review_ids"], [90])
        with patch("controller.native_review.trusted_inputs", return_value=(manifest, rules)):
            self.assertEqual(reader.discover_implementation("WP-101"), 123)

    def test_historical_native_review_scope_needs_no_description_boilerplate(self):
        reader, api, prefix, manifest, rules = self.fixture(legacy_native=True)
        api.routes[("GET", prefix + "/pulls/123")]["body"] = marker("WP-101") + "\nImplemented the services."
        self.assertEqual(self.evaluate(reader, manifest, rules)["review_ids"], [90])
        with patch("controller.native_review.trusted_inputs", return_value=(manifest, rules)):
            self.assertEqual(reader.discover_implementation("WP-101"), 123)
        checks = api.routes[("GET", prefix + f"/commits/{HEAD}/check-runs?filter=all&per_page=100&page=1")]["check_runs"]
        checks[-1]["conclusion"] = "failure"
        with self.assertRaisesRegex(PortError, "gate-integrity.*did not pass"):
            self.evaluate(reader, manifest, rules)
        checks[-1]["conclusion"] = "success"
        checks[-1]["output"]["summary"] = "different review"
        with self.assertRaisesRegex(PortError, "same published review"):
            self.evaluate(reader, manifest, rules)

    def normalized_merged_fixture(self, *, legacy=False, bounded=False):
        reader, api, prefix, manifest, rules = self.fixture(current=not legacy, legacy_native=legacy)
        checks = api.routes[("GET", prefix + f"/commits/{HEAD}/check-runs?filter=all&per_page=100&page=1")]["check_runs"][1:]
        if bounded:
            review = api.routes[("GET", prefix + "/pulls/123/reviews?per_page=100&page=1")][0]
            receipt = json.loads(review["body"][len(REVIEW_MARKER):])
            receipt.update(schema_version=2, evidence={"schema_version": 3}, scope_comment_id=700)
            review["body"] = REVIEW_MARKER + json.dumps(receipt)
            result = json.loads(checks[0]["output"]["summary"][len(RESULT_MARKER):])
            result.update(acceptance_kind="bounded-repair", wp_complete=False, dependency_unlock=False,
                          review_body_sha256=hashlib.sha256(review["body"].encode()).hexdigest())
            for check in checks:
                check["output"]["summary"] = RESULT_MARKER + json.dumps(result)
        for check in checks:
            check["details_url"] = f"https://github.com/{rules['repository']}/runs/{check['id']}"
        receipt = {"check_ids": sorted(check["id"] for check in checks),
                   "result_sha256": hashlib.sha256(checks[0]["output"]["summary"].encode()).hexdigest()}
        log = f"Native review WP-101 PR 123 receipt 90\t{PUBLICATION_STEP}\t2026-10-10T11:45:00Z {PUBLICATION_MARKER}{json.dumps(receipt)}\n"
        return reader, api, prefix, manifest, rules, checks, log

    def test_receipt_backed_normalized_checks_remain_recognizable_after_merge(self):
        for legacy in (False, True):
            for bounded in (False, True):
                with self.subTest(legacy=legacy, bounded=bounded):
                    reader, _, _, manifest, rules, _, log = self.normalized_merged_fixture(
                        legacy=legacy, bounded=bounded,
                    )
                    with patch("controller.repair_acceptance.read_job_log", return_value=log) as logs:
                        if bounded:
                            with self.assertRaises(BoundedRepair):
                                self.evaluate(reader, manifest, rules)
                            result = self.evaluate(reader, manifest, rules, completion=False)
                            self.assertEqual(result["kind"], "merged-scope")
                        else:
                            result = self.evaluate(reader, manifest, rules)
                            self.assertEqual(result["kind"], "merged-implementation")
                        self.assertEqual(result["review_ids"], [90])
                        self.assertTrue(all(call.args[1] == 102 for call in logs.call_args_list))

    def test_normalized_historical_checks_keep_receipt_timestamp_and_attempt_guards(self):
        for kind in ("missing-receipt", "copied-id", "changed-summary", "late-check",
                     "late-review", "wrong-attempt", "failed-job", "wrong-job", "wrong-url"):
            with self.subTest(kind=kind):
                reader, api, prefix, manifest, rules, checks, log = self.normalized_merged_fixture()
                if kind == "missing-receipt":
                    log = ""
                elif kind == "copied-id":
                    log = log.replace('"check_ids": [101]', '"check_ids": [999]')
                elif kind == "changed-summary":
                    checks[0]["output"]["summary"] += " "
                elif kind == "late-check":
                    checks[0]["completed_at"] = "2026-10-10T12:01:00Z"
                elif kind == "late-review":
                    api.routes[("GET", prefix + "/pulls/123/reviews?per_page=100&page=1")][0]["submitted_at"] = "2026-10-10T12:01:00Z"
                elif kind == "wrong-attempt":
                    api.routes[("GET", prefix + "/actions/runs/2/attempts/1")]["run_attempt"] = 2
                elif kind in ("failed-job", "wrong-job"):
                    job = api.routes[("GET", prefix + "/actions/runs/2/attempts/1/jobs?per_page=100&page=1")]["jobs"][0]
                    job["conclusion" if kind == "failed-job" else "run_id"] = "failure" if kind == "failed-job" else 3
                else:
                    checks[0]["details_url"] = f"https://github.com/{rules['repository']}/runs/999"
                with patch("controller.repair_acceptance.read_job_log", return_value=log), self.assertRaises(PortError):
                    self.evaluate(reader, manifest, rules)

    def test_published_human_acceptance_is_reused_without_an_extra_completion_record(self):
        reader, _, _, manifest, rules = self.fixture(current=True, human_required=True)
        proof = self.evaluate(reader, manifest, rules)
        self.assertEqual(proof["approval_id"], 91)
        self.assertEqual(validate_merged_implementation(rules["repository"], "WP-101", proof), proof)
        for invalid in (True, 0, "91"):
            with self.subTest(approval=invalid), self.assertRaises(PortError):
                validate_merged_implementation(rules["repository"], "WP-101", {**proof, "approval_id": invalid})

    def test_missing_or_unbound_human_acceptance_still_blocks(self):
        for kind in ("missing", "wrong-stamp", "after-merge", "wrong-maintainer"):
            reader, api, prefix, manifest, rules = self.fixture(current=True, human_required=True)
            reviews = api.routes[("GET", prefix + "/pulls/123/reviews?per_page=100&page=1")]
            if kind == "missing":
                reviews.pop()
            elif kind == "wrong-stamp":
                reviews[-1]["body"] = "Accepted"
            elif kind == "after-merge":
                reviews[-1]["submitted_at"] = "2026-10-10T12:01:00Z"
            else:
                reviews[-1]["user"]["login"] = "untrusted"
            with self.subTest(kind=kind), self.assertRaises(PortError):
                self.evaluate(reader, manifest, rules)

    def test_published_bounded_scope_cannot_be_expanded_by_pr_description(self):
        reader, api, prefix, manifest, rules = self.fixture(current=True)
        check = api.routes[("GET", prefix + f"/commits/{HEAD}/check-runs?filter=all&per_page=100&page=1")]["check_runs"][-1]
        result = json.loads(check["output"]["summary"][len(RESULT_MARKER):])
        review = api.routes[("GET", prefix + "/pulls/123/reviews?per_page=100&page=1")][0]
        receipt = json.loads(review["body"][len(REVIEW_MARKER):])
        receipt.update(schema_version=2, evidence={"schema_version": 3}, scope_comment_id=700)
        review["body"] = REVIEW_MARKER + json.dumps(receipt)
        result["review_body_sha256"] = hashlib.sha256(review["body"].encode()).hexdigest()
        result.update(acceptance_kind="bounded-repair", wp_complete=False, dependency_unlock=False)
        check["output"]["summary"] = RESULT_MARKER + json.dumps(result)
        with self.assertRaisesRegex(BoundedRepair, "bounded repair"):
            self.evaluate(reader, manifest, rules)
        with patch("controller.native_review.trusted_inputs", return_value=(manifest, rules)), \
                patch.object(reader, "verify_trusted_readers"), patch.object(reader, "verify_legacy_run"), \
                patch.object(reader, "candidate_history", return_value=nullcontext(manifest.repo)):
            scope = reader.evaluate("WP-101", 123, merged=True, completion=False)
            self.assertEqual(scope["kind"], "merged-scope")
            with self.assertRaises(NoImplementationFound):
                reader.discover_implementation("WP-101")
        for field in ("acceptance_kind", "wp_complete", "dependency_unlock"):
            result.pop(field)
        check["output"]["summary"] = RESULT_MARKER + json.dumps(result)
        with self.assertRaisesRegex(PortError, "scope disagrees"):
            self.evaluate(reader, manifest, rules)

    def test_merged_current_review_check_must_be_successful_and_authentic_before_merge(self):
        for kind in ("missing", "failure", "pending", "after-merge", "app", "binding",
                     "review-hash", "review-author", "review-timestamp", "publisher", "publisher-job", "scope"):
            reader, api, prefix, manifest, rules = self.fixture(current=True)
            checks = api.routes[("GET", prefix + f"/commits/{HEAD}/check-runs?filter=all&per_page=100&page=1")]["check_runs"]
            check = checks[-1]
            result = json.loads(check["output"]["summary"][len(RESULT_MARKER):])
            if kind == "missing":
                checks.pop()
            elif kind == "failure":
                check["conclusion"] = "failure"
            elif kind == "pending":
                check.update(status="in_progress", conclusion=None)
            elif kind == "after-merge":
                check["completed_at"] = "2026-10-10T12:01:00Z"
            elif kind == "app":
                check["app"]["id"] = 999
            elif kind == "binding":
                check["external_id"] = "stale"
            elif kind == "review-hash":
                result["review_body_sha256"] = "not-a-digest"
            elif kind == "review-author":
                api.routes[("GET", prefix + "/pulls/123/reviews?per_page=100&page=1")][0]["user"]["login"] = "candidate"
            elif kind == "review-timestamp":
                api.routes[("GET", prefix + "/pulls/123/reviews?per_page=100&page=1")][0]["submitted_at"] = "2026-10-10T12:01:00Z"
            elif kind == "publisher":
                api.routes[("GET", prefix + "/actions/runs/2/attempts/1")]["head_sha"] = HEAD
            elif kind == "publisher-job":
                api.routes[("GET", prefix + "/actions/runs/2/attempts/1/jobs?per_page=100&page=1")]["jobs"][0]["conclusion"] = "failure"
            else:
                result.update(acceptance_kind="bounded-repair", wp_complete=True, dependency_unlock=True)
            check["output"]["summary"] = RESULT_MARKER + json.dumps(result)
            with self.subTest(kind=kind), self.assertRaises(PortError):
                self.evaluate(reader, manifest, rules)

    def test_merged_review_uses_latest_premerge_check_not_a_later_rerun(self):
        reader, api, prefix, manifest, rules = self.fixture(current=True)
        checks = api.routes[("GET", prefix + f"/commits/{HEAD}/check-runs?filter=all&per_page=100&page=1")]["check_runs"]
        checks.append({**checks[-1], "id": 103, "status": "in_progress", "conclusion": None,
                       "started_at": "2026-10-10T11:50:00Z", "completed_at": None})
        with self.assertRaisesRegex(PortError, "did not pass before merge"):
            self.evaluate(reader, manifest, rules)
        checks[-1]["started_at"] = "2026-10-10T12:10:00Z"
        self.assertEqual(self.evaluate(reader, manifest, rules)["review_ids"], [90])

    def test_older_tested_base_is_preserved_when_the_real_merge_parent_advanced(self):
        reader, api, prefix, manifest, rules = self.fixture()
        earlier = "4" * 40
        api.routes[("GET", prefix + "/pulls/123")]["base"]["sha"] = earlier
        api.routes[("GET", prefix + f"/compare/{earlier}...{BASE}")] = {
            "status": "ahead", "behind_by": 0, "base_commit": {"sha": earlier},
            "merge_base_commit": {"sha": earlier},
        }
        proof = self.evaluate(reader, manifest, rules)
        self.assertEqual(proof["binding"]["base_sha"], earlier)
        self.assertEqual(proof["merge_parent_sha"], BASE)
        api.routes[("GET", prefix + f"/compare/{earlier}...{BASE}")]["behind_by"] = 1
        with self.assertRaisesRegex(PortError, "does not descend"):
            self.evaluate(reader, manifest, rules)

    def test_merged_pr_requires_full_declared_scope_without_fabricating_repair_completion(self):
        reader, api, prefix, manifest, rules = self.fixture()
        wp = manifest.wp("WP-101")
        pr = api.routes[("GET", prefix + "/pulls/123")]
        complete = pr["body"]
        for body in (
            "A port of **WP-101** with a single partial fix.",
            complete + "\nThis is a partial implementation.",
            complete + "\nThis does not assert whole-WP completion.",
            "A port of WP-101.\n" + ", ".join(item["id"] for item in wp["acceptance"]),
            "This partially implements WP-101.\n" + ", ".join(item["id"] for item in wp["acceptance"]),
            complete + "\nThis only fixes one race.",
            complete + "\nThe required integration test for the service graph is deferred.",
            complete + "\nThe acceptance criterion for the real integration is not verified.",
            complete + "\nThe full service graph is deferred. The acceptance item for the graph can't run.",
        ):
            with self.subTest(body=body):
                self.assertFalse(declares_completion(wp, body))
                pr["body"] = body
                with self.assertRaises(BoundedRepair):
                    self.evaluate(reader, manifest, rules)
        pr["body"] = complete
        self.assertTrue(declares_completion(wp, complete))
        self.assertTrue(declares_completion(
            wp, complete + "\nAll acceptance criteria passed. An unrelated enhancement was deferred.",
        ))
        self.assertTrue(declares_completion(wp, "This PR implements WP-101."))
        legacy = "A port of **WP-101**: fixture behavior.\nTargets " + ", ".join(
            item["id"] for item in wp["acceptance"]
        ) + "."
        self.assertTrue(declares_completion(wp, legacy))
        self.assertFalse(declares_completion(wp, legacy + "\nThis is a partial implementation."))
        acceptance_id = wp["acceptance"][0]["id"]
        for blocker in (
            f"{acceptance_id} is deferred.",
            f"This is deferred: {acceptance_id}.",
        ):
            with self.subTest(blocker=blocker):
                self.assertFalse(declares_completion(wp, legacy + "\n" + blocker))
        self.assertTrue(declares_completion(
            wp, legacy + "\nAn unrelated enhancement is deferred.",
        ))

    def test_human_gates_and_bounded_repairs_are_not_inferred_from_merge(self):
        reader, api, prefix, manifest, rules = self.fixture()
        pr = api.routes[("GET", prefix + "/pulls/123")]
        pr["body"] += "\nThis is a bounded repair."
        with self.assertRaises(BoundedRepair):
            self.evaluate(reader, manifest, rules)
        pr["body"] = "This PR implements WP-101.\n" + marker("WP-101")
        pr["labels"] = [{"name": "needs-human"}]
        with self.assertRaises(HumanGateRequired):
            self.evaluate(reader, manifest, rules)

    def test_supervised_completion_requires_actual_human_authority(self):
        reader, _, _, manifest, rules = self.fixture()
        manifest.wp("WP-101")["human_gate"] = True
        manifest.wp("WP-101")["supervised"] = True
        with self.assertRaises(HumanGateRequired):
            self.evaluate(reader, manifest, rules)

    def test_substantive_verification_blocker_still_applies_to_full_completion(self):
        for gated_wp in ("WP-202", "WP-502", "WP-504"):
            reader, _, _, manifest, rules = self.fixture()
            manifest.wp("WP-101")["verification"] = copy.deepcopy(manifest.wp(gated_wp)["verification"])
            with self.subTest(wp=gated_wp), self.assertRaises(PortError) as blocked:
                self.evaluate(reader, manifest, rules)
            self.assertEqual(str(blocked.exception), manifest.wp(gated_wp)["verification"]["blocker"])
        reader, _, _, manifest, rules = self.fixture()
        manifest.wp("WP-101")["verification"] = {
            "configured": False, "commands": [], "blocker": "Real device observation is still required",
        }
        with self.assertRaisesRegex(PortError, "Real device observation"):
            self.evaluate(reader, manifest, rules)

    def test_staged_review_failure_does_not_fall_back_to_a_bare_approval(self):
        reader, api, prefix, manifest, rules = self.fixture(merged=False)
        review = api.routes[("GET", prefix + "/pulls/123/reviews?per_page=100&page=1")][0]
        review.update(state="COMMENTED", body=REVIEW_MARKER + "{}")
        authority = GitHubGateAuthority(reader.backend, manifest, rules)
        with patch("controller.native_review.NativeReview.bundle",
                   side_effect=PortError("Required API31/37 runtime evidence is missing")), \
                self.assertRaisesRegex(PortError, "API31/37"):
            authority.reviewed_candidate("WP-101", Identity(pr_number=123), BASE)

    def test_discovery_uses_existing_implementation_declaration_not_repair_or_closed_issue(self):
        reader, api, prefix, manifest, rules = self.fixture()
        rows = api.routes[("GET", prefix + "/pulls?state=closed&per_page=100&page=1")]
        rows.extend([
            {**rows[0], "number": 124, "title": "[WP-101] Repair a bug", "body": marker("WP-101"),
             "merged_at": "2026-10-11T00:00:00Z"},
            {**rows[0], "number": 125, "title": "[WP-101] Port a bounded fix", "body": "A bounded repair.",
             "merged_at": "2026-10-11T00:00:00Z"},
        ])
        with patch("controller.native_review.trusted_inputs", return_value=(manifest, rules)):
            self.assertEqual(reader.discover_implementation("WP-101"), 123)
            rows[0]["merged_at"] = None
            with self.assertRaises(NoImplementationFound):
                reader.discover_implementation("WP-101")
            rows[0]["merged_at"] = "2026-10-10T12:00:00Z"
            rows.append({**rows[0], "number": 126})
            with self.assertRaisesRegex(PortError, "multiple"):
                reader.discover_implementation("WP-101")

    def test_authority_prefers_original_implementation_over_later_repair(self):
        reader, api, prefix, manifest, rules = self.fixture()
        repair = copy.deepcopy(api.routes[("GET", prefix + "/pulls/123")])
        repair.update(number=124, title="[WP-101] Repair a bug", body=marker("WP-101"))
        api.routes[("GET", prefix + "/pulls?state=closed&per_page=100&page=1")].append(repair)
        authority = GitHubGateAuthority(reader.backend, manifest, rules)
        with patch("controller.native_review.trusted_inputs", return_value=(manifest, rules)), \
                patch.object(MergedAcceptance, "verify_trusted_readers"), \
                patch.object(MergedAcceptance, "verify_legacy_run"), \
                patch.object(MergedAcceptance, "candidate_history", return_value=nullcontext(manifest.repo)):
            proof = authority.merged_implementation("WP-101", Identity(pr_number=124))
        self.assertEqual(proof["pr_number"], 123)

    def test_previously_accepted_legacy_stacked_pr_remains_valid(self):
        reader, api, prefix, manifest, rules = self.fixture()
        pr = api.routes[("GET", prefix + "/pulls/123")]
        pr["base"]["ref"] = "previous-foundation-branch"
        binding = Binding(rules["repository"], "WP-101", BASE, HEAD, REFERENCE_SHA,
                          manifest.sha256, policy_revision(manifest, rules))
        proof = {
            "binding": binding.__dict__, "pr_number": 123, "merge_commit_sha": MERGE,
            "ci_run_ids": [1], "approval_id": None,
            "evidence_sha256": "a" * 64, "review_sha256": "b" * 64,
        }
        authority = GitHubGateAuthority(reader.backend, manifest, rules)
        self.assertEqual(authority.confirm_merged("WP-101", proof), proof)
        with self.assertRaisesRegex(PortError, "Malformed legacy"):
            authority.confirm_merged("WP-101", {**proof, "ci_run_ids": []})
        with self.assertRaisesRegex(PortError, "Malformed legacy"):
            authority.confirm_merged("WP-101", {**proof, "approval_id": True})

    def test_historical_aggregate_requires_bound_positive_actual_discovery(self):
        reader, _, _, manifest, rules = self.fixture()
        binding = Binding(rules["repository"], "WP-003", BASE, HEAD, REFERENCE_SHA,
                          manifest.sha256, policy_revision(manifest, rules))
        report = {
            "result": "success", "binding": binding.__dict__, "run_id": 1, "run_attempt": 1,
            "hosts": {"linux": {
                "kotlin_assertions": 10, "standalone_assertions": 5,
                "module_unit_assertions": 50, "python_assertions": 20,
            }},
            "scope": "scaffold build evidence only; not parity-review, gate-integrity, human approval or WP completion",
        }
        jobs = [
            {"id": 99, "name": "Android scaffold", "run_id": 1, "head_sha": HEAD,
             "status": "completed", "conclusion": "success"},
            {"id": 100, "name": "android-ci", "run_id": 1, "head_sha": HEAD,
             "status": "completed", "conclusion": "success"},
        ]
        def log(*_):
            return "\n".join(
                f"android-ci\tUNKNOWN STEP\t2026-10-10T11:00:00Z {line}"
                for line in json.dumps(report, indent=2).splitlines()
            )
        with patch("controller.repair_acceptance.read_job_log", side_effect=log), \
                patch("controller.native_review.trusted_inputs", return_value=(manifest, rules)):
            reader.verify_legacy_run(jobs, 1, 1, BASE, HEAD, 100)
            report["hosts"]["linux"]["module_unit_assertions"] = 0
            with self.assertRaises(PortError):
                reader.verify_legacy_run(jobs, 1, 1, BASE, HEAD, 100)
            report["hosts"]["linux"]["module_unit_assertions"] = 50
            report["binding"]["head_sha"] = BASE
            with self.assertRaisesRegex(PortError, "exact candidate"):
                reader.verify_legacy_run(jobs, 1, 1, BASE, HEAD, 100)

    def test_candidate_cannot_redefine_the_trusted_ci_reader(self):
        reader, _, _, _, _ = self.fixture()
        base = {".github/workflows/android-ci.yml": "a", "android/scaffold/check_environment.py": "b"}
        with patch("controller.merged_acceptance.tree", side_effect=[base, dict(base)]):
            reader.verify_trusted_readers(BASE, HEAD)
        changed = {**base, "android/scaffold/check_environment.py": "c"}
        with patch("controller.merged_acceptance.tree", side_effect=[base, changed]), \
                self.assertRaisesRegex(PortError, "changed trusted CI readers"):
            reader.verify_trusted_readers(BASE, HEAD)

    def test_scoped_run_checks_real_selection_and_positive_controller_discovery(self):
        reader, _, _, _, _ = self.fixture()
        scopes = {"controller": True, "scaffold": False, "protocol": False,
                  "backup": False, "external-oracle": False}
        report = {"base": BASE, "head": HEAD, "diff_base": BASE, "fail_safe": False,
                  "paths": ["tools/android-port/controller/engine.py"], "scopes": scopes}
        native = {"suite": "android/accessibility-tests", "base": BASE, "head": HEAD, "selected": False,
                  "result": "NOT SELECTED", "committed_files": 0}
        counts = {"discovered": 3, "run": 3, "passed": 3, "failed": 0, "errors": 0, "skipped": 0,
                  "scope": "controller fixtures only; not Android/iOS/hardware parity"}
        def line(step, value):
            return f"job\t{step}\t2026-10-10T11:00:00Z {json.dumps(value)}\n"
        scope_log = line("Classify changed inputs with fail-safe defaults", report)
        scope_log += line("Classify changed inputs with fail-safe defaults", native)
        controller_log = line("Run controller tests once", counts)
        jobs = [
            {"id": index, "name": name, "run_id": 1, "head_sha": HEAD,
             "status": "completed", "conclusion": conclusion}
            for index, (name, conclusion) in enumerate((
                ("Classify candidate scopes", "success"), ("Controller and workflow policy", "success"),
                ("One Linux Android build", "skipped"), ("Swift codec oracle", "skipped"),
                ("SwiftData restore and export oracle", "skipped"),
            ), 1)
        ]
        def log(_native, job_id):
            return scope_log if job_id == 1 else controller_log
        with patch("controller.repair_acceptance.read_job_log", side_effect=log), \
                patch("oracle.workflow_scope.merge_base", return_value=BASE), \
                patch("oracle.workflow_scope.changed_paths", return_value=report["paths"]), \
                patch("oracle.workflow_scope.classify", return_value=scopes), \
                patch("controller.merged_acceptance.tree", return_value={}):
            reader.verify_scoped_run(jobs, 1, BASE, HEAD, jobs[:1])
            counts["discovered"] = counts["run"] = counts["passed"] = 0
            controller_log = line("Run controller tests once", counts)
            with self.assertRaisesRegex(PortError, "missing, skipped or failed"):
                reader.verify_scoped_run(jobs, 1, BASE, HEAD, jobs[:1])

    def test_scoped_product_changes_cannot_pass_without_runnable_module_tests(self):
        reader, _, _, _, _ = self.fixture()
        scopes = {"controller": False, "scaffold": True, "protocol": False,
                  "backup": False, "external-oracle": False}
        paths = ["android/core/data/src/main/kotlin/Store.kt"]
        report = {"base": BASE, "head": HEAD, "diff_base": BASE, "fail_safe": False,
                  "paths": paths, "scopes": scopes}
        native = {"suite": "android/accessibility-tests", "base": BASE, "head": HEAD, "selected": False,
                  "result": "NOT SELECTED", "committed_files": 0}
        def line(value):
            return ("job\tClassify changed inputs with fail-safe defaults\t"
                    f"2026-10-10T11:00:00Z {json.dumps(value)}\n")
        jobs = [
            {"id": index, "name": name, "run_id": 1, "head_sha": HEAD,
             "status": "completed", "conclusion": conclusion}
            for index, (name, conclusion) in enumerate((
                ("Classify candidate scopes", "success"), ("Controller and workflow policy", "success"),
                ("One Linux Android build", "success"), ("Swift codec oracle", "skipped"),
                ("SwiftData restore and export oracle", "skipped"),
            ), 1)
        ]
        with patch("controller.repair_acceptance.read_job_log", return_value=line(report) + line(native)), \
                patch("oracle.workflow_scope.merge_base", return_value=BASE), \
                patch("oracle.workflow_scope.changed_paths", return_value=paths), \
                patch("oracle.workflow_scope.classify", return_value=scopes), \
                patch("controller.merged_acceptance.tree", return_value={}), \
                patch("controller.local_scope.candidate_plan",
                      return_value={"scopes": ["module:core/data"], "tasks": [":core:data:lint"]}):
            with self.assertRaisesRegex(PortError, "no selected runnable module tests"):
                reader.verify_scoped_run(jobs, 1, BASE, HEAD, jobs[:1])


if __name__ == "__main__":
    unittest.main()
