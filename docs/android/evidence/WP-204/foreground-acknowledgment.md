# WP-204 foreground acknowledgment verifier

## Identity and scope

Repository: `cbattlegear/MeshCoreOne-Android`.
Worktree branch: `cbattlegear-foreground-enrollment-acknowledgment`.
Exact base: `94493a7248f8ad4a4a72f9751946339418174679`.
Verified verifier candidate: `90fc6fa1a8170e70c716c6cf30a4279e011886de`.
Verifier and test source commits include `007ed6bf7c66c5988dcf8416a8fcbd98a0bebb73`,
`8ed6164ad926578167f29205a153619d00519138`, compile correction
`c786c16bd1be664f7906730a5dd07881a5edc6d8`, and lifecycle admission fence
`90fc6fa1a8170e70c716c6cf30a4279e011886de`.
Frozen Swift reference: `db14559b39d32322b06477c6ae676112f583db50`.
This is Android-only enrollment infrastructure, not Swift parity and not an
activation of notification delivery or ingress.

The verifier is a separate AndroidKeystore-authenticated SQLite ledger. It
binds a foreground presentation to the exact operation/store/creation-nonce,
acknowledgment ID, prior witness observation, warning ID/version and SHA-256
of the exact rendered warning copy. A single-use staged event must be
transactionally transitioned to consumed, synchronized and positively read
back before an uninterrupted in-process presentation can return
`NewlyVerified`. History is append-only and authenticated. Replays return
`AlreadyConsumed`; missing, mismatched, invalidated, corrupt, locked or
uncertain evidence cannot grant. Loss of witness storage does not erase the
independent acknowledgment ledger.
Request and warning byte arrays use explicit length-prefixed encoding, while
the previous HMAC remains its fixed-width raw representation. The status
discriminator is decoded exactly once before enum matching.
Synchronous `retire` and stage/consume admission use the same short lifecycle
monitor. Storage and authenticator IO happen only after the admission monitor
is released. Tests hold deterministic barriers before both admissions and
during committed writes, then inspect fresh-verifier outcomes against the
SQLite ledger.

The warning digest is an identity descriptor, not authentication, proof that
the copy was actually rendered, proof of user intent, or antirollback.
WP-308 source-only producer refs are `d1c9944b9598051875b32644bb2d1e09f1248bf8`
and lifecycle-fence successor `05e31823c883147e25763c91b89153d474341172`.
The successor contract requires synchronous `onRetirePresentation` before
scheduling `onInvalidatePresentation`; its pause/dispose tests are fixture
source, not live integration evidence. The owner has not yet supplied the
approved typed handoff for wiring the real foreground lifecycle, displayed
localized warning and explicit action to this verifier. Until that integration
is handed off and reviewed, this implementation does not prove a real user
interaction or feature readiness. No same-device snapshot rollback guarantee
is claimed.

## Ownership and advisory state

Trusted `reserve.py admit` returned `wp-owned/create` for the verifier and
focused test paths, and `wp-owned/record-evidence` for this file. The
prior WP-204 advisory receipt belonged to a different session and unrelated
paths; its mismatch was not an edit lock. Its owner confirmed there was no
unfinished work and used the supported `reconcile-merged-intent` transaction
for merged PRs #345 and #521. The terminal receipts cover the prior six paths
and retain the original leased revision-3 claim; no claim was released, cleared
or recreated. Those paths do not overlap this candidate. This session did not
perform or edit those transactions.

The trusted `Controller.admit_successor_attempt` API exists, but the inspected
`dispatch.py` exposes no successor command and explicitly refuses local live
controller calls without the NativeHost adapter. The old receipt's terminal
reconciliation is not successor admission. No substitute claim or fabricated
terminal proof was used; no successor lease was granted. This does not block
the disjoint local authoring assignment: trusted policy authorizes declared
capabilities without pre-edit approval, and feature-branch publication may
proceed without a local result as an explicitly unverified candidate. Automatic
pre-push checks are retired. The separate hard Gradle execution owner was
preserved and not bypassed.

## Verification

Passed:

- `$output = Join-Path $env:TEMP ('MeshCoreOne-foreground-ack-preflight-' + [guid]::NewGuid().ToString('N')); python tools\android-port\controller\ci.py preflight --local --state $env:ANDROID_CI_STATE --output $output` (exit 0; pinned Python 3.12.4).
- `python tools\android-port\controller\validate.py` (manifest/ownership validation).
- `python tools\android-port\portmap.py` (exit code 0; traceability report generated to stdout).
- `git diff --check`.
- Trusted admission for both added Kotlin paths.
- Hosted Android candidate run `41985544220` passed on
  `c786c16bd1be664f7906730a5dd07881a5edc6d8`.
- Hosted Android candidate run `41988883807` passed on exact candidate
  `90fc6fa1a8170e70c716c6cf30a4279e011886de`, including
  `:core:datastore:compileDebugKotlin`, `:core:datastore:testDebugUnitTest`
  (249 discovered/run/passed; 0 failed/errors/skipped), and the real SwiftData
  restore/export oracle. The two admission-race test names appear in its test
  discovery output.

Not verified:

- A prior hosted attempt, run `41984383470` on an earlier SHA, failed Kotlin
  compilation because `persist` lacked an explicit return. The fix is included
  in the passing candidates above; the later test-discovery message from that
  failed run was a compile-abort consequence, not a test result.
- Local Gradle execution: the separate hard `gradle-execution` owner remained
  active for commit `3c5297acdc083df21ec08afd7654aeaaa103ee59`; this work did
  not invoke Gradle or disturb its lock.
- Physical AndroidKeystore, reboot, process-death, power-loss, restore-device,
  foreground UI, notification ingress or radio behavior.

Related baseline evidence, not evidence for this verifier:

- The witness owner reported hosted run `41978917292` successful for exact
  source commit `94493a7248f8ad4a4a72f9751946339418174679`, including 213/213
  datastore tests and six alias cases. That source predates these uncommitted
  verifier/test files, so the result does not verify their compilation or tests.

The 18 focused verifier methods contain assertions for exact warning-copy hashing,
request-field mismatch, version/copy changes, lifecycle invalidation, restart,
concurrent verification, replay, post-commit uncertainty, corrupt/missing
independent authenticator, independent witness database/key loss, and failure
to retry the same in-memory explicit action as first-install after joint
acknowledgment-ledger/key loss. Held-synchronization tests also retire the
presentation during staged-write and consumed-write synchronization; staging
must reject and consumption must never return a fresh grant. These are source
assertions exercised by the hosted datastore test suite; they do not prove
physical AndroidKeystore durability.

The restart/replay fixture uses a fresh operation/store/creation-nonce tuple
for an independent acknowledgment; the separate same-tuple test retains the
negative assertion that a new acknowledgment ID cannot reauthorize that tuple.
