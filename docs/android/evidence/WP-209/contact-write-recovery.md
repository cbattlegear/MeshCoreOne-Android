# WP-209 contact-write recovery scope

Repair: [issue #517](https://github.com/cbattlegear/MeshCoreOne-Android/issues/517),
producer prerequisite for WP-302's contact-confirmation evidence in
[PR #495](https://github.com/cbattlegear/MeshCoreOne-Android/pull/495).
Owner: `services-porter`, directly assigned Project child
`b3bf2b08-7b1d-4c4d-b34a-e0cafa4ba371`.

## Source and admission

- Reference remains `db14559b39d32322b06477c6ae676112f583db50`.
- Authoring base: `1e1ff70345f203b41f9a8b04836c2cc4640500c9`.
- Manifest SHA-256: `11120a20d04408b3d21fac09180412df0490cabf1dafb8a3c98fe83e0fad31e4`.
- Trusted policy Git blob: `b12794157978d6d90ab7508d4e2852fdc2d5e4a4`;
  reservation policy revision:
  `de026f7de86d91bf86ef26cc98075c2707e3f8e153f06d97ef159c401a7a792e`.
- WP-207's merged implementation `d147865c15d09b7e9f9956db3e8a0a10e54d5134`
  is an ancestor of the base. Prior WP-209 producer PR #481 is merged at
  `f581d82d64ee64577ccdfeb6120c0e5d607f11a7`.

## Current-main integration

- Current trusted main: `796a872a3565a66c74f782b738a9ecb3c273fb25`.
- Non-rewriting integration commit: `1816a3f47`; its parents are the previously
  diagnosed candidate `a58b015fbde677b1db20fe61b2fba304ce4191a2` and current main.
- The overlapping protocol edits are additive. Current main adds the separate
  `MessageWriteDispatch` messaging overloads to `MessagingSessionOps` and
  `MeshCoreSession`; the WP-209 contact overload remains on
  `ContactSessionOps` and continues to own contact persistence inside the same
  serialized response exchange. Both use the existing serializer and
  `ChannelWriteDispatch` fence. The messaging additions do not change contact
  request bytes, ACK correlation, the non-cancellable contact persistence tail,
  or caller cancellation behavior. The merge had no conflict markers or
  discarded edits.
- All 15 WP-209 changed paths below were re-admitted against current main with
  their actual operation and trusted capability. The inherited WP-107 additions
  are base content, not new WP-209 changed paths.

The supported `reserve.py admit --wp WP-209 --path <path> --operation <operation>`
matched each actual edit before authoring:

| Actual surface | Operation | Trusted capability |
|---|---|---|
| `core/services/.../contacts/ContactService.kt`, `ContactWriteRecovery.kt` | `modify` | `wp-owned` |
| `core/services/.../contacts/ContactsTestSupport.kt`, `ContactWriteRecoveryTests.kt` | `modify` | `wp-owned` |
| WP-209 deviation and this evidence document | `modify` | `wp-owned` |
| `core/protocol/.../session/SessionRoles.kt`, `MeshCoreSession.kt` | `modify-related-consumer` | `reviewed-support-consumer` |
| `core/contracts/.../domain/DeviceAndContactPersisting.kt` | `modify-related-consumer` | `reviewed-support-consumer` |
| `core/data/.../repository/ContactRepository.kt` | `modify-related-consumer` | `reviewed-support-consumer` |
| `app/.../container/AndroidPlatformAdapters.kt` | `modify-related-consumer` | `reviewed-support-consumer` |
| `core/data/.../repository/ContactWriteCompletionRoomTest.kt` | `add-or-update-related-test` | `reviewed-support-test` |
| `core/services/.../device/DiscoveryTestRoles.kt` | `add-or-update-related-test` | `reviewed-support-test` |
| `app/.../container/ContactRecoveryFlagsTest.kt` | `add-or-update-related-test` | `reviewed-support-test` |

These are Android-relative source/test surfaces; no build configuration, resource,
source pin or UI-owned file is changed. The support edits are directly necessary:
the existing protocol owner must retain the persistence continuation, Room must
make the newer-row decision atomically, and the existing process preference
adapter must report durable-write failure rather than claiming recovery was saved.
The discovery test role explicitly rejects the added abstract persistence operation,
preserving the existing repository contract invariant and its unrelated tests.

The initial supported `claim` attempt was refused with
`Existing WP lease is bound to different source/base/manifest/backend`.
Predecessor attempt `05c48790-1100-4158-b981-c97a525a6dcb`, revision 6, is retained.
No new lease/successor is claimed and no historical authorization, ledger or
execution lock is rewritten. Trusted `AUTOMATION.md`'s current local-authoring
contract and the coordinator's explicit direction permit isolated admitted
authoring despite that historical advisory mismatch. WP-302 confirmed no active
ContactService/service-test overlap; its UI stays with its existing owner.

## Behavioral evidence to execute

`ContactWriteRecoveryTests` contains 15 deterministic methods using an actual
`MeshCoreSession` and controlled raw frames, not a fake contact session:
queued-before-send cancellation; retired caller plus late successful ACK;
identical/different replacement delivery correlation; active and late rejection;
unanswered radio acceptance followed by a fresh-session full sync; active and
orphaned local-save failure; accepted persistence-tail serialization; greater and
equal-timestamp newer metadata, including a live superseded completion that must
not return import success; failed durable recovery recording with explicit
failure and process-retained uncertainty; teardown/different-radio replacement; partial
snapshot and cross-service newer-uncertainty fencing. Service persistence here is
an explicit in-memory role, not claimed Room evidence.

`ContactWriteCompletionRoomTest` has three real-Room methods for transactional
radio isolation, complete local metadata preservation, stale/equal-time CAS
protection, insertion and intentional equal-time updates.
`ContactRecoveryFlagsTest` has two Robolectric methods for disk-backed adapter
replacement and explicit failed-commit reporting.

These new native regressions supplement, not replace or relabel, the frozen
ContactService original cases. They address `WP-209-behavior` and
`WP-209-boundaries`; they do not complete unrelated WP-209 acceptance.

## Verification boundary

Use the declared interlocked candidate launcher or the existing Android candidate
CI workflow. Local Windows readiness is checked with pinned Python 3.12.4,
the existing same-host state and a fresh absolute preflight output directory as
documented in `docs/android/ci/local-checks.md`.
The shared `gradle-execution` owner
`local-check:f516a7e271f444dd9cb2efcc8b995a16` lacks genuine cleanup evidence and
remains untouched. Do not start local Gradle, bypass it through another launcher,
or infer cleanup from process absence.

The exact committed candidate and its original hosted run/job logs are the
authoritative reproducible execution record; no duplicate result bundle belongs
in this directory. A manually dispatched feature-branch diagnostic is not
PR-bound protected-check, local-device, repair-acceptance or merge evidence.
Positive discovery and passing actual Kotlin/Room methods are required before
claiming their behavior verified. The run for `a58b015fbde677b1db20fe61b2fba304ce4191a2`
remains historical diagnostic evidence only; it does not verify the current-main
integration commit or its eventual successor candidate.

WP-302 owns mounted confirmation cancellation/re-entry evidence. No physical
radio, Android device, signing, iOS or hardware execution is claimed here.
