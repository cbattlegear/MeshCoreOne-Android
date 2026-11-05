# WP-314 TS-11: mounted trace path editing controls

## Candidate and source

- Repository: `cbattlegear/MeshCoreOne-Android`
- Work package / acceptance: WP-314, TS-11, `WP-314-behavior`, `WP-314-boundaries`
- Candidate branch: `cbattlegear-trace-path-controls`
- Candidate base: `f93e2012049feb4d6be728f32bd661665d71161c`
- Source reference: `db14559b39d32322b06477c6ae676112f583db50`
- Verified source/test candidate: `2cb5dbbeabd97d94a1a32d9d8ab5f46a5c5d8e3f`
- Changed implementation paths:
  - `android/feature/tools/src/main/kotlin/com/meshcoreone/android/feature/tools/trace/TracePathUi.kt`
  - `android/feature/tools/src/test/kotlin/com/meshcoreone/android/feature/tools/trace/TraceCodeInputComposeTest.kt`

The pinned Swift trace list uses `onMove` and `onDelete`, and the trace screen presents a destructive
clear-path confirmation before calling `clearPath`. The Android UI now exposes accessible up/down
controls, edge-disabled states, existing removal, and a localized clear-path confirmation. Moves use
the state holder's original-list insertion-offset contract. All edits call the existing holder, so its
saved-path/result invalidation, error clearing, trace-hash override reset, and auto-return behavior
remain authoritative.

## Regression coverage added

`TraceCodeInputComposeTest` mounts the production `TracePathListContent` with the real
`TracePathStateHolder`. Its new interactions cover up/down ordering and boundary eligibility,
48 dp controls, existing hop removal, stale saved-path/result invalidation, clear confirmation and
cancellation, hash-override reset, and preservation of the auto-return setting.

## Checks and execution status

Earlier frozen diagnostic updates (not acceptance of the authority-boundary successor):
exact `bd0929a529eb7d660ccad24baa134bd43233e4a2`, full run
[42000049311](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/42000049311),
passed all six jobs. Original hosted discovery reports 747/747 tools and 2001/2001 app
cases passing, zero failures/errors/skips; candidate-compiled discovery includes mounted48,
execution34, batch-card4, saved-list13 and the actual AppContainer boundary control.
Exact `22ecb4e453a0122e4f36b7c025b52ae626427c0d`, run
[42001677036](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/42001677036),
failed `ChatMapPreviewTest.sharedLoadingFailureAndRetryReachBothCardsWithoutDuplicateRenders[31]`
at line412 (app2001, one failure). That held unrelated control is unchanged; no passing
aggregate or timeout-successor acceptance is inferred.

The next successor adds token-read-to-publication AppContainer/Room controls for an accepted
same-radio deletion and different-radio save. Accepted A operations retain immutable A keys;
callbacks first invoked after B reject. Save captures the radio before token acceptance.
A holder dispatch control also checks that accepted sender/radio/hash flags remain captured
when B publishes at the last pre-send token read. Source declarations and source reasoning
are not runtime reproduction: these additions require fresh exact-candidate execution and
review, and integration remains held pending that evidence.

Independent source review additionally identified device-B/token-A during actual ghost
identity reconciliation, the reverse of the prior publication controls. The new
`reconciledDeviceCannotAuthorizeActionsAgainstThePreviousGraph` AppContainer/Room control
holds real reconciliation persistence and requires rejection of save, dispatch, selection
and saved-list operations until graph and device identities agree. Bound guards compare
the same captured device/request radio with the immutable token radio, preserving null-token
adapters and previously accepted A writes. This is a new unverified successor, not evidence
transferred from r11. Its actual positive and unchanged-production controls are required;
the public PR and #402 hold remain unchanged.

