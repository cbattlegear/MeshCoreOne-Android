# WP-305 current-main device-write and session-seal foundation

## Identity and scope

- Repository: `cbattlegear/MeshCoreOne-Android`.
- Base and trusted admission revision: `41552c85b659396111cee6b0e0f70c404ac5a8ee`.
- Source specification: `db14559b39d32322b06477c6ae676112f583db50` (unchanged).
- Producer session: `37ae5450-e6b6-4e1d-b22a-63e695f73f0b`.
- Branch: `cbattlegear-onboarding-authority-foundation`.
- Candidate: the commit containing this document; no PR, push, hosted dispatch or merge requested.
- Acceptance relevance: prerequisite for `WP-305-behavior` and `WP-305-boundaries`, not completion
  of onboarding, its tip/UI work, Settings, device acceptance or another WP.

The coordinator assigned an independent clean-current-main foundation. Runtime/device owner
`36c8c079-3643-4bc2-876b-f4c80c3bdf4d`, storage owner
`896808b8-b03c-48e0-b93f-870598b885bd`, onboarding owner
`2f22fa7d-a4d6-4bd7-bc99-30c458e7f517`, Settings owner
`244fb3ac-2ee7-4761-874d-9617d4935209` and direct-ACK owner
`f8058663-e138-4456-9d4d-6ad11522824c` were consulted before overlapping edits.

## Source custody and adaptations

### Follow-up: queued reconnection failure authority

The independently reviewed foundation `8344344ff180faa3754928edd42c9e38e69adad1`
remains immutable. A subsequent bounded repair checks the captured connection revision and
reconnection coordinator generation, plus closed state, **inside** `deviceCommitAuthority`
before `handleReconnectionFailure` changes counters, owners, device state or publishes DISCONNECTED.
A stale waiter returns without claiming or closing any owner; successor connect retains its
normal captured-predecessor cleanup. Existing accepted-failure cleanup and cancellation paths
are unchanged. Runtime owner `36c8c079-3643-4bc2-876b-f4c80c3bdf4d` confirmed this repair is
owned here and has no competing edits.

The added real-manager `RuntimeSeamTest` control holds the actual write authority, queues a
successor WiFi connect first and an old failure second, then holds successor transport connect.
It asserts CONNECTING is not overwritten, no DISCONNECTED transition/loss/retry effect occurs,
predecessor services close once, and the successor reaches READY with a new token/live device
without closing its services or transport. All previous cases and assertions remain.
Before the repair, the queued failure would publish DISCONNECTED and increment rebuild failures
before its later ownership check; this is a source-level discrimination, not an executed result.

Fresh pinned-Python `reserve.py admit` calls admitted ConnectionManager as
`reviewed-support-consumer`, RuntimeSeamTest as `reviewed-support-test`, and this evidence as
`wp-owned`. `git diff --check` and
`C:\Python312\python.exe tools\android-port\controller\validate.py` passed (1,866 reference files,
64 WPs, 178 edges). These results apply to the follow-up working diff, not Kotlin execution.
Local Gradle remains held by `local-check:f516a7e271f444dd9cb2efcc8b995a16`;
no recovery, bypass, Kotlin compilation or test execution occurred. Exact follow-up hosted
execution and independent review remain required. Settings' separate three-argument removal
API/ACK guard is neither changed nor claimed integrated.

Selected hunks, not ancestry, were extracted from producer-approved immutable sources:

| Source | Selected behavior |
|---|---|
| `9972903c07f0bd500c1c4c1e74a39b3f73fb331c` | Versioned device contracts/helper, editor, Room context/delegation, runtime/reconnection authority, DeviceService OCV targeted writes, simulator insert-only seed, coupled test harnesses and RX controls |
| `5d7847b8ec612ec41a742ddf8b0a3efc4d2dc8bc` | DeviceRepository CAS/per-device revision and normalized region guards; 16 authored storage controls |
| `b2beb5f257b36cd3ccce7bb641f7b2ea3747e3fc` | OnboardingCompletionAttempt including isSealed; four runtime token/seal controls |

Only the added device hunk in DeviceAndContactPersisting was taken. Newer main contact APIs,
notification contracts/tests, message authority, DAOs and database schema are untouched.
No prohibited/held source was inspected or imported.

This is **not byte-equivalent to those sources**, nor does their hosted execution validate this head:

- AppConnectionPort exposes awaited `updateDevice(SelfInfo, String?, SessionToken): Boolean` and
  `updatePathHashMode(UByte, SessionToken): Boolean`; unsupported implementations throw rather than
  returning fake success. Production adapters retain the original token through the editor.
- Live publication stays inside the successful Room commit's runtime lease. Only the resulting
  known-region observer notice is deferred until the Room/runtime/editor locks have all released.
  The deferred notice also runs during late cancellation unwinding and retains its token/sequence.
- Runtime initialization insert and existing-row/identity conditional writes also retain the
  generation write lease across their actual Room work, not only pre-commit eligibility checks.
- OCV callbacks update only OCV fields under the original token, preventing an independent OCV
  writer's delayed full DTO from erasing newer preset/path-hash/live fields. The Settings event/ACK
  collector and UI are unchanged.
- The legacy optional clear-default callback remains available and is invoked outside write locks.
  Token-bound region removal remains persistence-only; Settings owns verified radio changes.

