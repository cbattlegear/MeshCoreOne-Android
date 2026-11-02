# WP-405 evidence

## Issue #417 mounted observation correction: new execution pending

Exact head `ce5bf82f41e7e69aab05bea73c0f03d33551c4ed` failed
[hosted run 41453274809](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41453274809)
against base `5878b5357dc9fe9986f002fe68763aca0bc35303`.
App discovered 1,881 cases: 1,873 passed, eight failed, zero errors/skips.
All four `heldRealJoin` variants failed on SDK 31 and 37, at lines
365/369/374: the expected committed/existing `pendingChannel.id` was null.
The Android job `125303663630` log is authoritative; no duplicate result
bundle is retained.

The actual mounted root consumes `pendingChannel` after opening its route.
The correction retains all 13 declared methods and their SDK families; it
does not convert the failure into a null-only success assertion. Bounded
observation of handoff consumption is paired with exact lasting selection,
top destination, preserved prior stack/other stacks, one selected-conversation
entry and its real editable composer. Existing confirmation identity,
submitting/resize, metadata, packet and once-per-consent checks remain.
This correction has not yet executed; the failed candidate is not accepted.
No local Android/Gradle or manual workflow dispatch is authorized or run.

The fixture still pauses after ACK and Room save, not before A's `0x20`
reply. It cannot cover the separately recorded pre-ACK two-consent slot
allocation race, late-ACK cancellation or service reconciliation.
See the same #417 finding below. No allocation or protocol fix is claimed.

Cold-start support assessment used exact trusted-main consumer operations
for `MainActivity`, `MeshCoreApplication`, `AppContainer`,
`ConnectionManagerAppPort` and `AppState`. The existing advisory attempt
`d76f0641-87d4-4218-92e9-3cdf3f972471` evolved revision 11 -> 12 -> 13
using production-only and then related-test-only calls. An initial mixed
operation call was refused atomically because the CLI cross-products paths
and operations; it did not deny the subsequently admitted exact pairs.
Historical binding and authorization remained unchanged. No ledger rewrite,
new identity, successor, #495 source/reservation change, or controller
acceptance occurred.

No startup source repair is claimed: cache initialization must not stand in
for the initial foreground teardown/provenance boundary, and awaiting the
existing foreground job can await a physical reconnect without conveying a
successful outcome. The exact unresolved methods and required producer split
are recorded in `docs/android/deviations/WP-405.md`. Required cold saved
contact/channel, initialized-empty, failure/retry, cancellation and
replacement regressions remain unexecuted; no full #398/#417 acceptance
follows.

## Issue #417 consumer-only follow-up: failed first execution

Finding: https://github.com/cbattlegear/MeshCoreOne-Android/issues/417#issuecomment-6578143740.
The original schedules were static/unexecuted. Source inspection at #508
`48474607c10e50a27933fb0cc0992905f2666e45` confirmed dual-slot clearing,
per-entry dialog mounting and holder identity tied to the unrelated slot.
Its exact current-base run `41442628576` passed against
`5878b5357dc9fe9986f002fe68763aca0bc35303`; that run does not execute this repair
or establish any new finding's outcome.

WP-302 explicitly reported no active competing channel-dialog/relocation
edit. Its #495 contact/room-auth and owner-metadata changes are retained as
future disjoint reconciliation requirements, not imported APIs. Trusted
`app-build-launcher` / `add-direct-support-wiring` and
`reviewed-support-test` / `add-or-update-related-test` admission covers the
exact consumer/test paths. Supported same-owner evolution reached revision
11, preserving historical authorization/binding; #495 and its reservation
remain frozen and untouched.

The original five `ChannelConfirmationConsumerTest` methods are retained.
Eight additional methods declare:

- Fresh identities for identical/different same-kind staging; old dismissal
  and successful completion cannot affect the replacement, for both kinds.
- Both cross-kind staging orders, cancellation and successful acknowledgement,
  retaining the other slot and completed navigation.
- Real-coordinator reentrant publication observing acknowledgement and
  navigation in one state before identical replacement.
- Real adaptive shell, existing conversation, compact/tiled resize in both
  directions, one accessible dialog, metadata and cancellation preserving
  the unseen other kind without a connection/write.
