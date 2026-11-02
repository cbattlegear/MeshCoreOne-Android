import base64
import hashlib
import json
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from typing import Callable, Protocol

from .errors import ApiError, PortError, ReceiptError
from .ledger import Identity
from .model import SHA, Manifest
from .render import cloud_payload, local_payload, marker
from .schema import decode_json, fields, nonempty, positive_integer
from .settings import Budget, Settings, Usage, utc

TASK_STATES = {
    "queued", "in_progress", "completed", "failed", "idle", "waiting_for_user", "timed_out", "cancelled",
}


class Api(Protocol):
    def request(self, method: str, path: str, payload: dict | None = None, *, readonly=False): ...


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, url):
        raise ApiError(request.method, request.selector, code)


class GitHubApi:
    """No ambient GITHUB_TOKEN, redirects, mutation retries, or credential-bearing logs."""

    def __init__(self, token: str, allow_mutation: bool = False):
        self.token = nonempty(token, "Explicit GitHub user credential")
        self.allow_mutation = allow_mutation
        self.opener = urllib.request.build_opener(NoRedirect())

    def request(self, method: str, path: str, payload: dict | None = None, *, readonly=False):
        if not path.startswith("/") or path.startswith("//") or any(c in path for c in ("\r", "\n", "\\", "#")):
            raise PortError("Unsafe GitHub API path")
        if method not in ("GET", "POST", "PATCH", "PUT"):
            raise PortError("Unsupported GitHub API verb")
        graph_query = (
            method == "POST" and path == "/graphql" and readonly and payload is not None
            and payload.get("query", "").lstrip().startswith("query ")
            and "mutation" not in payload.get("query", "")
        )
        if method != "GET" and not graph_query and not self.allow_mutation:
            raise PortError("Live API side effects are disabled")
        body = json.dumps(payload).encode() if payload is not None else None
        version = "2026-03-10" if path.startswith("/agents/") else "2022-11-28"
        request = urllib.request.Request(
            "https://api.github.com" + path, data=body, method=method,
            headers={
                "Accept": "application/vnd.github+json", "X-GitHub-Api-Version": version,
                "Authorization": f"Bearer {self.token}", "Content-Type": "application/json",
                "User-Agent": "meshcoreone-android-port-controller",
            },
        )
        try:
            with self.opener.open(request, timeout=30) as response:
                data = response.read(8 * 1024 * 1024 + 1)
        except urllib.error.HTTPError as error:
            raise ApiError(method, path, error.code) from None
        except (urllib.error.URLError, TimeoutError, OSError) as error:
            raise PortError("GitHub transport failed; mutation outcome may be uncertain, do not retry") from error
        if len(data) > 8 * 1024 * 1024:
            raise PortError("Oversized GitHub response")
        try:
            return decode_json(data.decode("utf-8"))
        except UnicodeError as error:
            raise PortError("GitHub response is not UTF-8 JSON") from error


@dataclass(frozen=True)
class Observation:
    identity: Identity
    worker_state: str
    pr_open: bool
    authoritative: bool
    ambiguous: bool = False
    reason: str = ""

    def __post_init__(self):
        if not isinstance(self.identity, Identity) or self.worker_state not in (
            TASK_STATES | {"absent", "unknown", "assignment_accepted"}
        ):
            raise PortError("Malformed backend identity/execution observation")
        if any(type(v) is not bool for v in (self.pr_open, self.authoritative, self.ambiguous)):
            raise PortError("Backend authority/PR/ambiguity flags must be explicit booleans")
        if not isinstance(self.reason, str):
            raise PortError("Malformed reconciliation reason")


class Backend(Protocol):
    name: str

    def preflight(self, wp_id: str, base_sha: str): ...
    def current_base(self) -> str: ...
    def reconcile(self, wp_id: str, known: Identity) -> Observation: ...
    def launch(self, wp_id: str, attempt: str) -> Observation: ...
    def repair(self, identity: Identity, prompt: str): ...
    def usage(self, budget: Budget, now: float) -> Usage: ...


