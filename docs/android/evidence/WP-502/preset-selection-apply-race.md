# Mounted onboarding preset selection/apply race

Refs [#405](https://github.com/cbattlegear/MeshCoreOne-Android/issues/405)
and [#135](https://github.com/cbattlegear/MeshCoreOne-Android/issues/135).
Production repair belongs to WP-305
[PR #532](https://github.com/cbattlegear/MeshCoreOne-Android/pull/532);
this WP-502 candidate changes only its mounted test and this evidence.

## Binding and custody

- Base: `1e1ff70345f203b41f9a8b04836c2cc4640500c9`.
- Frozen source: `db14559b39d32322b06477c6ae676112f583db50`.
- Source inputs: `MC1/Views/Onboarding/PresetStepView.swift` and
  `MC1/Views/Onboarding/RegionStepView.swift`. The preset view's blob is
  `0613b432da5862e2c8e417ce97f5725f21404204` at both source and base.
- Existing canonical WP-502 actor/attempt:
  `3bc8c1b8-20ec-4647-88ae-db3775268489`; runtime alias
  `fb112832-0a71-4f6d-abc0-f07f80eaeb8b`.
- Trusted `wp-owned/modify` admission and same-owner evolution for
  `OnboardingRegionPresetComposeTest.kt` succeeded idempotently at revision75.
  Adding this owned evidence through `wp-owned/create` returned revision76.
  The original receipt binding/authorization was not rewritten.
- Recorded WP-211 localization and WP-503 test intents were inspected.
  The two localized picker cases already landed in base; no open candidate
  from either recorded branch was found. All incumbent methods, helpers,
  fixture ports and localization additions are preserved byte-for-byte.
  Advisory path intent alone is not treated as an exclusive edit lock.
- Owner-facing contract:
  [PR532 comment](https://github.com/cbattlegear/MeshCoreOne-Android/pull/532#issuecomment-6596949991).
  Direct session messaging is unavailable in this worker's tool catalog.

## Real mounted consumer and independent predicates

The two additive methods in
`android/ui-tests/src/test/kotlin/com/meshcoreone/android/uitests/OnboardingRegionPresetComposeTest.kt`
mount the existing production `OnboardingFlow`, Region/preset screens and
coordinators. The existing permission, ready-connection, catalog and held
`OnboardingPresetPort` fixture remains unchanged. This is not a mock coordinator,
radio operation, new persistence adapter or new original-family claim.

Both use the real `ServicesRegionCatalog`, with independently specified
`US` / `US-CA` / `los angeles` detected input. The displayed choices are
USA (`us-ca`) and WCMesh (SoCal) (`wcmesh`). A `CompletableDeferred` holds A's
apply before any completion outcome; no sleep or elapsed-time assumption is used.
The host retains its existing 200 percent font and API31 Robolectric configuration.

| Method | Required observations |
| --- | --- |
| `tappingAnotherPresetDuringAHeldApplyCannotChangeTheSelectionThatCompletesOnboarding` | Select and apply USA A, hold the actual port call, and twice send a pointer click to the displayed disabled B row. A must remain selected, B unselected, both rows and the A CTA disabled, the one A job active and its result incomplete. No extra apply, completion callback or completion flag write is allowed. Release A as Applied: exactly one completion/write and no B apply; the visible selection still names A. |
| `anotherPresetBecomesSelectableAfterAHeldApplyFailsAndNeedsItsOwnSuccessfulApply` | Repeat the held-A/B-click race, then return an explicit A failure. The error is visible and completion remains absent. Dismiss it, select B through its real row, and require zero implicit B applies. Only B's explicit CTA starts the second held call (`us-ca`, then `wcmesh`). Completion remains absent while held and occurs exactly once only after B returns Applied. |

These are Android-only regression predicates for the confirmed race and WP-305's
busy-selection guard, not a claim that the original Swift view prevents this race.
The source view supplies the real picker/apply/completion workflow, but its own
row action can change the selection while applying. The native repair intentionally
keeps the in-flight selection stable and restores selection after failure.

## Verification status

**SOURCE-ONLY / UNVERIFIED: no Android runtime pass is claimed.**
At inspected PR532 head `21b1a70629c8eb9baa492a8668b639ccd5b447d3`,
`PresetStepCoordinator.select` still updated unconditionally, as it does at base.
The new selected/disabled-state assertions are expected to reject those bytes;
the actual red result has not been executed. WP-305 then published
`51898f760d5752f2aa8c66d822bf61877f452d22`, inspected from its real PR head:
`select` now ignores input while applying and the actual preset row disables both
pointer and semantic selection while busy. Its feature unit test is
`selectionCannotChangeWhileRadioApplyIsSuspended`. This matches the mounted
regression's busy-selection contract, but those product bytes are not silently
copied into this WP-502 candidate. An integrated exact candidate must execute
the mounted tests before this regression can be credited.

Executed source/readiness commands:

| Command | Actual result |
| --- | --- |
| `python .\tools\android-port\controller\ci.py preflight --local --state $env:ANDROID_CI_STATE --output <fresh absolute temporary directory>` | Exit0; existing Windows installations passed the declared environment check. Not Gradle or Linux/device evidence. |
| `python .\tools\android-port\controller\validate.py` | Exit0, `result=valid`; traceability/manifest validation only. |
| `python .\tools\android-port\portmap.py` | Exit0; source mapping only, not behavioral equivalence. |
| `git diff --check` | Exit0. |

Source readback confirms all nine incumbent annotated methods and their
helpers/fixture are unchanged, with two new methods (eleven definitions total).
Definitions are not runner discovery. **Runtime discovered/run/pass counts:
NOT RUN.**

The current trusted status contains the existing hard `gradle-execution`
reservation owned by `local-check:f516a7e271f444dd9cb2efcc8b995a16`,
bound to commit `3c5297acdc083df21ec08afd7654aeaaa103ee59`.
No lease cleanup, release, direct Gradle invocation or alternative launcher
bypasses it. Publication may be explicitly unverified; an exact hosted result
belongs to its actual integrated head, never to an earlier product/test pair.

The existing App/scaffold lane already registers this class from `ui-tests`;
there is no new Gradle module, task, selector, workflow or runner. Actual
exact-commit hosted checks/logs remain authoritative; no second CI-result bundle
is committed here.

## Remaining boundaries

This closes no issue by itself. Full WP-502 remains unconfigured/incomplete.
No actual API31/37 device, targeted API33/34/36, approved golden, folding,
spoken TalkBack, geocoder, permission-dialog, radio or human acceptance follows
from these mounted controlled-port cases. The pre-existing untracked
`MessagePathOriginalFamiliesTest.kt` is preserved and excluded from this candidate.