- Real-graph channel write held after the actual command and Room save:
  independent hashtag arrival keeps submitting disabled across resize and
  one consent produces exactly one command with original secret/scope.
- Identical and distinct channel replacements before that held join returns
  survive the old completion without obsolete navigation.
- Reverse independent channel arrival while a hashtag join is held, including
  temporary preemption/cancellation/reappearance with submitting still
  disabled, followed by preserved independent pending request and one write.

The held-write gate wraps the production persistence protocol after the
real firmware acknowledgement and Room save; it is not a replacement router,
callback-only action or invented radio result. The failed hosted execution
and unexecuted correction are distinguished above. No local Android/Gradle
is run. No physical-device or integrated
lifecycle claim follows. External/chat held-lookup session replacement and
confirmation/write session ownership remain blocked on WP-302's actual
accepted shared boundary after #506; no full #417 completion or merge follows
from this consumer-only candidate.

## Issue #504 related map-consumer synchronization follow-up

Exact selection candidate `d2d85f1fbb6557d8cafc71756a89c096c1277287`
compiled and executed in hosted run `41430849175`. All 29 selected
router/replacement/pending/existing-channel cases passed, but the complete App
suite failed: 1,865 completed, 1,863 passed, two failed, zero errors/skips.
The unchanged failures were the malware refresh preference observation timeout
and `ChatMapPreviewTest.rendererExceptionIsARecoverableFallbackRatherThanAStuckSpinner[31]`.
That is not a passing candidate.

The map test clicked Retry and immediately asserted a pin. The actual path is
`AppChatMapPreviews.retry` -> `MapSnapshotStore.request` -> a launched rendering
coroutine -> resolution flow -> Compose image state. Click completion does not
await rendering or its UI publication. The directly related test now uses the
same bounded 10-second real-pin semantics wait already used by neighboring
cases before retaining the original displayed-pin and exact two-request
assertions. The initial renderer exception, retry fallback and no-spinner
assertions remain unchanged; no map production, timeout threshold or repeated
attempt is added. This confirms a missing ordering barrier in source, not the
sole cause of the historical failure or an executed failing baseline.

Trusted admission is `reviewed-support-test` /
`add-or-update-related-test`; the same stored advisory owner evolved revision
7 to 8. WP-302 identified only a distinct unmerged edit in the neighboring
`sharedLoadingFailureAndRetryReachBothCardsWithoutDuplicateRenders` method,
which is unchanged here. New exact-head hosted results are pending; no local
Android/Gradle is run. The separate WP-204 producer #521 remains unmerged and
its candidate/evidence is not imported or attributed to this repair.

## Issue #504 selection follow-up: new execution pending

The previous exact candidate `ab5e5fabf7413ba54fa24fd56c25d5d6d4760eb6`
passed hosted run `41395949358` against actual main
`d85ac9f8a5367615099bc215acc8d022c11eb75b`: App 1,857 and chats 443
discovered/run/passed, zero failures/errors/skips. That run does not verify the
new selection repair below.

WP-302's static finding identified Settings -> suspended A -> rejected B
retaining A's obsolete temporary Chats selection. The App router now leaves
navigation untouched until a guarded target commit; external confirmations
select Chats in the same StateFlow update as staging, avoiding a reentrant
collector between selection and request publication. The environment carries
an explicit selection flag; existing navigation staging/clearing callers retain
their default behavior. No WP-302 lifecycle or lookup-ordering source is
imported. The deliberate pending-lookup timing
adaptation is documented in `docs/android/deviations/WP-405.md`.

Eight additional `ExternalRouteReplacementTest` methods exercise the actual
router/NavigationCoordinator: rejected replacement retains original selection
and every stack (including rail tool detail); user choices during lookup are
not rolled back; committed contact results survive rejected B; valid map,
channel, hashtag and identical-URI replacements commit their own selection and
metadata without damaging unrelated stacks. Additional methods cover the exact
rail-width boundary and Back, approved tool cleanup on a committed switch,
reentrant StateFlow submission during publication, and unchanged internal
routing/clearing semantics. All previous assertions are retained; only the fake
environment signature is updated to carry the atomic selection flag.
Definition inventory is now 15 replacement + 8 pending + 5 router =
28 targeted methods, not an execution count.