class CloudBackend:
    name = "cloud"

    def __init__(self, api: Api, manifest: Manifest, policy: dict, settings: Settings):
        self.api = api
        self.manifest = manifest
        self.policy = policy
        self.settings = settings
        self.prefix = f"/repos/{policy['repository']}"
        self.tasks_prefix = f"/agents/repos/{policy['repository']}"

    def current_base(self):
        branch = urllib.parse.quote(self.policy["default_branch"], safe="")
        result = self.api.request("GET", f"{self.prefix}/git/ref/heads/{branch}")
        sha = result.get("object", {}).get("sha")
        if not isinstance(sha, str) or SHA.fullmatch(sha) is None:
            raise PortError("Missing immutable current default-branch SHA")
        return sha

    def authenticate(self):
        if self.settings.auth_kind not in ("personal_access_token", "oauth_user", "github_app_user"):
            raise PortError("Cloud assignment requires user-to-server authentication, not an installation token")
        user = self.api.request("GET", "/user")
        if user.get("type") != "User" or user.get("login") != self.settings.user_login:
            raise PortError("GitHub credential is not the configured human user-to-server identity")
        repo = self.api.request("GET", self.prefix)
        if repo.get("full_name") != self.policy["repository"] or repo.get("default_branch") != self.policy["default_branch"]:
            raise PortError("Repository/default-branch capability mismatch")
        if not repo.get("permissions", {}).get("push"):
            raise PortError("Authenticated user lacks repository write permission")
        return repo

    def preflight(self, wp_id: str, base_sha: str):
        self.authenticate()
        if self.current_base() != base_sha:
            raise PortError("Default branch advanced after lease preparation")
        owner, repository = self.policy["repository"].split("/")
        actors = self.api.request("POST", "/graphql", {
            "query": (
                "query PortAvailability($owner:String!,$repo:String!){repository(owner:$owner,name:$repo)"
                "{suggestedActors(capabilities:[CAN_BE_ASSIGNED],first:100){nodes{login}}}}"
            ),
            "variables": {"owner": owner, "repo": repository},
        }, readonly=True)
        if actors.get("errors"):
            raise PortError("Cloud assignability query failed")
        nodes = actors.get("data", {}).get("repository", {}).get("suggestedActors", {}).get("nodes", [])
        if not any(a.get("login") in ("copilot-swe-agent", "copilot-swe-agent[bot]") for a in nodes):
            raise PortError("Copilot is unavailable/unassignable for this account/repository")
        agent = self.manifest.wp(wp_id)["owner"]
        # Repository agents are identified by filename stem, not an invented enterprise catalog API.
        result = self.api.request(
            "GET", f"{self.prefix}/contents/.github/agents/{agent}.agent.md?ref={base_sha}"
        )
        if result.get("encoding") != "base64" or result.get("name") != f"{agent}.agent.md":
            raise PortError("Trusted custom-agent identity is unavailable")
        try:
            if not isinstance(result["content"], str):
                raise PortError("Trusted profile content must be base64 text")
            encoded = "".join(result["content"].split())
            content = base64.b64decode(encoded, validate=True).decode("utf-8").replace("\r\n", "\n")
        except (ValueError, KeyError, UnicodeError) as error:
            raise PortError("Malformed trusted custom-agent contents") from error
        approved = next(a["sha256"] for a in self.manifest.data["agents"] if a["name"] == agent)
        if hashlib.sha256(content.encode()).hexdigest() != approved:
            raise PortError("Custom-agent profile does not match the trusted approved base profile")

    def pages(self, path: str, key: str | None = None):
        results = []
        separator = "&" if "?" in path else "?"
        for page in range(1, 101):
            value = self.api.request("GET", f"{path}{separator}per_page=100&page={page}")
            rows = value.get(key) if key and isinstance(value, dict) else value
            if not isinstance(rows, list) or any(not isinstance(row, dict) for row in rows):
                raise PortError("Malformed paginated GitHub response")
            results.extend(rows)
            if len(rows) < 100:
                return results
        raise PortError("Pagination limit reached; inventory is incomplete, not empty")

    def issues(self):
        return [i for i in self.pages(f"{self.prefix}/issues?state=all") if "pull_request" not in i]

    def find_issue(self, wp_id: str):
        matches = []
        for issue in self.issues():
            body = issue.get("body") or ""
            title = issue.get("title") or ""
            if marker(wp_id) in body:
                if body.count(marker(wp_id)) != 1:
                    raise PortError("Ambiguous WP issue marker")
                matches.append(issue)
            elif title.startswith(f"[{wp_id}]"):
                raise PortError("Existing WP-titled issue lacks the canonical marker; reconcile manually")
        if len(matches) > 1:
            raise PortError("Duplicate WP issues; do not assign or create another")
        return matches[0] if matches else None

    def reconcile(self, wp_id: str, known: Identity):
        issue = self.find_issue(wp_id)
        identity = Identity(issue_number=issue["number"] if issue else None)
        identity = known.reconcile(identity)
        prs = []
        for pr in self.pages(f"{self.prefix}/pulls?state=all"):
            body = pr.get("body") or ""
            if known.pr_number and pr.get("number") != known.pr_number:
                if pr.get("state") == "open" and (
                    marker(wp_id) in body or (pr.get("title") or "").startswith(f"[{wp_id}]")
                ):
                    raise PortError("Another open PR has the same WP; reconcile concurrent work")
                continue
            if marker(wp_id) in body or known.pr_number == pr.get("number"):
                if marker(wp_id) not in body or body.count(marker(wp_id)) != 1:
                    raise PortError("Existing PR identity lacks an unambiguous WP marker")
                prs.append(pr)
            elif (pr.get("title") or "").startswith(f"[{wp_id}]"):
                raise PortError("Unreconciled existing WP PR; do not launch a duplicate")
        if len(prs) > 1:
            raise PortError("Multiple implementation PRs for one attempt; inspect explicitly")
        pr = prs[0] if prs else None
        if known.pr_number and pr is None:
            raise PortError("Known PR is missing from the authoritative API inventory")
        tasks = self.pages(self.tasks_prefix + "/tasks", "tasks")
        matching = []
        for task in tasks:
            if task.get("state") not in TASK_STATES or not isinstance(task.get("id"), str):
                raise PortError("Unknown cloud task schema/state")
            pull_ids = {
                a.get("data", {}).get("id") for a in task.get("artifacts", [])
                if a.get("provider") == "github" and a.get("type") == "pull"
            }
            if task["id"] == known.task_id or (pr and pr["id"] in pull_ids):
                matching.append(task)
            elif (task.get("name") or "").startswith(f"[{wp_id}]") and (
                not known.pr_number or task.get("state") not in ("completed", "failed", "timed_out", "cancelled")
            ):
                raise PortError("Unmapped WP cloud task exists; reconcile rather than relaunch")
        if len(matching) > 1:
            raise PortError("Ambiguous cloud task identity")
        task = matching[0] if matching else None
        if known.task_id and task is None:
            # Archived tasks are not in the ordinary listing; fetch the known identity, never forget it.
            task = self.api.request("GET", f"{self.tasks_prefix}/tasks/{urllib.parse.quote(known.task_id, safe='')}")
            if task.get("id") != known.task_id or task.get("state") not in TASK_STATES:
                raise PortError("Known cloud task cannot be authoritatively reconciled")
        if task:
            expected_agent = self.manifest.wp(wp_id)["owner"]
            if task.get("custom_agent", {}).get("id") != expected_agent:
                raise PortError("Cloud task custom-agent identity is missing/mismatched; inspect rather than guess")
            identity = identity.reconcile(Identity(task_id=task["id"], pr_number=pr["number"] if pr else None))
            return Observation(identity, task["state"], bool(pr and pr.get("state") == "open"), True)
        if pr:
            identity = identity.reconcile(Identity(pr_number=pr["number"]))
            return Observation(identity, "unknown", pr.get("state") == "open", True, True,
                               "Existing PR has no independently reconciled cloud task")
        assigned = issue and any(a.get("login") == "copilot-swe-agent[bot]" for a in issue.get("assignees", []))
        if assigned or known.task_id or known.session_id or known.pr_number:
            return Observation(identity, "unknown", False, True, True,
                               "Prior assignment/worker identity exists but no authoritative execution receipt")
        return Observation(identity, "absent", False, True)

    def launch(self, wp_id: str, attempt: str):
        issue = self.find_issue(wp_id)
        if issue is None:
            raise PortError("Cloud launch requires an existing canonical issue; sync separately with explicit consent")
        if issue.get("state") != "open":
            raise PortError("Closed issue is not a ready task or dependency completion")
        if any(a.get("login") == "copilot-swe-agent[bot]" for a in issue.get("assignees", [])):
            raise PortError("Issue already assigned; reconcile, do not repeat assignment")
        number = positive_integer(issue["number"], "Existing issue number")
        result = self.api.request(
            "POST", f"{self.prefix}/issues/{number}/assignees",
            cloud_payload(self.manifest, self.policy, wp_id, attempt),
        )
        if result.get("number") != number or not any(
            a.get("login") == "copilot-swe-agent[bot]" for a in result.get("assignees", [])
        ):
            raise PortError("Assignment response did not confirm the requested issue/assignee")
        # Assignment acceptance is NOT a successful worker launch/task completion.
        return Observation(Identity(issue_number=number), "assignment_accepted", False, True)

    def repair(self, identity: Identity, prompt: str):
        if not identity.task_id or not identity.pr_number:
            raise PortError("Cloud repair needs the existing task and implementation PR")
        body = "@copilot " + nonempty(prompt, "Bounded repair feedback")
        result = self.api.request(
            "POST", f"{self.prefix}/issues/{identity.pr_number}/comments",
            {"body": body},
        )
        positive_integer(result.get("id"), "Repair comment receipt")
        if (
            result.get("issue_url") != "https://api.github.com" + self.prefix + f"/issues/{identity.pr_number}"
            or result.get("body") != body
        ):
            raise PortError("Repair comment receipt belongs to another PR or feedback")
        return {"comment_id": result["id"], "task_id": identity.task_id, "pr_number": identity.pr_number}

    def usage(self, budget: Budget, now: float):
        tasks = self.pages(self.tasks_prefix + "/tasks", "tasks")
        tasks += self.pages(self.tasks_prefix + "/tasks?is_archived=true", "tasks")
        if len({t.get("id") for t in tasks}) != len(tasks):
            raise PortError("Duplicate/ambiguous task usage inventory")
        used, active = 0.0, []
        for task in tasks:
            if task.get("state") not in TASK_STATES:
                raise PortError("Unknown cloud task state in usage inventory")
            running = task["state"] not in ("completed", "failed", "timed_out", "cancelled")
            if running:
                active.append(nonempty(task.get("id"), "Active task ID"))
            detail = self.api.request("GET", f"{self.tasks_prefix}/tasks/{urllib.parse.quote(task['id'], safe='')}")
            if detail.get("id") != task["id"]:
                raise PortError("Usage task identity mismatch")
            sessions = detail.get("sessions")
            if not isinstance(sessions, list) or not sessions:
                raise PortError("Missing per-session usage; budget is unmeasurable")
            if any(not isinstance(s.get("id"), str) or not s["id"] for s in sessions) or len(
                {s["id"] for s in sessions}
            ) != len(sessions):
                raise PortError("Missing/duplicate session usage identity")
            for session in sessions:
                # Repairs create recent sessions on old tasks; task.created_at is not a usage window.
                if session.get("state") not in TASK_STATES or "created_at" not in session:
                    raise PortError("Missing session usage state/timestamp provenance")
                created = utc(session["created_at"])
                terminal = session["state"] in ("completed", "failed", "timed_out", "cancelled")
                if terminal and created < budget.window_start and session.get("completed_at"):
                    if utc(session["completed_at"]) < budget.window_start:
                        continue
                usage = session.get("usage")
                if not isinstance(usage, dict) or usage.get("type") != budget.unit:
                    raise PortError("Missing or unsupported per-session usage unit")
                amount = usage.get("amount")
                if type(amount) not in (int, float) or amount < 0:
                    raise PortError("Malformed per-session usage")
                used += amount
        result = Usage(budget.unit, used, now, True, tuple(active), ("cloud",))
        result.validate(budget, now)
        return result


