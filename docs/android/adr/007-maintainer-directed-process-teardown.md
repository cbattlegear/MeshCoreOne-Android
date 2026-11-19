# ADR 007: Maintainer-directed process teardown

**Status:** Accepted 2026-11-09 by the maintainer (@cbattlegear).
Supersedes [ADR-005](005-fast-local-pre-push.md), [ADR-006](006-automatic-supervised-reservations.md) and the
automation sections of [`PORTING_PLAN.md`](../PORTING_PLAN.md).

## Context

The maintainer is the only person who approves, merges and decides on this repository. By November 2026 the repository's
own process made that person wait on machinery instead of reviewing code. An audit of every pull request, workflow run,
live session and setting on `main` at `b9193a1fb9` found:

- 390 pull requests: 362 merged, 10 closed, 18 open. 13 of the 18 open ones conflicted with `main` and 11 were red on
  `android-ci` (the red statuses on conflicted branches were stale, because conflicted pull requests do not trigger
  `pull_request` workflows).
- The largest open pull request (WP-302 navigation, #495) had 109 files, 144 commits and 98 reviews. A second pull
  request (#515) was green but held back as "do not merge independently of #495".
- GitHub itself required almost nothing: branch protection required no status checks and no reviews. Everything that
  blocked work was imposed by this repository's own policy files, workflows and tooling.
- Work had to be claimed, reserved, leased, admitted against a typed capability rule, receipted, reviewed by a bot,
  shepherded through bounded repair rounds and checked by a gate-integrity job before a person could merge it. Each layer
  could be stale, mismatched or blocked on a different layer: an "advisory claim mismatch", an unknown execution identity,
  a stale SHA, a paused dispatcher or a missing budget stopped real edits that nobody had any reason to stop.
- An execution lock serialised local Gradle and emulator runs across every worktree on the maintainer's machine (about
  160 of them). No workflow used it. It only made local checks wait.
- The evidence directory held 2,259 files, mostly per-WP receipts and run logs that restated results CI had already
  produced. Each one was one more byte-exact thing to keep consistent.

## Decision

Remove the process machinery and keep the product constraints, the real checks and the reference material.

**Removed** (all of it is recoverable from git at `b9193a1fb9`):

- The reservation, lease, claim, transfer and execution-lock tooling (`tools/android-port/local/`, the code behind the
  shared SQLite ledger and the controller modules that implemented capability admission, gates, receipts, dispatch and
  status).
- The agent roster (17 `.github/agents/*.agent.md`), the dispatch, parity-review, PR-shepherd, gate-integrity and
  upstream-sync workflows, and the `needs-human` repair loop.
- The automation and golden-vector policy files, per-WP verification/receipt checkers, the test-case registry and the
  parity, accessibility and scaffold verification harnesses that only enforced the above.
- The process documents: `AUTOMATION.md`, `docs/android/ci/`, `docs/android/parity/`, the scope-amendment bundle, the
  local-check and build runbooks, and the WP-000 and WP-003 deviation logs.
- 1,953 of the 2,259 files under `docs/android/evidence/`. The 306 that remain are oracle sources, vector generators and
  correspondence notes that Kotlin, Python and Gradle code or tests cite. Some of those notes, the deviation logs and the
  manifest's `write_paths` still name collectors or receipts that were removed (for example `generated_by` in
  `evidence/WP-205/source-cases.json`). No code reads those references; the removed files are in git at `b9193a1fb9`.

**Kept as historical data only:** `port-manifest.json` still carries per-WP `owner`, `human_gate`, `supervised`,
`planned_state`, `write_paths` and `verification` fields. Nothing reads them. The manifest is the dependency graph and the
Swift-file-to-WP ownership map, nothing more.

**Replaced with:**

- `.github/workflows/android-ci.yml`: one workflow that runs on every pull request and every push to `main`, with an
  `android-ci` job that fails if any of `source-checks`, `build`, `swift-codec-oracle` or `swift-backup-oracle` fails. The
  benchmark harness runs only on `main` and on manual dispatch. Plain Gradle with strict dependency verification does the
  work. `.github/actions/setup-android` holds the shared toolchain setup, and `copilot-setup-steps.yml` reuses it.
- A merge rule with nothing to ask permission for: the maintainer squash-merges when `android-ci` is green. There is no
  required review, review bot, approval gate, ownership claim or lock. Anyone, human or agent, may take any work package.
- Short instructions: [`docs/android/README.md`](../README.md), `.github/copilot-instructions.md`, the
  `android-port-wp` skill and the pull request template.
- `tools/android-port/portmap.py` and the slimmed `tools/android-port/portlib/`: the manifest validator (pin binding, work
  package DAG, one primary owner or reviewed exclusion per Swift file) and the `PortedFrom`/`AndroidOnly` header check.

## What stays true

These are product and legal constraints, not process, and they are unchanged:

- Native Kotlin and Material 3 Compose; a pure-JVM protocol module; minSdk 31, compile and target SDK 37; application
  ID `com.meshcoreone.android` (`.debug` for debug builds).
- The Swift reference stays read-only and pinned at `db14559b39d32322b06477c6ae676112f583db50`. Bumping the pin is a
  deliberate manual change (see the README, Maintenance).
- GPLv3 for the app, MIT for the MeshCore notices and every dependency, model, data and asset notice, checked by
  `sync_notices.py`. Dependency locks and verification metadata stay strict.
- Mesh messaging, persistence and core navigation work without internet or Google Play services. Message translation
  stays out of scope. All themes are unlocked, and there is no billing, analytics, updater, new backend or APK installer.
- Backups stay compatible with iOS in both directions (envelope v1, Unix seconds, zlib, 50 MiB compressed and 512 MiB
  expanded) and are proved by real codec evidence, not asserted.
- Nobody claims hardware, device, signing, iOS or license verification that did not happen.
- Distribution is sideloaded APKs from GitHub Releases, from WP-506 on.

## Consequences

- Review is the maintainer reading the diff and the hosted CI result. Nothing waits on a bot, a ledger, a lease or a
  person other than the maintainer.
- There is no automatic parity reviewer, protected-path check or dispatcher. If parity regressions or unreviewed
  workflow edits become a problem, add the specific check that catches the specific problem, as an ordinary pull request.
- A work package is complete when its pull request is merged and CI is green. A closed issue and an idle session never
  mean anything, and there are no completion receipts.
- Hosted CI is the evidence for every automated check. Evidence is committed only when it cannot be reproduced by a
  workflow: hardware, device, signing, release and legal results.
- The old documents still say things like "claim the WP" or "reserve the path". Where those appear in an ADR or deviation
  record, read them as history.

## Known gaps

- The bidirectional backup proof has a gap that predates this change: the Swift oracle restores the Kotlin export, but
  the Swift-written export is not fed back into the Kotlin restore test in CI (the committed Swift reference fixture is).
  The old workflow did not close it either.
- Instrumentation tests compile in CI but no emulator or device runs them. Hardware, signing and release evidence does
  not exist yet.

## Not touched

- GitHub repository settings and branch protection. Branch protection requires no checks and no reviews, and whether
  branches must be up to date before merging is the maintainer's setting.
- Local leftovers of the old tooling (a shared ledger under the main checkout's `.git` directory and a
  `meshcore-reservations/` runtime directory). Nothing reads them any more, so they can be deleted by hand.