Existing owned router/test/deviation/evidence operations were admitted by
`tools/android-port/local/reserve.py admit` under `wp-owned`. The directly
necessary `NavigationCoordinator` staging-method wiring was admitted under
`app-build-launcher` / `add-direct-support-wiring`; the same stored advisory
owner evolved revision 6 to 7, preserving historical binding and authorization
context. WP-302 confirmed it is not actively editing those methods; its unmerged
stageContactLink/completion-CAS overlap must be reconciled in the actual order.
No ownership,
lease binding, source pin, policy or review verdict was rewritten. New Kotlin
execution remains unverified until the next normal PR-triggered scoped run.
No local Android/Gradle or manual workflow dispatch is used.

## Issue #504 candidate: validation pending

This follow-up is separate from merged PR #347. Its integrated base is
`99919a0a8049c2da2bfab4c6cdc08bca787b3045`; the Swift source remains
`db14559b39d32322b06477c6ae676112f583db50`. Existing same-owner advisory
reservation `d76f0641-87d4-4218-92e9-3cdf3f972471` evolved from revision 4
through 6 using supported `reserve.py evolve`, admitting staging/router
modification under `wp-owned`. Historical authorization/binding fields were
preserved, not rewritten or treated as a fresh live execution grant. WP-302
owns its frozen contact lookup ordering/session work; this candidate does not
change its API, navigation state or environment projection.

Source characterization: previously B replaced `pending` while A held
`inFlight`; B's `drain()` returned `Staged`. A's completion released its slot
but did not drain B, and A's post-lookup consumer could still publish an obsolete
confirmation/navigation result. Identical URI text with a distinct ID had the
same defect. This is source-derived characterization, not an executed failing
regression.

The candidate cancels/retires A at B submission, routes B independently and
atomically guards validity/cancellation with synchronous router writes.
`ExternalRouteReplacementTest` defines seven methods covering known/unknown
contact results, distinct/identical URI deliveries, duplicate IDs, deliberately
non-cooperative A, the window before B enters the router, actual navigation and
confirmation state, channel/hashtag paths, metadata and once-only delivery.
The synchronous-write method additionally asserts that both real contact
navigation and confirmation effects hold the exact submission-invalidation
monitor, rather than relying on an unprotected check/write sequence.
The post-commit cancellation method cancels the caller immediately after the
real contact navigation/confirmation write and requires cancellation to
propagate without leaving a replayable ID. It covers both queued and unconfined
dispatch with known and unknown contacts, retaining the original pre-commit
cancellation/retry case.
`PendingExternalRouteTest` retains all five original assertions and adds
failure/retry, A/B/C pre-router replacement and a forced queued-submission
caller-ownership regression. The latter queues B from the initial child's
cancellation handler while A holds the staging mutex, checking that staging
and routing claim cannot interleave across callers. Total targeted
definitions: 20 methods across these two classes and `AppDeepLinkRouterTest`;
this is an inventory, **not a discovered/passed test count**.

Light validation performed:

```text
python .\tools\android-port\controller\validate.py
PASS: 1,866 tracked reference files, 1,786 owned, 80 excluded, 64 WPs,
178 dependency edges; manifest SHA-256
11120a20d04408b3d21fac09180412df0490cabf1dafb8a3c98fe83e0fad31e4.
git --no-pager diff --check
PASS for the candidate edits at authoring.
```

Kotlin compilation, regression execution, Android preflight, lint and App suite
discovery are **NOT RUN**. Local Android/Gradle is prohibited during runner
recovery; no remote heavy slot or live execution binding is inferred from
advisory path admission. A focused issue #504 PR was subsequently authorized
for normal change-scoped hosted validation; its unexecuted Kotlin results
remain explicitly unverified until the actual hosted job reports them.
No direct Gradle/compiler bypass or manual full workflow dispatch is authorized.
The historical passing results below must not be attributed to this candidate.

## WP405-LINKS repair verification status