A further source-review hypothesis concerns delayed effect execution adopting a live B token
for earlier A screen inputs. The origin successor captures the version-bound token before
effects/configuration, using an atomic AppState publication and a generation-specific lookup.
It preserves public reload bumps, loss clearing and same-graph rewiring idempotence; an old
asynchronous wire cannot associate itself with the replacement graph's version. Two real
AppContainer/Room effect-boundary controls and two mounted controls distinguish same- and
different-radio successors, stale saved-row rejection and valid fresh B actions. Their source
declarations are not passing evidence or a runtime reproduction. All older refs, failed runs,
the public PR head and the unrelated held map test remain intact.

Terminal diagnostics retained as failures: r11 `bd6c7a923`, run42004394041, app2003/1 failed
the accepted same-radio action assertion. Original-production control `1d795c238`, run42004542683,
app2003/2 and tools750/2 failed; its two explicitly discovered holder save/dispatch assertions
discriminate original production attribution, but the app acceptance gate was ambiguous.
R12 `04715ed7c`, run42005086992, app2004/2 failed the accepted-action assertions.
R12 negative `202a0733d`, run42005115369, app2004/2 failed the reconciliation save assertion
at line126 and the same-radio accepted action at line266. The former follows actual verified
A-token/B-device/Room identity and reproduces that save edge only; later send/selection
assertions were not reached. Absence of other reported failures is not a full positive pass.

The next coherent successor makes the action gate unambiguous (actual preparation refresh,
in-action arming, explicit captured-A assertion), fences same-token reload versions and adds
same-key replacement/fresh-version controls. It also checks retained hop/code/recent callbacks
against observable preference storage across same/different-radio graph replacement. These
new controls require their own positive/negative runtime evidence; no aggregate acceptance
is transferred from any earlier diagnostic.

R13 positive42006222795 (`f92208945`) and legacy-effect negative42006324874 (`c6582e2e1`)
are failed diagnostics, not origin acceptance. R14 positive42007671881 (`59cd22521`)
reports app2007/2 and tools753/3 failed; the delayed app controls failed their null-origin
assertion at line207, and the three mounted graph cases failed the shared button lookup at
line1618. Negative42007775666 (`a2032e1a3`) reports app2007/3 and tools753/4 failed, adding
the same-token reload app/mounted discriminators. Original assertions remain.

The successor explicitly models origin/version in mounted wrappers instead of delegating
to the fake's null-token defaults, checks origin against actual registry presence, and adds
no-watcher plus started-container withdrawal controls. Its accepted-operation gate is inside
the selected real A store create/delete boundary after complete admission, records the exact
operation/A radio, and then forwards into Room after B publishes. Saved holders use actual
generation/origin validation. These are new unverified changes requiring fresh execution;
failed earlier controls are not reclassified as product passes.

R15 exact positive `45ac1bb018976e040ccf9f33af8c299e450c28d5` runs42012983052 on
trusted main663d2029. Its distinct negative `285b1eabe47f86a73d5701ea20d9d55716efbaad`,
run42013081374, failed app2008/4 and tools753/3: both delayed app controls, same-token
reload, and independent `ChatMapPreviewTest.rendererExceptionIsARecoverableFallbackRatherThanAStuckSpinner[31]`;
the mounted failures are delayed same/different-radio and same-token reload. The negative
keeps identical assertions/APIs and the synchronous AppState withdrawal fix, but restores
legacy live-token UI configuration and removes version/recent guards. It is not a
byte-identical historic implementation. Positive runtime disposition remains separate.

Test-only successor `e8ec05f017c32926e739a6ffd6e1d2a174239b67`, run42015053100,
adds six real TracePathScreen mounted entrypoints for retained A hop edits/clear across
same/different-radio reconnects. It requires the preserved route and a fresh B trace,
rejects retained A actions/dialogs, and exercises fresh B controls and response handling.
The following source successor fences those callbacks by originating radio/version/token,
resets the dialog per connection, and freezes clear semantics callbacks. Neither this
additional schedule nor the full WP/device behavior is accepted by earlier results.

