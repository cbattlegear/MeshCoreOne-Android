# WP-204 foreground acknowledgment verifier

## Identity and scope

Repository: `cbattlegear/MeshCoreOne-Android`.
Worktree branch: `cbattlegear-foreground-enrollment-acknowledgment`.
Exact base and current `HEAD`: `94493a7248f8ad4a4a72f9751946339418174679`.
The verifier/test/evidence candidate is still uncommitted and has no immutable
candidate SHA.
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

The warning digest is an identity descriptor, not authentication, proof that
the copy was actually rendered, proof of user intent, or antirollback.
`RecoveryContinuityNotice` is owned by WP-308. Its owner still needs to connect
the real foreground lifecycle, displayed localized warning and actual explicit
user action to `present`, `recordExplicitAction` and `invalidate`. Until that
handoff is integrated and reviewed, this implementation does not prove a real
user interaction or feature readiness. It does not claim resistance to
same-device snapshot rollback.

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

- `python tools\android-port\controller\ci.py preflight --local --state C:\Users\camer\AppData\Local\MeshCoreOne\toolchains\windows-existing-sdk-r02-65a1304729d745f3a9b0d72d085661f5\environment.json --output C:\Users\camer\AppData\Local\Temp\MeshCoreOne-foreground-ack-preflight-81828d74eebf46ffa663fc87b25c2946` (exit 0; pinned Python 3.12.4).
- `python tools\android-port\controller\validate.py` (manifest/ownership validation).
- `python tools\android-port\portmap.py` (exit code 0; traceability report generated to stdout).
- `git diff --check`.
- Trusted admission for both added Kotlin paths.

Not run:

- Kotlin compilation and Robolectric tests. A separate hard `gradle-execution`
  owner remained active for commit `3c5297acdc083df21ec08afd7654aeaaa103ee59`;
  this work did not invoke Gradle or disturb its lock.
- Exact-candidate hosted diagnostic. The source candidate has not yet been
  committed/published, so no hosted run can verify these changes yet.
- Physical AndroidKeystore, reboot, process-death, power-loss, restore-device,
  foreground UI, notification ingress or radio tests.

Related baseline evidence, not evidence for this uncommitted verifier:

- The witness owner reported hosted run `41978917292` successful for exact
  source commit `94493a7248f8ad4a4a72f9751946339418174679`, including 213/213
  datastore tests and six alias cases. That source predates these uncommitted
  verifier/test files, so the result does not verify their compilation or tests.

The focused test source contains assertions for exact warning-copy hashing,
request-field mismatch, version/copy changes, lifecycle invalidation, restart,
concurrent verification, replay, post-commit uncertainty, corrupt/missing
independent authenticator, independent witness database/key loss, and failure
to retry the same in-memory explicit action as first-install after joint
acknowledgment-ledger/key loss. These are source assertions only until the
declared execution lane runs them.

The restart/replay fixture uses a fresh operation/store/creation-nonce tuple
for an independent acknowledgment; the separate same-tuple test retains the
negative assertion that a new acknowledgment ID cannot reauthorize that tuple.