The APP-G03/APP-G04/APP-G07 repair batch passed supported affected local
verification on the assigned Linux host at candidate
`35c1064d56f6affc92d3e2c585db0ed732230dd8`, tree
`2e60fbb49f81dcc1cf4b9af8fd146828e399f73d`, against execution baseline
`3a292b6498e8dcef0c374022a93c8e30c68f5bd5`. This is local automated
evidence, not hosted, physical-device, native, signing or human acceptance.
The separate historical section below must not be reused as repair evidence.

The repair preserves private-secret hashtag warning/scope metadata through the
mounted confirmation and reuses the unchanged protocol grapheme helper with the
shared 30-byte region limit in external, chat and QR parsers. Original source
remains pinned to `db14559b39d32322b06477c6ae676112f583db50`.
The feature-parser capability was freshly admitted after trusted support landing
`f2f481d2e7c2a9029808943529c98c98ef879299`; the same owner's reservation
evolved to revision 4 before that edit.

The committed regression definitions require these real consumer surfaces:

| Test class | Defined repair methods | Required behavior |
| --- | --- | --- |
| `app.container.LinkConsumerIntegrationTest` | 4 | Actual retained Intent delivery/binding, original San Francisco focus, once-only consumption and external/chat confirmation metadata |
| `app.container.ChannelConfirmationConsumerTest` | 5 | Mounted warning/scope before consent, exact confirmed firmware/Room state, disconnected disabled action and cancellation |
| `app.container.ChannelRegionConsumerTest` | 4 | External/chat/QR parser-to-real-join and persisted region/secret, absent/empty values, padded ASCII and whole combining-grapheme boundaries |
| `app.container.MentionNavigationConsumerTest` | 2 | Actual mounted mention producer/action to contact destination and non-mention dispatch, preserving all original encoded-name vectors |
| `app.platform.ChatMapPreviewTest` | 2 coordinate methods | Independent mounted San Francisco/London thumbnail clicks replace prior focus with exact coordinates |

Fresh source-bound JUnit reports discovered and passed 4 link integration,
10 confirmation, 4 region and 4 mention cases. Both coordinate methods passed
on API 31 and 37 (4 cases); the complete map-preview class passed 22 cases.
These named results were read while the execution snapshot still had the exact
tested HEAD, before slot reuse. No required case failed, errored or skipped.
The QR assertion covers the production parser/join seam, not camera or device
evidence.

The actual affected plan selected `module:app` and `module:feature/chats`.
The assigned canonical runner executed:

```text
env -i HOME="$HOME" PATH=/ai_cache/meshcoreone/python/3.12.4/bin:/usr/bin:/bin LANG=C.UTF-8 PYTHONDONTWRITEBYTECODE=1 ANDROID_LOCAL_HOME=/ai_cache/meshcoreone/execution/single-shared ANDROID_LOCAL_PYTHON=/ai_cache/meshcoreone/python/3.12.4/bin/python3.12 ANDROID_PORT_LEDGER=/ai_cache/meshcoreone/control/android-port-shared.sqlite3 /ai_cache/meshcoreone/python/3.12.4/bin/python3.12 -B tools/android-port/local/check.py --commit 35c1064d56f6affc92d3e2c585db0ed732230dd8 --affected-base 3a292b6498e8dcef0c374022a93c8e30c68f5bd5 --parallel-jvm
```

The derived obligations are complete `:app:testDebugUnitTest` and
`:feature:chats:testDebugUnitTest` runners, App/chats Kotlin compilation and lint,
`:feature:chats:check`, `:app:compileDebugAndroidTestKotlin`,
`validateModuleGraph` and `runtimeDependencyInventory`. No arbitrary Gradle
filters, direct execution workaround or full-scaffold widening is requested.
Result: terminal exit 0, Python/preflight/scaffold passed, and the owned launcher
printed final `AFFECTED LOCAL CHECK PASSED` after current-base revalidation and
owned-child/resource cleanup. App discovered/ran/passed 1,571 tests; chats
discovered/ran/passed 392 tests. Both had 0 failures, 0 errors and 0 skipped.
Lint passed with 50 App and 4 chats warnings. Module graph validation covered
30 modules; runtime inventory recorded 195 inputs. App AndroidTest compilation
passed; APK assembly was not selected by this affected plan and is not claimed.

