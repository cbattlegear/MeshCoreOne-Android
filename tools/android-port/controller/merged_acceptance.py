"""AndroidOnly: WP-003 Acceptance from actual GitHub merge, review and CI facts."""

import base64
import hashlib
import json
import os
import re
import subprocess
import tempfile
from contextlib import contextmanager
from dataclasses import asdict, replace
from datetime import datetime
from pathlib import Path

from .errors import PortError
from .gates import Binding, human_acceptance_required, policy_revision, validate_approval
from .model import REFERENCE_SHA, SHA, git, tree
from .render import marker
from .schema import decode_json, fields, positive_integer
from .verification import work_package_software_lane


class HumanGateRequired(PortError):
    pass


class BoundedRepair(PortError):
    pass


class NoImplementationFound(PortError):
    pass


def timestamp(value, label):
    if not isinstance(value, str):
        raise PortError(f"Missing {label} timestamp")
    try:
        result = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise PortError(f"Malformed {label} timestamp") from error
    if result.utcoffset() is None:
        raise PortError(f"Missing timezone in {label} timestamp")
    return result


def log_objects(log, step):
    lines = []
    for line in log.splitlines():
        columns = line.split("\t", 2)
        if len(columns) == 3 and columns[1] == step:
            lines.append(re.sub(r"^\S+\s?", "", columns[2]))
    text = "\n".join(lines)
    objects = []
    for match in re.finditer(r"(?m)^\{", text):
        try:
            _, length = json.JSONDecoder().raw_decode(text[match.start():])
            value = decode_json(text[match.start():match.start() + length])
        except json.JSONDecodeError:
            continue
        if isinstance(value, dict):
            objects.append(value)
    return objects


def declares_completion(wp, body):
    """Distinguish a declared WP implementation from a change to part of one."""
    acceptance_ids = [item["id"] for item in wp["acceptance"]]
    acceptance_reference = (
        r"(?:\b(?:acceptance\s+(?:criterion|criteria|item|requirement)|required\s+"
        r"(?:behavior|verification|test|integration))\b"
        + ("|(?<![\\w-])(?:" + "|".join(re.escape(value) for value in acceptance_ids)
           + r")(?![\w-])" if acceptance_ids else "")
        + ")"
    )
    if re.search(
        r"\b(?:bounded|partial|limited)\s+(?:port|implementation|repair|scope|fix|change)\b"
        r"|\b(?:partially|only)\s+(?:implements?|ports?|covers?)\b"
        r"|\b(?:only|solely|just)\s+(?:addresses?|fixes?|implements?|ports?)\b"
        r"|\b(?:does not|doesn't|doesnt|not)\s+(?:complete|finish|implement)"
        r"\s+(?:the\s+)?(?:(?:full|entire)\s+)?(?:WP\b|work\s+package\b)"
        r"|\b(?:does not|do not|not)\s+(?:assert|claim|establish)"
        r".{0,160}(?:completion|whole[- ]WP|entire|dependency unlock)\b",
        body, re.IGNORECASE | re.DOTALL,
    ):
        return False
    unfinished = (
        r"\b(?:deferred|postponed|outstanding|unmet|incomplete|"
        r"not\s+(?:implemented|covered|tested|verified|runnable)|"
        r"can(?:not|'t)\s+(?:run|be\s+(?:run|implemented|covered|tested|verified)))\b"
    )
    if re.search(
        rf"(?:{acceptance_reference}[^\n.!?]{{0,160}}{unfinished}"
        rf"|{unfinished}[^\n.!?]{{0,160}}{acceptance_reference})",
        body, re.IGNORECASE,
    ):
        return False
    explicit = re.search(
        rf"\btargets?\s+{re.escape(wp['id'])}'s\s+behavior,\s*boundaries\s+and\s+"
        r"source-test-parity\s+acceptance\b"
        rf"|\b(?:implements?|completes?|ports?)\s+(?:the\s+)?(?:full|entire)\s+"
        rf"(?:work\s+package\s+)?\**{re.escape(wp['id'])}\**(?=$|[\s:.,])"
        rf"|\b(?:implements?|completes?|ports?)\s+\**{re.escape(wp['id'])}\**\s*[.!](?:\s|$)",
        body, re.IGNORECASE,
    )
    if explicit:
        return True
    lead = re.match(
        rf"\s*(?:#{{1,6}}[^\n]*\n\s*)?A\s+port\s+of\s+\**{re.escape(wp['id'])}\**\s*:",
        body, re.IGNORECASE,
    )
    return bool(lead and acceptance_ids and any(
        re.search(r"\b(?:targets?|targeted)\b", line, re.IGNORECASE)
        and all(re.search(rf"(?<![\w-]){re.escape(identifier)}(?![\w-])", line)
                for identifier in acceptance_ids)
        for line in body.splitlines()
    ))