R15 positive42012983052 passed all six jobs. Original hosted-log inspection confirms
tools753/753 and app2008/2008, zero failed/errors/skips, with exact compiled-source
blobs and actual methods: mounted51, execution37, batch4, saved13, app8. This does
not accept later retained-action changes.

R16 test-only42015053100 failed tools759/6: visible clear dialogs remained after
same/different-radio reconnect (line613), retained confirmations canceled B's trace
(line619), and both hop methods stopped at the description helper (line1768).
Repaired42015431589 failed tools759/2, both hop methods at the same helper.
The hop fixture omitted the displayed hash from its name label (`Alpha (A1)` /
`Bravo (B2)`); neither failed hop method is claimed as a reached stale-edit finding.
The fresh successor preserves those assertions with exact rendered descriptions
and composes actual main5012's landed factory-publication fence. A distinct old-UI/
holder negative will use identical corrected tests and the landed lifecycle baseline.

R17 positive `5e6dbf1c65ac3073ada5417f57ff995f12d9f1c6`, run42017235223, passed
all six jobs. Original-log audit confirms tools759/759, app2010/2010, zero failed/
errors/skips and exact compiled blobs for all119 selected methods: mounted57,
execution37, batch4, saved13, app8. The distinct identical-test negative
`e5302704542e84acc38ce831d5ebaf1056478d91`, run42017275126, restores only the
two tools production files from45ac. It fails tools759/6: old dialogs remain at613;
retained clear confirmations and now-corrected retained hop actions cancel B at619,
both same/different-radio. The hop loop reaches its first action (up), so the negative
does not separately reproduce later down/remove branches; the positive executes all.
Negative app2010/1 is the untouched held ChatMapPreview method at412. No retry,
test removal or product workaround was applied to that independent failure.

The retained-action production delta received bounded independent source review,
not full-candidate acceptance. Final integration must bind current main and its own
exact check/review; these results neither transfer to a combined map candidate nor
accept physical radio, firmware, TalkBack or the rest of WP-314.

Full independent review of `897e56e` found additional unfenced list/code-entry callbacks
and live-device width reads after captured dispatch. The immutable `897e56e` diagnostics
remain separate, not clearance for these changes. Test-only successor
`6444103507bcd27e234f83a31c534bb8a0586c67`, run42022446364, adds eighteen separately
discovered same/different-radio retained-action cases plus three held-lookup width methods.
The repair successor gates all mutating list/input callbacks, retains saveable drafts,
rechecks run admission after launch, and decodes/saves using actual dispatch/result width.
The existing code-input path is admitted by trusted `wp-owned/modify` capability with the
same pinned-source/declared-verification invariants. No runtime outcome is inferred from
source review or any earlier green run.

R19 test-only42022446364 reports tools780/20 failed: eighteen retained-action cases at
the B-running assertion642, single dispatch width at891 and batch width at882.
Source42022705359 reports tools780/27 failed: ten fresh mounted assertions at681/688,
plus seventeen existing response-parser/listener controls. The mounted setup had loaded
a single-hop saved route, which the real codec correctly recognizes as auto-return;
the fresh assertions assumed it was off. The successor explicitly sets it off before
capturing A, preserving all fresh-route/toggle expectations.

The original response controls intentionally seed correlation with the existing
`setPendingTagForTesting` helper rather than dispatch a command. That helper now also
seeds the captured pending mode, matching the new correlation metadata. Production
retains only the scalar sent mode and never falls back to live width after a real
dispatch. No original assertion was dropped. These are fixture/metadata repairs to
be evaluated by distinct exact successor/negative runs, not retroactive passes.

Independent exact-c7 review retained the separate unmounted batch-save finding: old
completed/partial results remained saveable after reconnect because the editable route
and batch history intentionally survive retirement. Test-only `4a9cc1682078e4a04213e0a391b900985061d9d3`
adds four completed/partial same/different-radio controls. The following source successor
retains history while retiring its save eligibility, checks it in both derived state and
the save API, and restores eligibility only for a newly started batch. Fresh B persistence
and accepted-before-retirement semantics remain required. This is not a mounted Save
button claim or a transfer of the still-distinct r20 diagnostic results.

