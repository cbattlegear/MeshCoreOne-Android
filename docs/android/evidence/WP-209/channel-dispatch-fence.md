# Shared channel dispatch fence candidate

**Status: UNVERIFIED, NOT INTEGRATED.** This candidate is an independently attributable
primitive and deterministic test source, not a completed #524/#530 repair or feature acceptance.

## Identity, scope and ownership

- Repository: `cbattlegear/MeshCoreOne-Android`; WP-209 / services-porter.
- Actual session: `39acddfa-f57c-4713-99f5-1a73892fe49f`.
- Branch: `cbattlegear-channel-dispatch-fence`; base: `796a872a3565a66c74f782b738a9ecb3c273fb25`.
- Frozen source: `db14559b39d32322b06477c6ae676112f583db50`, not advanced.
- Manifest: `11120a20d04408b3d21fac09180412df0490cabf1dafb8a3c98fe83e0fad31e4`;
  policy revision: `de026f7de86d91bf86ef26cc98075c2707e3f8e153f06d97ef159c401a7a792e`.
- Unchanged standalone WP-201 contract producer: `cc5f064c3c137c228a678f282f30fd50924e5394`,
  integrated as `501a3be40`. No persistence/model cohort imported.
- Owned additions: `ChannelDispatchFence.kt`, `ChannelDispatchFenceTest.kt`, this evidence;
  directly related WP-209 deviation amendment.
- Actual `local/reserve.py admit` passed `wp-owned` for the two new Kotlin paths (`create`),
  this evidence (`record-evidence`) and the deviation (`modify`).
- Actual `local/reserve.py claim --wp WP-209 --session-id 39acddfa-f57c-4713-99f5-1a73892fe49f`
  with immutable base, exact two Kotlin paths, `--operation create` and original assignment
  authorization refused: `Existing WP lease is bound to different source/base/manifest/backend`.
  No reservation acquired, released, recreated or borrowed; no ledger edited.
  The earlier generic dispatch wrapper also refused paused/unconfigured live dispatch;
  that is not authority to change dispatch configuration.

WP-405 confirms its actual frozen local head `3b2eb448aae86a5b823d6387c66c59bba14a9c42`
and no active ChannelService writer. Its #508 allocation/receipt cohort has a real semantic overlap
with channel sync/retry, allocate/write, clear, uncertainty and completion publication. Its single-PR,
combined-local-tests-before-one-push requirement is retained. No ChannelService/Support, #508 hunks,
message producer, App or runtime files are changed by this candidate.

## API and ordering

`ChannelDispatchFence.PROCESS` delegates to `ChannelIdentityMutex.PROCESS`. A fresh instance is
for isolated tests, not one instance per ChannelService. The registry binds `SessionToken`
(process epoch, generation, durable radio ID) to the exact session object. It shares a lease
among services on that connection, rejects token/object substitution and stale-token reopening,
and immediately retires future admission without waiting behind a held transport.

The shared contract defines outer `withChannelOperation`, inner `withChannelIdentityMutation`,
and `withChannelIdentityRestore` (outer then inner). Restore must own both for its entire transaction.
Channel service ordering is outer -> existing mutation lock -> repository inner -> Room.
Dispatch ordering is outer -> inner -> authoritative radio/slot epoch read -> actual protocol call.
Callbacks, cache updates, UI publication and committed receipt publication are outside every lane.
The outer lane conservatively serializes all radios; it is not merely a per-service lock.

`dispatch(lease, index, expectedOccupantID, currentOccupantID, operation)` rejects null/unknown or
unequal UUIDs before invoking `operation`. The validator receives the lease's radio ID and exact slot;
it must be a plain, non-minting read and must not reacquire the inner lock. The protocol operation must
use that same connection and index. No new epoch is sampled into an old pending intent.