class MergedAcceptance:
    """Use server facts, not candidate-created receipts or closed issues."""

    def __init__(self, backend, manifest, policy):
        self.backend, self.manifest, self.policy = backend, manifest, policy
        self.prefix = backend.prefix
        self.trusted_bases = {}

    def get(self, path):
        return self.backend.api.request("GET", self.prefix + path)

    def fetch_candidate(self, repository, number, base):
        token = getattr(self.backend.api, "token", None)
        name = self.policy["repository"]
        if not isinstance(token, str) or not token:
            raise PortError("Missing authenticated repository read credential for the merged PR's Git objects")
        if not isinstance(name, str) or re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", name) is None:
            raise PortError("Invalid trusted repository identity")
        if not isinstance(base, str) or SHA.fullmatch(base) is None:
            raise PortError("Merged PR has no immutable tested base")
        authorization = base64.b64encode(f"x-access-token:{token}".encode("utf-8")).decode("ascii")
        environment = {
            key: value for key, value in os.environ.items()
            if not key.startswith("GIT_TRACE") and key not in {
                "GIT_CURL_VERBOSE", "GIT_CONFIG_PARAMETERS", "GIT_DIR", "GIT_WORK_TREE",
                "GIT_INDEX_FILE", "GIT_COMMON_DIR", "GIT_OBJECT_DIRECTORY",
                "GIT_ALTERNATE_OBJECT_DIRECTORIES", "GIT_SSL_NO_VERIFY",
            }
        }
        environment.update({
            "GIT_TERMINAL_PROMPT": "0", "GIT_ASKPASS": "",
            "GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": os.devnull,
            "GIT_CONFIG_COUNT": "2",
            "GIT_CONFIG_KEY_0": "credential.helper", "GIT_CONFIG_VALUE_0": "",
            "GIT_CONFIG_KEY_1": "http.https://github.com/.extraheader",
            "GIT_CONFIG_VALUE_1": "Authorization: Basic " + authorization,
        })
        try:
            result = subprocess.run(
                ["git", "-C", str(repository), "fetch", "--no-tags", "--no-write-fetch-head",
                 "--no-recurse-submodules", "--no-auto-gc", f"--negotiation-tip={base}",
                 f"https://github.com/{name}.git",
                 f"refs/pull/{positive_integer(number, 'Implementation PR')}/head"],
                stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                env=environment, timeout=120, check=False,
            )
        except (OSError, subprocess.TimeoutExpired) as error:
            raise PortError("Read-only retrieval of the merged PR's Git objects failed") from error
        if result.returncode:
            raise PortError("Cannot read the merged PR's Git objects; verify repository read access")

    @contextmanager
    def candidate_history(self, number, base, head):
        positive_integer(number, "Implementation PR")
        if not isinstance(base, str) or SHA.fullmatch(base) is None:
            raise PortError("Merged PR has no immutable tested base")
        if not isinstance(head, str) or SHA.fullmatch(head) is None:
            raise PortError("Merged PR has no immutable candidate head")
        repository = self.manifest.repo
        grafts = Path(git(repository, "rev-parse", "--git-path", "info/grafts").decode().strip())
        if not grafts.is_absolute():
            grafts = repository / grafts
        if (
            git(repository, "rev-parse", "--is-shallow-repository").strip() != b"false"
            or git(repository, "for-each-ref", "--format=%(refname)", "refs/replace/").strip()
            or grafts.exists()
        ):
            raise PortError("Merged PR inspection requires complete, unreplaced default-branch history")
        try:
            git(repository, "cat-file", "-e", head + "^{commit}")
        except PortError:
            pass
        else:
            yield repository
            return
        objects = Path(git(repository, "rev-parse", "--git-path", "objects").decode().strip())
        if not objects.is_absolute():
            objects = repository / objects
        objects = objects.resolve()
        if not objects.is_dir() or any(character in str(objects) for character in ("\n", "\r")):
            raise PortError("Trusted Git object storage is unavailable")
        with tempfile.TemporaryDirectory(prefix="android-port-pr-") as directory:
            temporary = Path(directory) / "history"
            try:
                result = subprocess.run(
                    ["git", "init", "--template=", "--object-format=sha1", "--quiet", str(temporary)],
                    stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                    timeout=30, check=False,
                )
            except (OSError, subprocess.TimeoutExpired) as error:
                raise PortError("Cannot initialize disposable Git history") from error
            if result.returncode:
                raise PortError("Cannot initialize disposable Git history")
            (temporary / ".git" / "objects" / "info" / "alternates").write_text(
                str(objects) + "\n", encoding="utf-8", newline="\n",
            )
            git(temporary, "cat-file", "-e", base + "^{commit}")
            self.fetch_candidate(temporary, number, base)
            try:
                git(temporary, "cat-file", "-e", head + "^{commit}")
            except PortError as error:
                raise PortError("Fetched PR history does not contain the exact merged candidate") from error
            yield temporary

    def trusted_base(self, base, wp_id):
        from .native_review import trusted_inputs

        if not isinstance(base, str) or SHA.fullmatch(base) is None:
            raise PortError("Merged PR has no immutable tested base")
        if base not in self.trusted_bases:
            self.trusted_bases[base] = trusted_inputs(self.manifest.repo, base)
        manifest, policy = self.trusted_bases[base]
        if manifest.data["reference"]["commit"] != REFERENCE_SHA:
            raise PortError("Merged implementation does not bind the pinned Swift reference")
        if policy.get("repository") != self.policy["repository"]:
            raise PortError("Merged implementation's historical policy targets another repository")
        manifest.wp(wp_id)
        return manifest, policy

    def published_scope(self, wp_id, number, base, head, merged_at, manifest, policy):
        from .native_review import RESULT_MARKER, WORKFLOW_PATH, NativeReview
        from .publication import bounded_output

        legacy = policy["required_checks"] == ["android-ci", "parity-review", "gate-integrity"]
        if not legacy and policy["required_checks"] != ["android-ci", "parity-review"]:
            raise PortError("Unknown historical required-check policy")
        if not legacy and policy["trusted_gate_publisher_app_id"] is not None:
            raise PortError("Current merged-review recognition requires the native trusted publisher")
        if not isinstance(head, str) or SHA.fullmatch(head) is None:
            raise PortError("PR has no immutable head")
        checks = self.backend.pages(f"{self.prefix}/commits/{head}/check-runs?filter=all", "check_runs")
        def latest(name):
            candidates = []
            for check in checks:
                if check.get("name") != name or check.get("head_sha") != head:
                    continue
                positive_integer(check.get("id"), "Review check ID")
                if timestamp(check.get("started_at"), "review check start") <= merged_at:
                    candidates.append(check)
            if not candidates:
                return None
            check = max(candidates, key=lambda value: value["id"])
            if check.get("status") != "completed" or check.get("conclusion") != "success":
                raise PortError(f"Latest {name} check did not pass before merge")
            if timestamp(check.get("completed_at"), "review check completion") > merged_at:
                raise PortError(f"Latest {name} check had not completed before merge")
            return check

        check = latest("parity-review")
        if check is None:
            if legacy:
                return None
            raise PortError("Missing authenticated parity-review check before merge")
        summary = check.get("output", {}).get("summary")
        if legacy and (not isinstance(summary, str) or not summary.startswith(RESULT_MARKER)):
            return None
        summary = bounded_output(summary)
        if not summary.startswith(RESULT_MARKER):
            raise PortError("Review check has no authenticated published result")
        selected = [check]
        if legacy:
            integrity = latest("gate-integrity")
            if integrity is None or bounded_output(integrity.get("output", {}).get("summary")) != summary:
                raise PortError("Historical native checks do not reference the same published review")
            selected.append(integrity)
        result = decode_json(summary[len(RESULT_MARKER):])
        bounded = isinstance(result, dict) and result.get("acceptance_kind") == "bounded-repair"
        fields(result, {
            "binding", "pr_number", "review_id", "review_body_sha256",
            "publisher_run_id", "publisher_run_attempt",
        } | ({"acceptance_kind", "wp_complete", "dependency_unlock"} if bounded else set()),
               label="published review result")
        expected = Binding(
            self.policy["repository"], wp_id, base, head, REFERENCE_SHA,
            manifest.sha256, policy_revision(manifest, policy),
        )
        review_id = positive_integer(result["review_id"], "Source review ID")
        positive_integer(result["pr_number"], "Reviewed PR")
        if (
            Binding.parse(result["binding"]) != expected or result["pr_number"] != number
            or not isinstance(result["review_body_sha256"], str)
            or re.fullmatch(r"[0-9a-f]{64}", result["review_body_sha256"]) is None
            or bounded and (result["wp_complete"] is not False or result["dependency_unlock"] is not False)
        ):
            raise PortError("Published review has stale or conflicting scope provenance")
        native = NativeReview(self.backend, manifest, policy)
        run, suite = native.workflow_run(
            result["publisher_run_id"], result["publisher_run_attempt"], WORKFLOW_PATH, base,
            ("workflow_dispatch",), review_identity=(wp_id, number, review_id), historical_attempt=True,
        )
        native.verify_published_checks(selected, summary, expected, run, suite, number, review_id)
        reviews = self.backend.pages(f"{self.prefix}/pulls/{number}/reviews")
        actual = [review for review in reviews if review.get("id") == review_id]
        if len(actual) != 1 or timestamp(actual[0].get("submitted_at"), "source review") > merged_at:
            raise PortError("Published source review is missing or postdates the merge")
        body, receipt = native.source_receipt(actual[0], expected)
        if (
            hashlib.sha256(body.encode()).hexdigest() != result["review_body_sha256"]
            or (receipt["schema_version"] == 2) != bounded
        ):
            raise PortError("Published review scope disagrees with the authenticated source review")
        return {"bounded": bounded, "review_id": review_id, "approvals": receipt["approvals"]}

    def discover_implementation(self, wp_id):
        wp = self.manifest.wp(wp_id)
        title = re.compile(rf"^\[{re.escape(wp_id)}\]\s+", re.IGNORECASE)
        matches = []
        unverified = []
        for pr in self.backend.pages(f"{self.prefix}/pulls?state=closed"):
            body = pr.get("body") or ""
            if not pr.get("merged_at") or not (title.search(pr.get("title") or "") or marker(wp_id) in body):
                continue
            destination = pr.get("base", {})
            if (
                destination.get("ref") != self.policy["default_branch"]
                or destination.get("repo", {}).get("full_name") != self.policy["repository"]
            ):
                continue
            number = positive_integer(pr.get("number"), "Implementation PR")
            base, head = destination.get("sha"), pr.get("head", {}).get("sha")
            try:
                manifest, policy = self.trusted_base(base, wp_id)
                scope = self.published_scope(
                    wp_id, number, base, head, timestamp(pr["merged_at"], "merge"), manifest, policy,
                )
            except PortError as error:
                if declares_completion(wp, body):
                    raise PortError(f"PR #{number}: cannot authenticate the declared implementation: {error}") from error
                unverified.append(f"PR #{number}: {error}")
                continue
            if scope is not None and not scope["bounded"] or scope is None and declares_completion(wp, body):
                matches.append(number)
        if not matches:
            detail = f"; {unverified[0]}" if unverified else ""
            raise NoImplementationFound(
                f"{wp_id}: no merged PR establishes the complete dependency scope; "
                f"earlier partial work remains accepted within its own scope{detail}"
            )
        if len(matches) != 1:
            raise PortError(
                f"{wp_id}: multiple merged implementation PRs; reconcile the actual assignment"
            )
        return matches[0]

    def files(self, number):
        paths = set()
        for item in self.backend.pages(f"{self.prefix}/pulls/{number}/files"):
            path = item.get("filename")
            if not isinstance(path, str) or not path:
                raise PortError("Malformed PR file inventory")
            paths.add(path)
            if item.get("status") == "renamed":
                previous = item.get("previous_filename")
                if not isinstance(previous, str) or not previous:
                    raise PortError("Renamed PR file has no previous path")
                paths.add(previous)
        if not paths:
            raise PortError("Implementation PR has no changed files")
        return sorted(paths)

    def unchanged_scope_at_merge(self, number, body, merged_at):
        owner, name = self.policy["repository"].split("/")
        result = self.backend.api.request("POST", "/graphql", {
            "query": (
                "query PortMergedScope($owner:String!,$name:String!,$number:Int!){"
                "repository(owner:$owner,name:$name){pullRequest(number:$number){"
                "number body lastEditedAt}}}"
            ),
            "variables": {"owner": owner, "name": name, "number": number},
        }, readonly=True)
        if result.get("errors"):
            raise PortError("Cannot authenticate the merged PR's declared scope")
        actual = result.get("data", {}).get("repository", {}).get("pullRequest")
        if (
            not isinstance(actual, dict) or "lastEditedAt" not in actual
            or actual.get("number") != number or actual.get("body") != body
        ):
            raise PortError("Merged PR scope changed while it was being read")
        edited_at = actual.get("lastEditedAt")
        if edited_at is not None and timestamp(edited_at, "PR description edit") > merged_at:
            raise PortError("PR scope was edited after merge; its merged declaration cannot be inferred")

    def require_no_requested_changes(self, number, merged_at=None):
        reviews = self.backend.pages(f"{self.prefix}/pulls/{number}/reviews")
        latest = {}
        for review in reviews:
            author = review.get("user")
            if not isinstance(author, dict) or not isinstance(author.get("login"), str):
                raise PortError("Malformed server review author")
            state = review.get("state")
            if state not in ("APPROVED", "COMMENTED", "CHANGES_REQUESTED", "DISMISSED", "PENDING"):
                raise PortError("Unknown server review state")
            if state == "PENDING":
                continue
            submitted = timestamp(review.get("submitted_at"), "review")
            if merged_at is not None and submitted > merged_at:
                continue
            review_id = positive_integer(review.get("id"), "Review ID")
            login = author["login"]
            if state in ("APPROVED", "CHANGES_REQUESTED", "DISMISSED"):
                current = latest.get(login)
                if current is None or (submitted, review_id) > (current["submitted_at"], current["id"]):
                    latest[login] = {"id": review_id, "submitted_at": submitted, "state": state}
        if any(review["state"] == "CHANGES_REQUESTED" for review in latest.values()):
            raise PortError("A reviewer has outstanding requested changes")

    def check(self, number, base, head, merged_at):
        checks = self.backend.pages(f"{self.prefix}/commits/{head}/check-runs?filter=all", "check_runs")
        candidates = []
        for check in checks:
            if check.get("name") != "android-ci" or check.get("head_sha") != head:
                continue
            positive_integer(check.get("id"), "Android CI check ID")
            if merged_at is not None:
                if timestamp(check.get("started_at"), "CI check start") > merged_at:
                    continue
            candidates.append(check)
        if not candidates:
            raise PortError("Missing exact-head android-ci check before merge")
        check = max(candidates, key=lambda value: value["id"])
        if check.get("status") != "completed" or check.get("conclusion") != "success":
            raise PortError("Latest exact-head android-ci check did not pass")
        if merged_at is not None and timestamp(check.get("completed_at"), "CI check completion") > merged_at:
            raise PortError("Latest exact-head android-ci check had not completed before merge")
        details = check.get("details_url") or ""
        match = re.fullmatch(
            rf"https://github\.com/{re.escape(self.policy['repository'])}/actions/runs/(\d+)/job/(\d+)",
            details,
        )
        if match is None:
            raise PortError("Android check is not linked to an actual repository Actions run")
        run_id = positive_integer(int(match[1]), "Actions run ID")
        job_id = positive_integer(int(match[2]), "Actions job ID")
        check_id = positive_integer(check.get("id"), "Android CI check-run ID")
        selected_job = self.get(f"/actions/jobs/{job_id}")
        attempt = positive_integer(selected_job.get("run_attempt"), "Actions run attempt")
        expected_check_run_url = (
            f"https://api.github.com{self.prefix}/check-runs/{check_id}"
        )
        if (
            selected_job.get("id") != job_id
            or selected_job.get("run_id") != run_id or selected_job.get("name") != "android-ci"
            or selected_job.get("head_sha") != head or selected_job.get("status") != "completed"
            or selected_job.get("conclusion") != "success"
            or selected_job.get("check_run_url") != expected_check_run_url
        ):
            raise PortError("Required check is not linked to the selected successful Actions job")
        run = self.get(f"/actions/runs/{run_id}/attempts/{attempt}")
        suite_id = positive_integer(run.get("check_suite_id"), "Actions check suite ID")
        if (
            run.get("id") != run_id or run.get("repository", {}).get("full_name") != self.policy["repository"]
            or run.get("path") != ".github/workflows/android-ci.yml"
            or run.get("event") != "pull_request" or run.get("head_sha") != head
            or run.get("status") != "completed" or run.get("conclusion") != "success"
            or check.get("check_suite", {}).get("id") != suite_id
        ):
            raise PortError("Android check and exact candidate Actions run disagree")
        positive_integer(run.get("workflow_id"), "Actions workflow ID")
        suite = self.get(f"/check-suites/{suite_id}")
        if (
            suite.get("id") != suite_id or suite.get("head_sha") != head
            or suite.get("app", {}).get("slug") != "github-actions"
            or suite.get("app", {}).get("id") != check.get("app", {}).get("id")
        ):
            raise PortError("Android check is not from the actual GitHub Actions suite")
        positive_integer(suite.get("app", {}).get("id"), "Actions application ID")
        associations = run.get("pull_requests")
        if not isinstance(associations, list):
            raise PortError("Malformed Actions PR associations")
        if associations:
            if not any(
                pr.get("number") == number and pr.get("base", {}).get("sha") == base
                for pr in associations if isinstance(pr, dict)
            ):
                raise PortError("Actions run belongs to another PR or base")
        else:
            linked = self.backend.pages(f"{self.prefix}/commits/{head}/pulls")
            if not any(pr.get("number") == number for pr in linked):
                raise PortError("Exact candidate commit is not associated with this PR")
        jobs = self.backend.pages(
            f"{self.prefix}/actions/runs/{run_id}/attempts/{attempt}/jobs", "jobs",
        )
        with self.candidate_history(number, base, head) as repository:
            validator = self if repository == self.manifest.repo else MergedAcceptance(
                self.backend, replace(self.manifest, repo=repository), self.policy,
            )
            validator.verify_trusted_readers(base, head)
            scope_jobs = [job for job in jobs if job.get("name") == "Classify candidate scopes"]
            if scope_jobs:
                validator.verify_scoped_run(jobs, run_id, base, head, scope_jobs)
            else:
                validator.verify_legacy_run(jobs, run_id, attempt, base, head, job_id)
        return {"run_id": run_id, "run_attempt": attempt, "check_id": check_id}

    def verify_trusted_readers(self, base, head):
        before, candidate = tree(self.manifest.repo, base), tree(self.manifest.repo, head)
        roots = (
            "tools/android-port/controller/", "tools/android-port/oracle/",
            "android/scaffold/", "android/build-logic/", "android/gradle/",
        )
        fixed = {
            ".github/workflows/android-ci.yml", "android/build.gradle.kts",
            "android/settings.gradle.kts", "android/gradlew", "android/gradlew.bat",
        }
        readers = fixed | {path for path in before.keys() | candidate.keys() if path.startswith(roots)}
        if any(before.get(path) != candidate.get(path) for path in readers):
            raise PortError("Candidate changed trusted CI readers; separate trusted verification is required")

    def verify_legacy_run(self, jobs, run_id, attempt, base, head, check_id):
        from .native_review import trusted_inputs
        from .repair_acceptance import read_job_log

        def selected(name):
            matches = [item for item in jobs if item.get("name") == name]
            if len(matches) != 1 or (
                matches[0].get("run_id") != run_id or matches[0].get("head_sha") != head
                or matches[0].get("status") != "completed" or matches[0].get("conclusion") != "success"
            ):
                raise PortError(f"Historical hosted job is missing, skipped or failed: {name}")
            positive_integer(matches[0].get("id"), "Hosted job ID")
            return matches[0]

        selected("Android scaffold")
        aggregate = selected("android-ci")
        if aggregate["id"] != check_id:
            raise PortError("Historical aggregate job disagrees with the required check")
        log = read_job_log(self, check_id)
        objects = log_objects(log, "Fail closed on job outcomes, discovery or stale/artifact evidence")
        objects += log_objects(log, "UNKNOWN STEP")
        reports = [
            item for item in objects
            if item.get("scope") == (
                "scaffold build evidence only; not parity-review, gate-integrity, human approval or WP completion"
            )
        ]
        if len(reports) != 1:
            raise PortError("Missing/ambiguous historical hosted discovery report")
        report = reports[0]
        historical_manifest, historical_policy = trusted_inputs(self.manifest.repo, base)
        expected = Binding(
            self.policy["repository"], "WP-003", base, head, REFERENCE_SHA,
            historical_manifest.sha256, policy_revision(historical_manifest, historical_policy),
        )
        hosts = report.get("hosts")
        if (
            report.get("result") != "success" or type(report.get("run_id")) is not int
            or report["run_id"] != run_id or type(report.get("run_attempt")) is not int
            or report["run_attempt"] != attempt or Binding.parse(report.get("binding")) != expected
            or not isinstance(hosts, dict) or set(hosts) != {"linux"}
            or not isinstance(hosts["linux"], dict)
        ):
            raise PortError("Historical hosted report does not bind the exact candidate and run")
        for key in ("kotlin_assertions", "standalone_assertions", "module_unit_assertions", "python_assertions"):
            positive_integer(hosts["linux"].get(key), "Historical " + key)

    def verify_scoped_run(self, jobs, run_id, base, head, scope_jobs):
        from .local_scope import candidate_plan
        from .repair_acceptance import read_job_log, validate_build_log
        from .test_runner import NATIVE_INPUTS, NATIVE_SUITE
        from oracle.workflow_scope import changed_paths, classify, merge_base

        def job(name, conclusion):
            matches = [item for item in jobs if item.get("name") == name]
            if len(matches) != 1 or (
                matches[0].get("run_id") != run_id or matches[0].get("head_sha") != head
                or matches[0].get("status") != "completed" or matches[0].get("conclusion") != conclusion
            ):
                raise PortError(f"Selected hosted job is missing, skipped or failed: {name}")
            positive_integer(matches[0].get("id"), "Hosted job ID")
            return matches[0]

        if len(scope_jobs) != 1:
            raise PortError("Ambiguous hosted scope job")
        scope_job = job("Classify candidate scopes", "success")
        objects = log_objects(
            read_job_log(self, scope_job["id"]), "Classify changed inputs with fail-safe defaults",
        )
        reports = [item for item in objects if "scopes" in item]
        native = [item for item in objects if item.get("suite") == "android/accessibility-tests"]
        if len(reports) != 1 or len(native) != 1:
            raise PortError("Missing/ambiguous actual candidate scope report")
        report, native_report = reports[0], native[0]
        diff_base = merge_base(self.manifest.repo, base, head)
        paths = changed_paths(self.manifest.repo, diff_base, head)
        before, after = tree(self.manifest.repo, base), tree(self.manifest.repo, head)
        family = lambda path: path == NATIVE_SUITE or path.startswith(NATIVE_SUITE + "/")
        committed = {path for path in after if family(path)}
        native_selected = (
            bool(committed) or any(family(path) for path in before)
            or any(
                family(path) or path == "docs/android/deviations/WP-503.md"
                or path.startswith(NATIVE_INPUTS)
                for path in changed_paths(self.manifest.repo, base, head)
            )
        )
        if native_selected and not committed:
            raise PortError("Selected native verification suite is missing from the candidate")
        if (
            report.get("base") != base or report.get("head") != head
            or report.get("diff_base") != diff_base or report.get("fail_safe") is not False
            or report.get("paths") != paths or report.get("scopes") != classify(paths)
            or native_report.get("base") != base or native_report.get("head") != head
            or native_report.get("selected") is not native_selected
            or native_report.get("result") != ("SELECTED" if native_selected else "NOT SELECTED")
            or type(native_report.get("committed_files")) is not int
            or native_report["committed_files"] != len(committed)
        ):
            raise PortError("Hosted scope does not match the trusted exact-candidate diff")
        scopes = report["scopes"]
        controller_job = job("Controller and workflow policy", "success")
        if scopes["controller"] or native_report["selected"]:
            reports = log_objects(read_job_log(self, controller_job["id"]), "Run controller tests once")
            counts = [item for item in reports if item.get("scope") == "controller fixtures only; not Android/iOS/hardware parity"]
            if len(counts) != 1:
                raise PortError("Missing/ambiguous controller test discovery in hosted log")
            value = counts[0]
            if (
                type(value.get("discovered")) is not int or value["discovered"] <= 0
                or value.get("run") != value["discovered"] or value.get("passed") != value["discovered"]
                or any(value.get(key) != 0 for key in ("failed", "errors", "skipped"))
            ):
                raise PortError("Hosted controller tests were missing, skipped or failed")
        build_selected = any(scopes[key] for key in ("scaffold", "protocol", "backup"))
        product_changes = any(re.match(
            r"^android/(?:app|(?:core|feature|platform)/[^/]+)/src/"
            r"(?:main|debug|release|commonMain|androidMain)/.+\.(?:kt|java)$", path,
        ) for path in paths)
        if product_changes and not build_selected:
            raise PortError("Android product changes have no selected hosted build or tests")
        if build_selected:
            build = job("One Linux Android build", "success")
            plan = candidate_plan(self.manifest.repo, base, head, trusted_base=base, merge_base_only=True)
            modules = []
            for scope in plan["scopes"]:
                if scope.startswith("module:"):
                    module = scope.removeprefix("module:")
                    prefix = ":" + module.replace("/", ":") + ":"
                    if prefix + "test" in plan["tasks"] or prefix + "testDebugUnitTest" in plan["tasks"]:
                        modules.append(module)
            if not modules and "protocol" in plan["scopes"]:
                modules.append("core/protocol")
            if product_changes and not modules:
                raise PortError("Android product changes have no selected runnable module tests")
            validate_build_log(
                read_job_log(self, build["id"]), modules,
                plan_for_scopes=lambda selected: candidate_plan(
                    self.manifest.repo, base, head, selected,
                    trusted_base=base, merge_base_only=True,
                ),
            )
        else:
            job("One Linux Android build", "skipped")
        job("Swift codec oracle", "success" if scopes["external-oracle"] else "skipped")
        job("SwiftData restore and export oracle", "success" if scopes["backup"] else "skipped")

    def evaluate(self, wp_id, number, *, merged, completion=True):
        if not merged:
            raise PortError("Open candidates require an authenticated staged review, not a bare review comment")
        positive_integer(number, "Implementation PR")
        wp = self.manifest.wp(wp_id)
        pr = self.get(f"/pulls/{number}")
        title = pr.get("title") or ""
        body = pr.get("body") or ""
        if (
            pr.get("number") != number
            or not (marker(wp_id) in body or re.match(rf"^\[{re.escape(wp_id)}\]\s+", title, re.IGNORECASE))
            or pr.get("base", {}).get("repo", {}).get("full_name") != self.policy["repository"]
            or pr.get("base", {}).get("ref") != self.policy["default_branch"]
        ):
            raise PortError("PR identity, WP or destination does not match")
        head = pr.get("head", {}).get("sha")
        if not isinstance(head, str) or SHA.fullmatch(head) is None:
            raise PortError("PR has no immutable head")
        merger = pr.get("merged_by")
        merge_sha = pr.get("merge_commit_sha")
        if (
            pr.get("merged") is not True or pr.get("state") != "closed"
            or not isinstance(merge_sha, str) or SHA.fullmatch(merge_sha) is None
            or not isinstance(merger, dict) or merger.get("type") != "User"
            or merger.get("login") not in self.policy["maintainers"]
        ):
            raise PortError("Completion requires the actual maintainer-merged implementation PR")
        merged_at = timestamp(pr.get("merged_at"), "merge")
        base = pr.get("base", {}).get("sha")
        historical_manifest, historical_policy = self.trusted_base(base, wp_id)
        scope = self.published_scope(wp_id, number, base, head, merged_at, historical_manifest, historical_policy)
        if completion:
            if scope is not None and scope["bounded"]:
                raise BoundedRepair("This review accepts only a bounded repair")
            if scope is None:
                if not declares_completion(wp, body):
                    raise BoundedRepair("This PR does not declare completion of the work package")
                self.unchanged_scope_at_merge(number, body, merged_at)
            work_package_software_lane(self.manifest, wp)
        commit = self.get(f"/git/commits/{merge_sha}")
        parents = commit.get("parents")
        if (
            commit.get("sha") != merge_sha or not isinstance(parents, list)
            or len(parents) not in (1, 2) or not all(isinstance(item, dict) for item in parents)
            or any(not isinstance(item.get("sha"), str) or SHA.fullmatch(item["sha"]) is None
                   for item in parents)
            or len(parents) == 2 and parents[1]["sha"] != head
        ):
            raise PortError("Merged commit does not bind the implementation PR")
        merge_parent = parents[0]["sha"]
        if base != merge_parent:
            comparison = self.get(f"/compare/{base}...{merge_parent}")
            if (
                comparison.get("status") != "ahead" or type(comparison.get("behind_by")) is not int
                or comparison["behind_by"] != 0
                or comparison.get("base_commit", {}).get("sha") != base
                or comparison.get("merge_base_commit", {}).get("sha") != base
            ):
                raise PortError("Historical merge parent does not descend from the tested PR base")
        paths = self.files(number)
        labels = [item.get("name") for item in pr.get("labels", []) if isinstance(item, dict)]
        if not all(isinstance(label, str) for label in labels):
            raise PortError("Malformed PR labels")
        if wp["supervised"]:
            raise HumanGateRequired("This PR requires actual human, hardware, legal or release acceptance")
        binding = Binding(
            self.policy["repository"], wp_id, base, head, REFERENCE_SHA,
            historical_manifest.sha256, policy_revision(historical_manifest, historical_policy),
        )
        approval_id = None
        if human_acceptance_required(wp, self.policy, paths, labels):
            if scope is None:
                raise HumanGateRequired("This PR requires actual human, hardware, legal or release acceptance")
            from .authority import GitHubGateAuthority

            reviews = self.backend.pages(f"{self.prefix}/pulls/{number}/reviews")
            before_merge = []
            for review in reviews:
                if not isinstance(review, dict):
                    raise PortError("Malformed server review")
                if review.get("state") != "PENDING" and (
                    timestamp(review.get("submitted_at"), "human acceptance") <= merged_at
                ):
                    before_merge.append(review)
            GitHubGateAuthority(self.backend, self.manifest, self.policy).validate_server_acceptance(
                scope["approvals"], before_merge, binding,
            )
            author = pr.get("user")
            if not isinstance(author, dict):
                raise PortError("Missing actual PR author for human acceptance")
            approval_id = validate_approval(
                scope["approvals"], binding, self.policy, author.get("login"),
            )
        self.require_no_requested_changes(number, merged_at)
        ci = self.check(number, base, head, merged_at)
        return {
            "kind": "merged-implementation" if completion else "merged-scope",
            "binding": asdict(binding), "pr_number": number, "merge_commit_sha": merge_sha,
            "merge_parent_sha": merge_parent,
            "ci_run_ids": [ci["run_id"]], "ci": ci, "review_ids": [scope["review_id"]] if scope else [],
            "approval_id": approval_id,
        }
