# WP-214 nonactivated receive-envelope prerequisite

## Binding and scope

- Repository: `cbattlegear/MeshCoreOne-Android`; session:
  `bb7109c3-0f71-4248-bce3-6793a34fab7f`; branch:
  `cbattlegear-wp-214-sync-concerns`.
- Trusted base: `99479711bfdb7c621765f09d671068ca8b8318ce`.
  The candidate is the commit containing this record; the PR identifies its exact head.
- Read-only Swift reference: `db14559b39d32322b06477c6ae676112f583db50`.
- Trusted manifest SHA-256:
  `11120a20d04408b3d21fac09180412df0490cabf1dafb8a3c98fe83e0fad31e4`;
  review-policy revision:
  `de026f7de86d91bf86ef26cc98075c2707e3f8e153f06d97ef159c401a7a792e`.

This is an explicitly assigned WP-214 prerequisite, not a second implementation
of the completed timestamp-range repair (#392 / #488). Its producer edits only
the new contracts carrier, actual message poller, new poller test and these
WP-owned evidence/deviation records. The existing sync registration, handler,
ordinary save, timestamp recovery and downstream effects remain unchanged.
No application consumer installs the new opt-in handler.

## Actual admission and overlap disposition

Trusted `tools/android-port/local/reserve.py admit --wp WP-214` returned:

| Path | Operation | Capability |
| --- | --- | --- |
| `android/core/contracts/src/main/kotlin/com/meshcoreone/android/core/contracts/domain/ChannelReceiveEnvelope.kt` | `modify-related-consumer` | `reviewed-support-consumer` |
| `android/core/services/src/main/kotlin/com/meshcoreone/android/core/services/messaging/MessagePollingService.kt` | `modify-related-consumer` | `reviewed-support-consumer` |
| `android/core/services/src/test/kotlin/com/meshcoreone/android/core/services/messaging/ChannelReceiveEnvelopeTest.kt` | `add-or-update-related-test` | `reviewed-support-test` |
| `docs/android/evidence/WP-214/receive-envelope.md` | `modify` | `wp-owned` |
| `docs/android/deviations/WP-214.md` | `modify` | `wp-owned` |

These are real path/operation admissions, not a successful claim receipt.
The attempted WP-214 advisory claim was refused with
`Existing WP lease is bound to different source/base/manifest/backend`.
Its historical identity was not rewritten, released or recreated. Current
`AUTOMATION.md` distinguishes this advisory mismatch from an actual conflicting
write and permits isolated, assigned, capability-admitted authoring.

The coordinator supplied the bounded WP-307 poller-custody reconciliation and
13a/bef/b5 disjoint dispositions. b5 retains DTO/history/storage; bef retains
queue support. Neither history snapshots nor a public UUID confer receive proof.
No custody transfer is inferred. A subsequent complete check of all 18 open
published PR file lists (#495 through #540, not a numerical range of ownership)
found no overlap with these five exact paths; neither PR-list nor file-list
pagination was truncated. This check is a point-in-time published-source
observation, not global clearance for unpublished work. Real new overlap blocks
integration.

## Carrier and occurrence behavior

`ChannelReceiveEnvelope` carries the generation-owned session token, original
parsed channel message, delivery context, host occurrence UUID and typed
`ChannelReceiveOrigin`. Every current production construction uses `Unproved`.
The reserved `Proved` arm has a private constructor and no mint/copy factory.
Its envelope guard binds any future legitimately introduced proof to the same
session and parsed message object. No public slot/key/UUID input can create it.
A genuine future authority requires separate producer admission and review; this
slice does not claim that such authority exists.

The same parsed `ChannelMessage` instance fans out from `SessionCore` to the poll
response and event subscriber. Only the opt-in route uses that object identity
to pair those two host observations. Separately parsed messages with identical
fields remain separate occurrences. This is not a firmware packet identifier,
cryptographic provenance, replay defense or persistent identity.

Delivery objects retain their envelope while observed/buffered, parked and
retried. Pairing an event with its poll response preserves the occurrence and
origin while selecting the existing `InitialSync` context. Expected echoes
retain the original delivery; typed matching cannot consume a distinct equal
event. Contact echo counts and legacy structural channel comparison remain
unchanged. Selecting either channel handler replaces the other, never calls
both, and leaves ownership, lookup, failure, cancellation and close handling in
the existing dispatch path. Install the opt-in handler before receiving;
switching later cannot recover observations already coalesced by legacy logic.
Current channel metadata remains a separately named callback argument, not proof.

## Limits and producer requirements

The coordinator's firmware evidence is source-specific:
`meshcore-dev/MeshCore@81dbae592ffad40b4212bc0b37e82b38da08f4bc`.
For that revision, index-only queued receives and non-fencing empty responses
invalidate host-only slot snapshot/drain promotion. This implementation does not
re-run that assessment or claim physical-radio or other-version verification.
Firmware backlog across replacement/reconnect and same-key ABA therefore remain
unproved. The test scripts model those host interleavings, not firmware behavior.

A genuine proved producer must establish original occupant incarnation at the
authoritative receive/decryption boundary, bind it to the actual receive
occurrence/session, and preserve that binding through asynchronous fanout,
backlog and reconnect. It cannot sample a later slot or infer authority from a
secret, sync completion, drain or local UUID. A future adapter would require
exact admission, overlap reconciliation and discriminating independent evidence;
it must not add an ordinary `Proved(...)` factory.

This carrier does not make the unchanged ingestion path safe for unverified
history or its downstream unread, notification, mention, reaction, repeat, sort
and logging effects. No unbound-history presentation/product policy, provenance
promotion, data deletion, storage integration or runtime activation is selected.
All existing local-execution/user and protected merge holds are preserved.

## Authored cases and actual validation

`ChannelReceiveEnvelopeTest` authors 13 native dynamic cases, using the existing
real session/parser/raw-transport harness with controlled scheduling:

- Equal V1/V3 raw inputs produce separate occurrences, each explicitly Unproved.
- Held poll returns and reordered equal observations select the actual poll
  message object and preserve `InitialSync` versus `Live` context.
- Delayed/reordered echoes do not swallow a distinct equal live occurrence.
- Parked retry retains the same envelope across different-key replacement and
  same-key ABA; current metadata cannot promote origin.
- Legacy/typed replacement, clear-and-retry, outstanding echoes in both switch
  directions, generation retirement/reconnect and consumer-initiated close retain
  the existing ownership/failure behavior.
- The reserved proof state exposes no public constructor or copy/mint factory.

These supplement, not replace or newly credit, pinned original Swift families.
The existing harness cleanup wrapper supplies isolation; the `WP-214` dynamic
names are native additions, not invented original-source case identities.
No forged Proved instance is used to claim producer or runtime-proof coverage.

Actual commands before publication:

| Command | Result |
| --- | --- |
| `python -B tools/android-port/controller/ci.py preflight --state $env:ANDROID_CI_STATE --output <absolute-session-files>/receive-envelope-readiness --local` | PASS, exit 0; environment readiness only. |
| `git diff --check` | PASS, exit 0. |
| `python -B tools/android-port/controller/validate.py` | PASS, exit 0. |
| `python -B tools/android-port/portmap.py` | PASS, exit 0; mapping is not feature acceptance. |
| `python -B tools/android-port/controller/verification_config.py --check` | PASS, exit 0. |
| `python -B tools/android-port/test_inventory.py --check` | PASS, exit 0; pinned inventory only, not execution of these cases. |
| `python -B android/parity-tests/audit.py --check` | PASS, exit 0; original-case mapping audit only. |
| `python -B android/parity-tests/semantic.py --check` | PASS, exit 0; semantic mapping audit only. |

An intermediate mixed `PortedFrom`/`AndroidOnly` header on the existing poller
was rejected by `portmap.py`. The poller keeps its original source disposition
and uses the existing `Native adaptation` convention for this slice; the final
mapping check passed without weakening a validator.

Local Kotlin compilation/Gradle/native execution was not run: the existing
execution/user holds remain. Thirteen is an authored count, not a discovered or
passed count. This is an explicitly **unverified product candidate** pending the
existing exact-head hosted build/tests and trusted review. Exact-commit hosted
job results and logs are authoritative; no second CI-result bundle is retained.
No iOS, Android device, firmware/hardware, signing, release or legal verification
is claimed.