@dataclass
class NativeHost:
    """Injected ONLY by a trusted app host; Python/Actions have no create_session API."""

    capabilities: Callable[[], dict]
    reconcile: Callable[[str, Identity], Observation]
    create_session: Callable[[dict], dict]
    get_session: Callable[[str], dict]
    send_session_message: Callable[[str, str], dict]
    usage: Callable[[Budget, float], Usage]


class LocalHostBackend:
    name = "local"

    def __init__(self, host: NativeHost, manifest: Manifest, policy: dict):
        self.host, self.manifest, self.policy = host, manifest, policy
        self.prepared_base = None
        self.prepared_wp = None

    def current_base(self):
        capabilities = self.host.capabilities()
        if not isinstance(capabilities, dict):
            raise PortError("Native host capabilities are missing or malformed")
        value = capabilities.get("default_branch_sha")
        if not isinstance(value, str) or SHA.fullmatch(value) is None:
            raise PortError("Native host did not supply a verified immutable default-branch SHA")
        return value

    def preflight(self, wp_id: str, base_sha: str):
        value = self.host.capabilities()
        if not isinstance(value, dict):
            raise PortError("Native host capabilities are missing or malformed")
        required = (
            "isolated_worktrees", "shared_ledger", "advisory_reservations",
            "read_only_reference", "credential_isolation", "authenticated",
        )
        if value.get("repository") != self.policy["repository"] or any(value.get(k) is not True for k in required):
            raise PortError("Native host lacks verified repository/isolation/path/authentication capabilities")
        self.branch = nonempty(value.get("branch"), "Native host worktree branch")
        self.worktree = nonempty(value.get("worktree"), "Native host worktree path")
        if self.current_base() != base_sha:
            raise PortError("Native host default branch changed")
        owner = self.manifest.wp(wp_id)["owner"]
        expected = next(a["sha256"] for a in self.manifest.data["agents"] if a["name"] == owner)
        profiles = value.get("profiles")
        if not isinstance(profiles, dict) or profiles.get(owner) != expected:
            raise PortError("Native host custom agent is not the trusted installed profile")
        self.prepared_base, self.prepared_wp = base_sha, wp_id

    def reconcile(self, wp_id: str, known: Identity):
        result = self.host.reconcile(wp_id, known)
        if not isinstance(result, Observation) or not result.authoritative:
            raise PortError("Native host reconciliation is missing/unverified")
        known.reconcile(result.identity)
        return result

    def require_policy_owner(self, wp_id: str, record: dict):
        raise PortError(
            "Authenticated app-native caller attestation is unavailable; "
            "injected callbacks and authority flags alone cannot establish ownership"
        )

    def same_task(self, wp_id: str, record: dict):
        known = Identity.parse(record["identity"])
        if not known.session_id or not record["branch"] or not record["worktree"]:
            raise PortError("Policy reconciliation requires the stored session, branch and worktree")
        capabilities = self.host.capabilities()
        if (
            not isinstance(capabilities, dict) or capabilities.get("caller_session_id") != known.session_id
            or capabilities.get("branch") != record["branch"]
            or capabilities.get("worktree") != record["worktree"]
        ):
            raise PortError("Authenticated native caller is not the existing session/branch/worktree owner")
        observation = self.reconcile(wp_id, known)
        if (
            observation.ambiguous or not observation.pr_open
            or observation.worker_state not in ("queued", "in_progress", "idle", "waiting_for_user")
            or any(
                value is not None and observation.identity.as_dict()[key] != value
                for key, value in known.as_dict().items()
            )
            or observation.identity.session_id != known.session_id
            or type(observation.identity.pr_number) is not int or observation.identity.pr_number < 1
        ):
            raise PortError("Policy reconciliation requires the same unambiguous active session and open PR")
        actual = self.host.get_session(known.session_id)
        if (
            not isinstance(actual, dict)
            or actual.get("session_id") != known.session_id
            or actual.get("repository") != self.policy["repository"]
            or actual.get("branch") != record["branch"]
            or actual.get("worktree") != record["worktree"]
            or actual.get("pr_number") != observation.identity.pr_number
            or actual.get("pr_open") is not True
            or actual.get("worker_state") not in ("queued", "in_progress", "idle", "waiting_for_user")
        ):
            raise PortError("Native session lookup does not confirm the stored owner and open PR")
        return observation

    def launch(self, wp_id: str, attempt: str):
        if self.prepared_wp != wp_id or self.prepared_base is None:
            raise PortError("Native launch requires exact-base/profile/isolation preflight first")
        expected_base = self.prepared_base
        self.prepared_base, self.prepared_wp = None, None
        receipt = self.host.create_session(local_payload(self.manifest, self.policy, wp_id, attempt))
        session_id = nonempty(receipt.get("session_id"), "Native session receipt")
        identity = Identity(session_id=session_id)
        try:
            actual = self.host.get_session(session_id)
            if not isinstance(actual, dict):
                raise PortError("Native session lookup did not return structured identity data")
            if actual.get("session_id") != session_id or actual.get("repository") != self.policy["repository"]:
                raise PortError("Native session receipt did not reconcile to the requested repository")
            if actual.get("attempt") != attempt or actual.get("work_package") != wp_id:
                raise PortError("Native session receipt belongs to another WP/attempt")
            if actual.get("base_sha") != expected_base:
                raise PortError("Native session started from a different base; reconcile rather than relaunch")
            if actual.get("worker_state") not in TASK_STATES:
                raise PortError("Native host worker execution state is unavailable (idle alone is not completion)")
            if type(actual.get("pr_open")) is not bool:
                raise PortError("Native host did not provide an explicit PR state")
        except (PortError, OSError, TimeoutError) as error:
            raise ReceiptError(str(error), identity) from error
        return Observation(
            Identity(session_id=session_id, pr_number=actual.get("pr_number")),
            actual["worker_state"], actual["pr_open"], True,
        )

    def repair(self, identity: Identity, prompt: str):
        if not identity.session_id:
            raise PortError("Local repair requires the existing native session identity")
        receipt = self.host.send_session_message(identity.session_id, nonempty(prompt, "Repair feedback"))
        if receipt.get("session_id") != identity.session_id or receipt.get("delivered") is not True:
            raise PortError("Native host did not confirm repair delivery to the same session")
        return receipt

    def usage(self, budget: Budget, now: float):
        result = self.host.usage(budget, now)
        if not isinstance(result, Usage):
            raise PortError("Native host usage adapter is unavailable")
        result.validate(budget, now)
        return result


