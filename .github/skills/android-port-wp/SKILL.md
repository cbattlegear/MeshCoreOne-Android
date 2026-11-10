---
name: android-port-wp
description: Implement or review one MeshCore One Android port work package - read the Swift source and tests, port the behavior with real tests, open one PR and let hosted CI verify it.
---

# Android port work package

Product boundaries (stack, licenses, scope exclusions, backups) are in `.github/copilot-instructions.md`.
Process: one WP, one branch, one PR, hosted CI, maintainer merges. No reservations, leases, locks,
receipts or approvals exist.

## Before coding

1. Read the WP entry in `docs/android/port-manifest.json` (dependencies, acceptance, inventory of Swift
   sources), its section of `docs/android/PORTING_PLAN.md`, and the architecture notes/ADRs it touches.
2. Read the actual Swift source and tests plus the nearby Kotlin consumers. Prefer real code over stale docs.
   Reference paths are Git identifiers; resolve them to the host's path convention.
3. Look at the current branch and integrate edits already there. Work only in the current worktree.
4. If a prerequisite WP is not merged, build on its branch or implement the small piece you need. Record what
   you assumed in the PR. Do not add fake implementations to get around ordering.

## Implementing

- Edit any file the change needs. No path is reserved; resolve collisions by rebasing.
- Ported Kotlin files start with one or more `// PortedFrom: <git path>@<reference sha>` headers. Android-only
  code has `// AndroidOnly: WP-xxx <purpose>`. Generated files have
  `// GeneratedFrom: <generator under tools/android-port/>; inputs: <path>@<sha>, ...`. Keep copyright/license
  notices. `python tools/android-port/portmap.py` checks the headers.
- Preserve source IDs, raw enums, units, defaults, ordering, validation, retry, deduplication, concurrency and
  failure behavior unless a native adaptation is deliberate. Record deliberate differences in
  `docs/android/deviations/WP-xxx.md`: original behavior, Android behavior, why, which test covers it.
- Use the approved contracts and existing helpers. Surface typed failures and localized recovery in the UI. No
  broad catches, empty substitutes, silent key/database recreation or unconditional success. Cancellation
  propagates, except a narrowly documented post-commit completion.
- Read `swift-to-kotlin` for logic and `compose-from-swiftui` for UI.

## Verifying

- Port each original case and parameter family, or document a reviewed equivalent or exclusion. Generated file
  names and line counts are not coverage.
- Use the Gradle tasks that exist (`docs/android/README.md`, `.github/workflows/android-ci.yml`); do not invent
  tasks. Make sure the runner really discovered the tests: zero tests or a skipped mandatory suite is a failure.
- Use independent vectors and oracles, real Room transactions/migrations and deterministic clocks. Never edit a
  golden or lower coverage to make a candidate pass.
- For UI, test flows and state transitions plus deterministic native screens. For platform behavior, test the
  required API level, not an unrelated proxy.
- Real radio, background, upgrade and signing behavior needs the maintainer's device. Say "not run" for it.
- Hosted `Android CI` on the pushed commit is the authoritative result. Do not commit CI results or evidence bundles.

## Done

Implemented behavior, real assertions, preserved consumer contracts and green CI. A compile-only scaffold can
satisfy a scaffold WP, never a feature WP. Name concrete items you did not verify ("physical radio test not
run"), not boilerplate disclaimers.

## Pull request

Title `[WP-xxx] <bounded change>`. Use the PR template: what changed, the real commands you ran with results,
Swift sources ported, deviations and anything unverified. Link an existing issue only; never invent one.
Return the WP, the head commit, what is implemented, what is blocked, and the PR link. "Blocked" is a valid
outcome; a success-shaped fallback is not. Stop only for a product decision, a licensing question, or missing
credentials/hardware.
