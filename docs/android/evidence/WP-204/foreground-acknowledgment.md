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
source, not live integration evidence. The coordinator subsequently assigned
the actual foreground enrollment producer to session
`c8e06c81-e88c-429e-97af-e85f06bbfa19`; the composer source is not that producer.
The real producer now has mounted warning/action and lifecycle integration,
with exact-candidate execution tracked below. Automated mounted interaction
is not physical user-consent evidence or feature readiness. No same-device
snapshot rollback guarantee is claimed.

## Typed consumer integration successor

The branch integrates trusted main
`1a8b406a67d44006f14ac043ac468b6adf07a9d7`, including merged #571's
`NotificationEnrollmentCoordinator`, through merge
`51c3f1087`. The adapter source is
`96a2512cae9e359571cde4fc5d343431324abc44`.
`ForegroundEnrollmentWitnessAdapter` maps every operation/store/nonce,
acknowledgment, full prior record, receipt, revision, phase and witness identity
without generating or adopting replacements. It preserves `newlyAuthorized`
exactly, including false on replay, and never turns a stale, failed or uncertain
mutation into a successful write. Missing/authentication failures map to the
coordinator's coarse identity-mismatch category; locked/unavailable storage maps
to storage-unavailable. Cancellation and unexpected exceptions propagate rather
than causing retries or fabricated acknowledgment.

The six adapter methods use deterministic port doubles to assert mapping, not
real consent or journal durability. Constructing the adapter installs no
notification ingress, publication or default enrollment service.

The witness owner's exact independently reviewed successor
`b75555a0b22e493a2a9117f25e1fcfde871ee60c` is integrated without editing
its production/test paths. It prevents a stale acknowledgment expecting a
previous record from allocating replacement storage after that storage is
deleted. It also retains nullable-blob corruption and typed platform-failure
controls. Its source review and execution remain bound to its own PR #576 and
exact candidate; integration does not relabel those results.

Verifier successors `321a82532` and `1bf569fcf` add typed rejection of foreign
nullable history/head blobs, direct independent decoding assertions for staged,
consumed and invalidated event layouts (including the raw 32-byte previous MAC),
reopen controls, and a typed boundary around platform alias lookup. These
successors require fresh exact-candidate execution beyond the earlier passing
verifier run.

The agreed producer contract derives warning
`notification-enrollment-new-generation`, version 1, from the exact four
rendered title/body/confirm/cancel strings in that order. Each string contributes
its UTF-16 `String.length`, a colon and the unchanged string, without trimming
or normalization. Any additional displayed consent-bearing copy must become
part of that descriptor. The immutable request is predeclared before display;
neither its UUIDs nor this copy digest is consent. Retirement must synchronously
fence the opaque presentation before scheduling durable invalidation. An
already admitted or uncertain action cannot be retried as a fresh presentation
grant. This is a coordinated interface, not evidence that the UI has executed.

## Retirement before ledger allocation successor

The coordinator identified a narrower ordering after explicit-action admission:
hold `KEY_CREATED` before file creation, synchronously retire, await invalidation
while the ledger is absent, then release staging. Previously the late writer
could retain a `STAGED` row after invalidation had returned without a file.
Both same-process and fresh-process verification still reject that presentation;
this is not evidence of an authorization bypass. Nevertheless the writer must
complete the retired action's terminal ledger transition rather than relying
on a second UI invalidation callback after its presentation has been removed.

The staging writer now transitions its exact evidence to `INVALIDATED` after
durable staging if its lifecycle recheck finds retirement. It returns `Rejected`
only after confirmed invalidation; a missing/unconfirmed transition or reported
storage failure is `Indeterminate`, never a fresh grant. IO remains outside the
lifecycle monitor.

Two new native-SQLite controls cover the pre-file `KEY_CREATED` ordering and
cleanup write failure. The first independently decodes exact
`STAGED`/`INVALIDATED` history, reopens the verifier, and requires witness
rejection without a witness file or key allocation. The second injects a
cleanup row-write failure and requires reported uncertainty and rejection by
both current and reopened verifiers. These bring the focused suite to 23 methods
at SDK31/37. Same-host readiness, manifest/ownership validation, portmap and
`git diff --check` passed; exact successor hosted execution is pending.
No earlier run verifies this repair.

The adjacent `ALLOCATED` control holds the actual empty SQLite file before
schema creation. Invalidation now defers only while this verifier's live
allocating writer owns initialization; that writer still validates/commits the
schema and performs terminal retirement cleanup. Its short in-memory ownership
flag is cleared in `finally`, including failed allocation. It is not durable
consent, a first-install marker, or proof about another process. The control
requires no false corruption report and exact `STAGED`/`INVALIDATED` history.
A separate injected allocation failure leaves an abandoned empty file, clears
the ownership flag and requires typed corruption on later invalidation and
uncertainty after reopen. Genuine abandoned/corrupt storage is not suppressed.
These controls bring the focused suite to 25 methods at SDK31/37; successor
execution remains pending.

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