The existing compact/large-font review finding is addressed on the actual path row:
full-width details precede a wrapping action group. The added mounted case runs at
320dp with 200% font and checks real text layout, visible name/hash, 48dp controls,
nonoverlap, disabled boundaries and real removal. It does not substitute the earlier
840dp code-input test or claim physical accessibility verification. It shares the next
exact candidate with the batch-save eligibility fix; both require new runtime evidence.

| Check | Result |
|---|---|
| Pinned Windows preflight: `python .\\tools\\android-port\\controller\\ci.py preflight --local --state $env:ANDROID_CI_STATE --output $output` with a fresh absolute TEMP output and pinned Python 3.12.4 | PASS (exit 0; readiness only) |
| `git diff --check` | PASS on the source/test candidate |
| Full existing `android-ci.yml` manual workflow, run [41984214970](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41984214970), exact head `2cb5dbbeabd97d94a1a32d9d8ab5f46a5c5d8e3f` | PASS; all six workflow jobs succeeded |
| `:feature:tools:testDebugUnitTest` mounted/JVM tests | PASS: 719 discovered, 719 run, 719 passed, 0 failed/errors/skipped |
| Mounted test discovery in `TraceCodeInputComposeTest` | PASS: both `mountedPathControlsReorderWithinBoundariesAndPreserveRemovalInvalidation` and `mountedClearPathRequiresConfirmationAndResetsTheTraceHashOverride` are in the 39-method runner inventory; both are included in the passing module run |
| Swift codec oracle and SwiftData restore/export oracle | PASS |
| APK inspection and static alignment | PASS; this is static hosted evidence, not device execution |

The trusted WP-314 manifest has `verification.configured=false` and an empty
`commands` list. For an admitted bounded repair of a landed WP, the trusted
`docs/android/ci/README.md` lane permits the existing WP-003 preflight, scaffold,
and inspect commands; no manifest mutation, Gradle task, test selector, or new
runner was added. The earlier exact-candidate run `41982469986` discovered 719
tools tests but failed one mounted assertion because the expected accessible
label omitted the hash rendered by `TracePathHop.displayText`; that run remains
failed evidence and is not treated as a pass. The successor derives expected
labels from the real holder's current hop and passed the full existing workflow.
The successful run's actual method inventory contains both added mounted tests,
and its module JUnit report records all 719 cases passing with no skips/errors.
The mounted tests run in JVM Robolectric/Compose, not on an emulator or physical
device.

No device, firmware, radio reception, or physical accessibility evidence is claimed. This TS-11 UI
change does not alter radio or firmware behavior. This evidence covers only the TS-11 slice and does
not complete WP-314 or accept its other original-case families.

## Current-main integration successor

The preserved source/test candidate was composed without rewriting commits onto main
`1a8b406a67d44006f14ac043ac468b6adf07a9d7`, producing `e5645c5f1f06cbf94f7db8a75f09d35c1e8b96fd`.
Independent review of that exact head found a real running-edit lifetime defect:
the holder invalidated path bytes but retained its pending trace tag. No acceptance
receipt was issued for that candidate.

The successor retires active execution on path edits at the actual holder/runner boundary,
not only at rendered UI eligibility. It fences outstanding send/lookup/batch continuations
and saved-history refreshes so old responses, errors and timeouts cannot resurrect edited
presentation or overwrite a newer run. The explicit native safety adaptation is recorded
in `docs/android/deviations/WP-314.md`. Added assertions include held-send success/failure,
late-response and timeout controls, partial-history preservation, a newer execution,
suspended saved-path work, and stale mounted reorder/clear-confirmation callbacks.
Retained row callbacks additionally bind the captured hop UUID at its index, and history
refresh binds the full radio/path key and a separate path-selection revision, including
clear/reselect while preserving valid refreshes across new runs on the same selection.
Auto Return changes retire the old wire route; unchanged assignments remain no-ops.
Adjacent unmounted save APIs retain accepted durable history without reselecting a
cleared/changed route after held create/fetch.
Existing assertions are retained except the explicitly documented active-batch continuation
expectation adapted below. Successor compile, actual method discovery and execution are
pending; neither historical passing run nor source inventory proves this successor.