class MergeBackend:
    """No admin flag; caller must hold the shared merge lane and all proven gates."""

    def __init__(self, api: Api, policy: dict):
        self.api, self.policy = api, policy

    def merge_exact(self, pr_number: int, expected_head: str, expected_base: str, *, native_stack_id=None):
        if native_stack_id is not None:
            raise PortError("Registered native stacks require the asynchronous native stack API, never legacy MergeBackend")
        positive_integer(pr_number, "Merge PR identity")
        if SHA.fullmatch(expected_head) is None or SHA.fullmatch(expected_base) is None:
            raise PortError("Merge requires immutable expected head and current base")
        if self.policy["paused"] or not self.policy["activation_approved"] or self.policy["dispatch_mode"] == "off":
            raise PortError("Paused/off policy blocks live merge")
        if not self.policy["branch_rules_proven"] or self.policy["admin_bypass"]:
            raise PortError("Strict no-bypass branch rules are unproven/unsupported")
        prefix = f"/repos/{self.policy['repository']}"
        branch = urllib.parse.quote(self.policy["default_branch"], safe="")
        rules = self.api.request("GET", f"{prefix}/branches/{branch}/protection")
        required = rules.get("required_status_checks") or {}
        names = set(required.get("contexts", [])) | {
            item.get("context") for item in required.get("checks", [])
        }
        if (
            required.get("strict") is not True
            or not set(self.policy["required_checks"]).issubset(names)
            or rules.get("enforce_admins", {}).get("enabled") is not True
        ):
            raise PortError("Live branch protection lacks strict required checks/no-admin-bypass")
        base = self.api.request("GET", f"{prefix}/git/ref/heads/{branch}")
        pr = self.api.request("GET", f"{prefix}/pulls/{pr_number}")
        if base.get("object", {}).get("sha") != expected_base or pr.get("head", {}).get("sha") != expected_head:
            raise PortError("Merge head/base changed; do not merge stale evidence")
        if pr.get("base", {}).get("sha") != expected_base or pr.get("state") != "open" or pr.get("draft") is not False:
            raise PortError("PR is not an exact-base, open, non-draft candidate")
        # Strict current-base branch protection is mandatory: REST has a head guard, not a base CAS.
        result = self.api.request(
            "PUT", f"{prefix}/pulls/{pr_number}/merge",
            {"sha": expected_head, "merge_method": "squash"},
        )
        if result.get("merged") is not True or not isinstance(result.get("sha"), str) or SHA.fullmatch(result["sha"]) is None:
            raise PortError("GitHub did not confirm a real merge")
        return result