The Room authority registry is process-local and shared for stores using the **same MeshCoreDatabase
object**, matching the process-store topology. This is not a durable revision scheme across different
database objects/processes. Schema version 1 is unchanged here, not asserted as a future invariant:
the direct-ACK producer's independently authorized v2 migration must compose later. Its close/restore
and message-authority hunks are disjoint from this foundation.

## Admission and reservation result

Each extracted/edited production Kotlin path was evaluated with:

```text
python tools\android-port\local\reserve.py admit --wp WP-305 --path <canonical Git path> --operation modify-related-consumer
```

Result: `reviewed-support-consumer`, including runtime, connectivity, data, services, contracts,
app adapters and the narrow OCV callback. Existing-consumer-only, necessity, original-case/consumer
preservation, semantic-overlap coordination and exact-candidate review invariants apply.
Each added/updated Kotlin test path used `add-or-update-related-test` and returned
`reviewed-support-test`. This document and the deviation addition used `modify`, returning `wp-owned`.

The initial typed claim was actually attempted with this session, base and assignment:

```text
python tools\android-port\local\reserve.py claim --wp WP-305 --session-id 37ae5450-e6b6-4e1d-b22a-63e695f73f0b --authorization "Implement a bounded current-main device-write and session-seal foundation needed to finish onboarding, independently of other active lanes." --base 41552c85b659396111cee6b0e0f70c404ac5a8ee --path docs/android/evidence/WP-305/
```

Result: `BLOCKED: Existing WP lease is bound to different source/base/manifest/backend`.
The historical WP-wide receipt belongs to a different producer/base. It was not edited, evolved,
released, recreated or impersonated. Trusted AUTOMATION.md's local coordination contract and the
coordinator explicitly permit isolated capability-admitted authoring after retaining this result.
No new autonomous ledger worker, successor, live execution or merge authority is claimed.

## Actual local results

Host: native Windows; `python --version` reported the pinned `Python 3.12.4`. The documented
verification-venv path was absent, so the existing pinned `C:\Python312\python.exe` was used.
The existing same-host ANDROID_CI_STATE was passed explicitly; no tools were installed or recovered.
Each controller invocation used a fresh absolute session-private output directory.

| Exact command | Actual result |
|---|---|
| `python tools\android-port\controller\ci.py preflight --local --state $env:ANDROID_CI_STATE --output C:\Users\camer\.copilot\session-state\6e767589-3de4-4390-a618-1fafe7293cbb\files\preflight-foundation-01` | Exit 0; documented scaffold readiness check executed |
| `python tools\android-port\controller\validate.py` | Valid: 1,866 tracked reference files, 64 WPs, 178 dependency edges; reference pin unchanged |
| `python tools\android-port\portmap.py` | Exit 0; many-to-many provenance derived without editing shared progress |
| `python -m unittest discover -s tools\android-port\tests -p test_consumer_support_capabilities.py -v` | 41 discovered/run/passed; no failures/errors/skips |
| `python tools\android-port\controller\ci.py python --output C:\Users\camer\.copilot\session-state\6e767589-3de4-4390-a618-1fafe7293cbb\files\python-foundation-02` | Exit 0: manifest, provenance, pinned notices, parity accounting and semantic checks; scaffold 16/16 and parity 110/110 passed, zero skips |
| `git diff --check` | Passed |

These are source/Python results, **not Kotlin compilation, Room execution or product acceptance**.
No duplicate hosted CI-result bundle is retained here; any future exact-commit hosted job/log is
the authoritative automated result.

## Authored Kotlin controls, not execution evidence

- DeviceWriterAuthorityRoomTest: 18 controls (17 exact independent foundation controls from 997,
  plus the new held-Room initialization/retirement control).
- DevicePersistenceAuthorityRoomTest: 16 exact controls from 5d, including staged SQL rollback,
  real Room transaction requests, per-device/shared-store authority and normalized aliases.
- DeviceNotificationAuthorityRoomTest: 3 new controls for public AppConnectionPort old/new tokens,
  callback reentry into Room/runtime/the same editor, and exactly-once committed late-cancel notice.
- SessionBoundDeviceEditTest: 8 exact token/replacement/durable-error controls.
- RuntimeSeamTest: the four source token/seal controls plus a new concurrent explicit reject/seal
  CAS control; reconnect/disconnect seal assertions are strengthened.
- Existing reconnection, data-device and pairing cases are retained. The source's three native
  reconnection and three data-device controls are carried with their necessary harness changes.
- All current-main RX cases remain. Their pauses move to the real CAS or post-commit notification
  boundary; a retired pre-commit edit must leave Room unchanged. The source's committed-cancellation
  RX control is also carried.

Seven writer-source controls requiring the separately owned newer Settings radio/region helpers
are **not imported** into this clean-main foundation. They are not existing main tests, exclusions
from acceptance or passing controls; their producer retains them. No current-main test is deleted,
disabled or weakened to bypass a missing dependency.

## Remaining gates

Local Gradle remains held by `local-check:f516a7e271f444dd9cb2efcc8b995a16`; no invocation,
bypass, recovery, cancellation or receipt mutation was attempted. Kotlin/Room/runtime tests,
compilation and lint have **not run on this candidate**. Prior producer counts are source custody,
not passing evidence for this adaptation.

The immutable candidate must receive independent producer review before hosted validation.
No hosted ref was advanced/cancelled, no PR opened and no merge requested. Physical radio,
Android device, signing, release and human/legal acceptance were not performed.
