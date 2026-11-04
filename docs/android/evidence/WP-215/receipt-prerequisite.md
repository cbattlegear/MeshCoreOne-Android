# WP-215 isolated action receipt prerequisite

## Scope and provenance

Repository: `cbattlegear/MeshCoreOne-Android`.
Current refreshed candidate base: `cf1ee845b3d6022990d7d699cd5bb2a373c8e456`.
Current-main merge commit before the evidence refresh:
`14ed733b5bf25487617c22c72a5cb2d2d109a9e4`.
Swift specification remains `db14559b39d32322b06477c6ae676112f583db50`;
this contract and its additional assertions are Android-only.
This is the existing open PR #537 prerequisite, not a WP-215 completion claim.
The exact candidate is the Git head bound by the authenticated scope and hosted
checks, not a mutable branch name. The user authorized progressing #537 through
normal review and merge gates before separate device acceptance.

`NotificationActionReceipt.kt` is the existing contract agreed with the WP-215
notification owner, taken from candidate `70e1fe464` and identical to #535 head
`0d058b5a9d74ee25651d70eecf85490ba62bf674`. Its required Git blob is
`f0a1b0deb8b9ae7f7f77cc0f065ef74ab2565209`. This includes the later guarded
`wireNotDispatched()` addition; initial commit `8d75429` alone is insufficient.
No messaging service, dispatcher, adapter, policy, capability or build change is
part of this candidate. #535 must subsequently take a fresh main base and scope;
#514 integration still waits for the actual #535 merge.

## Admission and concurrency

Actual trusted-base `tools/android-port/local/reserve.py admit` evaluations:

| Path | Operation | Capability |
| --- | --- | --- |
| `android/core/contracts/src/main/kotlin/com/meshcoreone/android/core/contracts/notifications/NotificationActionReceipt.kt` | `create` | `wp-owned` |
| `android/core/contracts/src/test/kotlin/com/meshcoreone/android/core/contracts/notifications/NotificationActionReceiptTests.kt` | `add-or-update-related-test` | `reviewed-support-test` |
| `docs/android/deviations/WP-215.md` | `modify` | `wp-owned` |
| `docs/android/evidence/WP-215/receipt-prerequisite.md` | `record-evidence` | `wp-owned` |

Plain `create` is not used to admit the contracts test. The tests are directly
necessary to the extracted receipt, preserve all existing cases, and require
actual affected hosted test execution with positive discovery before acceptance.
The trusted manifest SHA-256 is
`11120a20d04408b3d21fac09180412df0490cabf1dafb8a3c98fe83e0fad31e4`;
the trusted policy revision is
`de026f7de86d91bf86ef26cc98075c2707e3f8e153f06d97ef159c401a7a792e`.

The existing WP-215 historical advisory row remains session/attempt
`c9bd4093-269f-48d9-afd2-5684c2b4d2ce`, revision 7, with its original bindings
and authorization. A bounded current-base `claim` refused that historical
binding; it did not alter or recreate the reservation. Supported typed
`reserve.py transfer` receipts instead identify this prerequisite producer as
session `9c5ef37a-072b-42f9-a702-503aec293897`, retain both origin and producer
attributions, and return `advisory: true, file_edit_blocked: false`.
Those receipts are session artifacts, not historical ledger edits.

The current WP-215/#514 owner, session
`2e1cab82-f7dd-4885-a0f9-7231b6354d60`, explicitly relinquished active isolated
receipt production and confirmed the semantics and exact blob. Its existing
branch remains unchanged. The retained local `gradle-execution` owner
`local-check:f516a7e271f444dd9cb2efcc8b995a16` is not bypassed, recovered,
cleared or disturbed.

## Independently expected transitions

The 10 test methods were authored by the WP-215 owner and imported after actual
path/operation admission and inspection. They assert the agreed state machine,
not observed implementation output:

- Fresh receipts are retryable, unaccepted and retrievable by coroutine context key.
- Starting wire work prevents replay; definite rejection permits a new attempt.
- Producer-proven non-dispatch permits a new attempt only from `IN_FLIGHT`.
- Successful acceptance works from `READY` or `IN_FLIGHT`, is idempotent and is
  not downgraded by a later uncertainty report.
- Uncertainty changes only `IN_FLIGHT`; it blocks replay without claiming acceptance.
- Committed mark-read work blocks replay but is not wire acceptance.
- Invalid starts, resets, commitments and terminal acceptance throw without
  mutating state, exhaustively covering disallowed states.
- The uncertain-outcome exception preserves its original cause and diagnostic.

The receipt cannot itself establish the protocol owner's external proof; tests
of this contract do not claim messaging, protocol dispatch, cancellation recovery,
device delivery or durable process-death behavior.

## Verification boundary

The documented Windows-native readiness preflight passed with Python 3.12.4,
existing same-host `ANDROID_CI_STATE` and a fresh absolute output directory:

```powershell
python .\tools\android-port\controller\ci.py preflight --local --state $env:ANDROID_CI_STATE --output (Join-Path $env:TEMP ('MeshCoreOne-receipt-preflight-' + [guid]::NewGuid().ToString('N')))
```

Readiness is not Kotlin compilation or test execution. No local Gradle invocation
is made while the retained execution owner exists. Test methods are authored,
not executed locally. These declared static checks passed during preparation:

```powershell
git diff --check
python .\tools\android-port\controller\validate.py
python .\tools\android-port\portmap.py
python .\android\parity-tests\audit.py --check
python .\android\parity-tests\semantic.py --check
```

`git hash-object` also confirmed the required production blob. These are source,
provenance and accounting checks, not execution of the 10 Kotlin test methods.
The prior exact head `795a8d6299ad4a5a8d14a6a003639d02d35eb774` on base
`cb4a79ab906db851800bcf93f7c2ebfb5aa34571` had successful hosted run
`41864822492` and an approval bound to that head. Its Android build, controller,
scope classification, checklist and aggregate passed; the Swift lanes were
skipped by scope. Those results do not verify or approve the refreshed
candidate based on `cf1ee845b3d6022990d7d699cd5bb2a373c8e456`; its exact head
is bound in the refreshed authenticated scope and must receive its own hosted
verification and independent review. Those are pending. Until both finish
successfully, this remains an unverified implementation candidate, not WP-215
acceptance or notification integration completion. No second CI-result bundle
is retained, hashed or revalidated here.

An intermediate candidate based on `ab570ebbf5e8d3402343f5ccf8a8450791f472b0`
was published at `213e64d9b2939ea9aee0659d111b91613e7f497c` with run
`41869354821`; it was still in progress when main advanced to `cf1ee845`. Its
results are not evidence for the current-base candidate.