The first run at `3c64b9845` terminated with exit 1 before App discovery because
the mention test used the single-action API for a list-valued semantics property.
The tested candidate fixes that using the existing custom-action list/idle-thread
pattern without weakening navigation assertions. No hosted-first debugging,
test filtering, direct Gradle or execution-interlock bypass was used.

The exact-commit hosted job/log remains authoritative for hosted checks. No
second CI-result bundle is committed. This evidence update changes documentation
only after the tested candidate; it does not change tested production/test bytes.

## Historical original implementation evidence

## Scope and provenance

- Work package: `WP-405`
- Owner: `platform-integrations-engineer`
- Integrated base: `e4a4eb1e41ed4616c66271c519ce1637c6375c83`
- Frozen Swift source: `db14559b39d32322b06477c6ae676112f583db50`
- Frozen Swift tree: `8918fdc604341e6996a68c88f6bb1c02b9c2f87e`
- Manifest digest: `58f7ebd7f46bbe0636c71005f20776efe139a287279e4f4708b25ce6bfa3f892`
- All five owned production sources and four owned Swift test files were compared to the frozen revision without drift.

The 47 owned frozen Swift test cases are named by `@SourceCases` annotations in
the WP-405 JVM tests. They are exercised by 26 discovered tests covering
contact/channel/map parsing, malformed and encoded input, query injection,
identity handling, hashtag/mention support, flood-scope application, typed
confirmation staging, pending cold-start delivery, duplicate suppression, and
cancellation retry.

## Security boundary

The exported manifest entry points are limited to:

- `meshcore://map`
- `meshcore://contact/add`
- `meshcore://channel/add`
- `meshcoreone://hashtag/<name>`

Routing recognizes only those typed forms. It can navigate to an existing
object or stage a typed confirmation request. There is no generic command,
shell, radio administration, or mutation dispatch surface reachable from an
external URI. Internal mention URIs are not exported.

## Verification

Run after integrating `origin/main` at the base above, with the repository
credential-stripping launcher, Temurin 21.0.12.1, compile SDK 37.2,
build-tools 37.0.0, strict dependency verification, and explicit isolated
Gradle/Android caches:

```text
.\android\scaffold\invoke-gradle.ps1 -ConstrainedMemory -BuildHeap 1024m \
  -GradleArguments @(
    ':app:testDebugUnitTest',
    '--tests', 'com.meshcoreone.android.app.deeplinks.*',
    ':app:lintDebug',
    ':app:assembleDebug',
    'validateModuleGraph',
    '--dependency-verification', 'strict'
  )
```

Result: passed. Deep-link JVM discovery was 26 tests in 4 suites; 26 passed,
0 failed, 0 errors, 0 skipped. App lint produced 0 errors and 28 existing
warnings. `:app:assembleDebug` and `validateModuleGraph` passed.

```text
.\android\gradlew.bat :app:testDebugUnitTest \
  --tests com.meshcoreone.android.app.navigation.NavigationActivityTest \
  --dependency-verification strict --no-daemon
```

Result: passed; 4 activity/navigation lifecycle tests passed, 0 failed,
0 errors, 0 skipped. This verifies the WP-405 `MainActivity` integration
against the current WP-303 onboarding binding.

```text
python .\tools\android-port\portmap.py
```

Result: passed with exit code 0.

```text
python .\tools\android-port\controller\validate.py
```

Result: valid with nonzero inventory (1,866 tracked reference files, 65 work
packages, 185 dependency edges).

An exploratory unfiltered `:app:testDebugUnitTest` run at a constrained 768 MiB
heap discovered 387 tests before the Robolectric worker exhausted metaspace;
this was an environment-capacity failure, not a WP-405 assertion failure. The
declared targeted WP-405 run and required app lint/build/module-graph checks
above all passed at the final integrated base.

## Ownership adaptations

The manifest-declared deep-link source/test and WP-405 documentation paths are
used directly. `AndroidManifest.xml`, `MainActivity.kt`, and
`NavigationState.kt` are directly necessary app-launch/navigation support
edits admitted by the trusted app-build-launcher capability. The manifest edit
is a protected-path change and remains subject to the repository's normal
human review/merge protection.