The adapter production path was separately admitted by trusted
`app-build-launcher/add-direct-support-wiring`; its new test path was admitted
by `reviewed-support-test/add-or-update-related-test`. The verifier source/test
repairs were freshly admitted as `wp-owned/modify`. No resource or composer
file was copied under the existing-consumer rule.

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

Passed for its own exact candidate only:

- Hosted run [41994415358](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41994415358)
  passed on exact adapter candidate
  `96a2512cae9e359571cde4fc5d343431324abc44` on fresh immutable diagnostic
  ref `wp204-foreground-adapter-96a2512c-20261105-022557`. The actual Linux
  job log discovers all six adapter methods and the 36 SDK-specific executions
  of the then-current 18 verifier methods. The Linux build, controller policy,
  Swift codec and SwiftData restore/export jobs all passed. It predates the
  witness successor reconciliation and the new verifier codec/corruption tests.
  Its inputs and workflow are unchanged; no later source is attributed to it.
  The independent expected-record assertions in `834aaf637` likewise require
  successor execution.

The real producer source was reviewed at
`dec5bf2b820b958f8e4900ad86bcdc61ef908d1b`, and the producer owner repaired
the reported class-companion placement, stale failed-read epoch path and direct
dialog focus/lifecycle confirmation boundary in `1c463663a`. Its test suite
adds actual warning actions through the real verifier, witness and journal,
including native-SQLite pause/dispose barriers before stage and consume.
These are reviewed source assertions, not passing UI execution evidence.
One combined exact-candidate hosted run is coordinated with the producer;
there is no duplicate verification bundle or transfer of the earlier run.

Combined execution attempts (preserved, not attributed to successors):

- Run [41999079789](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41999079789)
  on `3fc62cb36c770a7c152eba4f9dfaad020f17a34b` failed app test compilation:
  an invalid `assertDoesNotExist` import and accidentally nested `retireHost`
  helper. Missing JUnit results were a compilation consequence, not executed
  test failures.
- Run [41999738888](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41999738888)
  on `3b360f056f27d9b5ceb1bfcc66be1b203db76eba` compiled and executed
  2,058 app tests with 24 failures: twelve mounted methods at both SDKs.
  The warning retired at the initial dialog-focus check because Robolectric
  had focused the activity but not its dialog. This was not passing mounted
  interaction evidence.
- Successor `aee0f1f0166bf29d45e1d14d1a311ed88eacea23`, immutable ref
  `diagnostic-wp303-enrollment-aee0f1f01-20261105`, supplies the missing
  dialog view window-focus event in the test fixture before the posted initial
  focus observation. Reviewed test diff leaves production guards unchanged;
  actual focus-loss and queued-confirm negative controls remain present.
  Its combined run
  [42002017581](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/42002017581)
  failed after compiling and executing 2,058 app tests with the same 24 mounted
  failures. The warning was still absent at display/request assertions, and
  the focus-loss test encountered a null dialog window. The fixture change did
  not establish a working focused dialog. The actual adapter suite logged
  `END|SUCCESS`, but the mounted real-ledger discriminator remains unverified.
  The producer owner has the exact assertion locations for repair; no product
  guard was weakened and no earlier outcome is transferred to this attempt.
  Classification, controller policy and Swift codec jobs passed; SwiftData
  restore/export was skipped following the Android job failure.
- Run [42004265838](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/42004265838)
  on `7401b75380ba8dba4bd3ce8a972cc5e5f3473000` failed in the test-only
  focus helper: the dialog decor had no `ViewRootImpl` immediately after
  `Dialog.show`. This attempt did not establish the mounted real-ledger flow.
  Reviewed successor `10c1d31e5252132b674d46c722dc8b9d32bcd2af` moves
  actual root focus delivery to the decor's one-shot attachment callback and
  asserts attachment, root existence and `hasWindowFocus`. It retains the
  no-initial-focus negative control and changes no production consent guard.
  Its exact run
  [42009037096](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/42009037096)
  executed 2,060 app tests with 12 failures (six mounted methods at both SDKs).
  Warning display/descriptor, recording-backend confirmation/dismissal,
  recreation and no-initial-focus controls no longer failed. Five real-ledger
  methods still timed out with Android Main continuations queued; the remaining
  standalone focus-loss method acted before mounted composition was idle.
  This is not passing real-ledger integration evidence.
- Reviewed successor `618ed213f5f7bb2d376b3c99a530a91fc8e942e0` waits
  for mounted idle after the actual start action and pumps the real Main looper
  while awaiting IO/admission outcomes instead of blocking Main with a latch.
  The positive control still requires `BoundSnapshot`, exact
  `STAGED`/`CONSUMED` history and reopened stores; negative controls retain
  their actual admission barriers and exact ledger assertions. Production
  source is unchanged. Exact run
  [42011995652](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/42011995652)
  is pending; no earlier outcome is transferred to it.

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

The 21 focused verifier methods contain assertions for exact warning-copy hashing,
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