The original current-main diagnostic `41987819845` passed its exact `e564` software checks
but did not clear the independent lifecycle finding. Subsequent `34ed` diagnostic
`41988598761` failed the existing mounted batch-edit control because it expected the
continuing batch to automatically send the edited route. Its 725 tools cases included one
failure and mandatory aggregate reports were incomplete; no passing acceptance is inferred.
The control is updated to the explicit safety adaptation: retire on edit, retain/hide old
history, reject late responses and require a new explicit run of the changed route with
isolated statistics. Its original route/statistics discrimination remains asserted.
Batch mode changes now retire pending execution in both directions. History refresh also
orders actual snapshots by fetch ticket so a held older return cannot regress visible runs.
Exact `d444` run `41989167627` discovered 727 tools cases with two failures; exact `f080`
run `41989676506` discovered 731 with the same two failing methods. In addition to the batch
control, the mounted retained-row callback exposed Compose rebinding its Clickable delegate
after a keyed row moved. Explicit immutable semantics callbacks now preserve the rendered
hop/index guard. These failed runs remain authoritative for their own heads only.
Independent `9ac` review additionally identified a retained move-down action whose hop stayed
at the same index while its live boundary changed, and a stable-feature screen reconnect
that did not retire the holder. The successor checks the live boundary, makes unchanged
moves true no-ops, and retires the actual mounted radio/generation lifetime without discarding
the editable route or committed history. New mounted and virtual-time cases cover held
lookup/send, both radio-change and same-radio reconnect, old response/timeout/refresh, and
successful fresh traces. Active `9ac` diagnostic/ref remain immutable and do not verify these
successor changes.
The `c32` review found that its retained saved-dialog/list still crossed that connection
boundary. Its successor also scopes and retires the saved-list holder and dialog state,
fences retained selection/deletion and noncooperative completion, preserves accepted durable
mutations, and prevents an old list snapshot from restoring a committed deletion. Mounted
visible/held A-list controls preserve A data while a fresh B row can select, run and delete;
same-radio reconnect and direct-holder completion/error cases are included. These source
assertions remain unverified until the new exact-head hosted diagnostic completes.

Exact `28f5` run `41996072286` passed all six jobs, with actual 742 tools and 2000 app cases,
zero failures/errors/skips, complete mandatory suites and matching candidate-compiled source
blobs for 47 mounted, 33 execution, 4 batch-card and 10 saved-list methods. This does not
clear later saved-holder findings or verify later successors. Exact `8809` run `41997510663`
failed an unrelated app mounted `MountedInlineMalwareWarningTest` wrong-thread assertion
(2000 app cases, one failure); no passing aggregate acceptance or result transfer is claimed.

Trusted main `5640a3cf238a0c08f57f77fb78175964fdd6cdf6` was composed without rewriting
prior diagnostic refs. The directly necessary app trace adapter and production-wiring test
are admitted as `reviewed-support-consumer` / `modify-related-consumer` and
`reviewed-support-test` / `add-or-update-related-test`; bare WP-owned operations do not
authorize those app paths. The successor captures the live graph SessionToken to cover
same-radio registry publication before delayed Compose retirement. Its AppContainer/Room
held-persist reproduction and mounted/virtual-time controls require fresh execution and
independent review. Same-connection rename/delete outcome ordering is independently tested
across intervening loads without weakening retired-connection fencing.