The admitted protocol call runs non-cancellably while exclusion remains held. A messaging producer
must record ACCEPTED immediately inside that call's operation after a normal protocol return, finish
its own accounting outside the fence, and propagate caller cancellation without relabelling an
accepted operation as failed. Non-cancellation of a caller is not proof of end-to-end delivery.
Definite `MeshCoreException.DeviceError` rejection does not quarantine; every ambiguous exception,
including session-owned cancellation, records radio quarantine before unlocking. A logical reconnect
cannot erase it. No closure/drain reset API exists without a real, exact transport-owner proof.

The channel producer must quarantine uncertain SET attempts before releasing the mutation lane,
retain its existing DEFINITELY_UNSENT distinction, and durably invalidate uncertain occupant authority
through the persistence producer. The primitive does not infer a SET outcome or manufacture a save receipt.

## Deterministic schedules authored

Seventeen ordinary JUnit methods cover both dispatch/replacement orders, multiple services sharing a
lease, cancellation before and after admission, a held late transport, retirement during validation,
old and successor sessions, ambiguous/session-cancelled versus definite device rejection, producer
quarantine, same-epoch rename, different epoch, clear and ABA, restore before/after a whole SET/save
interval, a fresh registry consuming a supplied epoch, unknown legacy intent, wrong connection/process
or fence, and radio isolation of quarantine. These are authored methods, **zero executed/credited**.
The fresh-registry test is not a real Room migration, process restart, transport or hardware test.

## Actual checks and limitations

- `python tools\android-port\controller\ci.py preflight --local --state $env:ANDROID_CI_STATE --output <fresh absolute temporary directory>`
  — PASS, exit 0, pinned native Windows Python 3.12.4/current same-host toolchain state.
  This checks readiness only and launches no Gradle/native test.
- `python tools\android-port\portmap.py` — PASS, exit 0; provenance only.
- `python tools\android-port\controller\validate.py` — PASS, exit 0; manifest/ownership only.
- `git diff --check` — PASS, exit 0.
- JVM/Kotlin/Android tests/build: NOT RUN. The retained local execution owner
  `local-check:f516a7e271f444dd9cb2efcc8b995a16` is unchanged and not executed through.
  No local Gradle invocation, cleanup, runtime deployment, cache/lock bypass or parallel substitute.
- Hosted candidate CI: NOT RUN. Feature branch push alone does not trigger `android-ci.yml`;
  no PR or manual workflow dispatch authorized in this assignment. No CI-result bundle retained.

## Remaining integration blockers

1. ChannelService integration must be composed with the frozen WP-405 cohort, not a main-base
   overwrite. Allocation's public sync fallback cannot reacquire the non-reentrant outer lane.
   Sync/retry/write/clear completions and allocation's `finally` receipt must publish after all lanes.
2. Persistence owns durable epoch capture, rotation, invalidation, restore and text retention.
   A selected old DTO plus a newly fetched epoch is unsafe; selection must carry an atomically
   paired epoch before a suspended pending save. Identity-changing repository entry points need
   enforced ownership composition; raw DAO or inner-only mutations are not magically covered
   by this service primitive.
3. WP-208 owns inline/queue/retry/hydration integration and accepted-after-cancellation bookkeeping.
   No messaging files are independently rewritten; #535 remains separate.
4. Runtime/App must actually open/retire leases and require authoritative reconciliation before
   dispatch on a new connection. A quarantine reset needs exact physical-close/drain evidence,
   not a fresh generation, stale token, callback, timeout or PID absence. Contact-recovery runtime
   owner confirms candidate `45b590a3bc83c8da1ede7b189899bf50ca391615` does not edit
   ConnectionManager, RuntimePorts, RadioSessionContainer or retirement/physical-drain hooks.
   Its ChannelWriteDispatch completion callback is not cross-session closure proof and is not
   imported by this candidate. No runtime ownership transfer is inferred.
5. A transport that never settles holds the process operation lane, including for unrelated radios.
   Neither bounded drain nor recovery availability is established. Out-of-band physical-radio key
   mutation, actual restart, iOS/Android/device, signing, release and hardware verification are not claimed.
