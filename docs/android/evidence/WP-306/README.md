# WP-306 evidence

External contribution, no lease. Local macOS runs only, not CI or acceptance receipts. Frozen source:
`db14559b39d32322b06477c6ae676112f583db50`. Base: `c8a16529` (origin/main). Scope: chat list logic and Compose
UI in `android/feature/chats`; see [WP-306.md](../../deviations/WP-306.md) for ownership, seams and adaptations.

## Commands

Environment: JDK 21, Android command-line SDK, private Gradle/Android homes, `--dependency-verification lenient`
(`-x :core:ui:verifySharedUiInputs -x :core:services:prepareContentInvocation`).

```
./gradlew :feature:chats:testDebugUnitTest :feature:chats:lintDebug :app:assembleDebug validateModuleGraph
python3.13 tools/android-port/portmap.py
python3.13 tools/android-port/controller/validate.py
```

## Observed results

- Full build exit 0 (incl. `:app:assembleDebug`, `validateModuleGraph`). `:core:l10n:verifyL10nConversion` also ran and passed.
- JUnit XML (`:feature:chats:testDebugUnitTest`, clean rerun after the last source change): 9 suites, 106 tests,
  0 failures, 0 errors, 0 skipped.
- `lintDebug`: 0 errors; 3 `LocalContextResourcesRead` warnings (WP-306.md D-12).
- `portmap.py` and `controller/validate.py` exit 0.
- Mutation checks (applied, observed failing, restored; tree clean afterwards): unread filter without the muted
  exclusion -> `unread filter excludes muted` fails; ascending sort -> 3 sort tests fail; `toggleMute` using the stale
  copy -> `toggleMute with stale unmuted snapshot unmutes live row` fails.

## Source id coverage

77 owned ids in `docs/android/test-cases.json` (7 test files). Per-case class, method, disposition and JUnit result are
in [`source-cases.json`](source-cases.json).

| Status | Count | Notes |
| --- | --- | --- |
| Ported and passing | 76 | 75 source-behavior, 1 platform-adaptation (source scan of the Compose list files) |
| Deferred | 1 | `ConversationListScrollPerfHarnessTests::hosted list programmatic scroll stays under hitch budget()`: needs a device frame-timing run (WP-306.md D-1) |
| Missing | 0 | |

Native (not source-bound) tests: 30 (`ChatListActionsTest` 25, `ChatTextMatchingTest` 2, 1 search-ignores-filter,
2 hashtag-router behaviors). Includes one real-time 7 s test of the delete timeout.

## Oracle

`oracle/wp306_strings.swift.txt` is the swiftc program that produced `oracle/wp306_strings.output.txt`
(`localizedCaseInsensitiveCompare` and `localizedStandardContains` probes); the results are pinned in `ChatTextMatchingTest`.

## Not covered

Compose rendering, gestures and TalkBack are not exercised (no compose-ui-test/Robolectric on the locked classpath and
no device). See WP-306.md "Not verifiable without a device".

## Issue #404 list-owned candidate (separate from historical results)

Base: `d7f35bbe938ca79d3bc8ab5510fb5cfa9cf8a808`. Reference remains
`db14559b39d32322b06477c6ae676112f583db50`. Candidate head is identified by Git and
the exact-commit check invocation; no self-referential head or duplicate CI result
bundle is persisted here. The historical `source-cases.json`, oracle files and
results above remain unchanged.

User-directed scope carves the shared heading and mailto dispatcher out to their
current owners. The following is a source/assertion map, **not passing execution
evidence**. New methods have not acquired acceptance merely by being written.

| Issue concern | Candidate assertion or retained obligation |
| --- | --- |
| `CHAT-LIST-FILTER-MATRIX-ORACLES` | `ChatListRepairTest`: exact joint 2x2 identities; two chats/two repeaters; all-blocked/all-repeater empty snapshots; complete exclusions and date-ordered favorite partitions. Six original families are bound in the methods. |
| `CHAT-DELETE-MASK-ORACLES` | `ChatListRepairTest`: stale and confirming room reload generations; held DTO restore identity/idempotence; each pending set plus union. Original masks and generations are asserted on `ChatListStateHolder`. |
| `CHAT-RELOAD-INTERLEAVE-ORACLES` | `ChatListRepairTest`: cancelled continuation-backed stale contact and room fetches resume after an exact eleven-row replacement snapshot. No original-store transaction acceptance is inferred from a fake datasource. |
| `CHAT-DELETE-API-POLICY` | Feature action enforces READY and original localized typed failure; all non-READY states and disconnect-before-write restoration have assertions. Raw App datasource, unread/badge sequencing and persisted outcomes remain a producer follow-up. |
| `CHAT-LOCALE-MATCHING` | `ChatTextMatchingTest`: retained independently recorded Unicode expected values now exercise actual sender matching/blocked exclusion and every selected search filter. Broader Foundation locale tailoring remains unestablished. |
| `CHAT-LINK-PERSISTENCE-ORACLE` | Existing DTO test unchanged; App-owned real existing-channel Germany store readback still required. Existing newly-joined-channel region tests are not the same predicate. |
| `CHAT-LINK-MAILTO-FALLBACK` | Carved out to current shared-dispatcher owner; no arbitrary scheme policy change here. |
| `CHAT-CONVERSATION-CHROME-CONSUMER` | Carved out to current shell owner; existing pure metadata tests are not mounted heading evidence. |
| `CHAT-LIST-PERFORMANCE-EVIDENCE` | Original native source guard unchanged. Real 40-row/26-step scroll-budget adaptation and mounted/device execution remain required; no new measurement or performance pass claimed. |

New execution must use the supported change-scoped lane:

```powershell
python tools\android-port\local\check.py --commit <candidate SHA> --affected-base d7f35bbe938ca79d3bc8ab5510fb5cfa9cf8a808
```

Any readiness refusal means product tests did not execute, not that tests failed.
Actual discovered/run/pass/fail/error/skip counts must come from the runner.
This candidate does not change golden expectations, source pin, policy or module
dependencies, or edit the timeline/map/shared graph.

### Actual candidate execution refusal

`python tools\android-port\local\check.py --commit 359666a98 --affected-base d7f35bbe938ca79d3bc8ab5510fb5cfa9cf8a808`
returned exit 1 before product execution:
`Installed execution runtime is old or mismatched: controller/execution_recovery.py`.
No Gradle build or JUnit discovery ran; zero product tests were executed and no
passing result is claimed. This is an execution-readiness gate, not a product test
failure or permission to install/deploy a runtime, bypass the launcher, clear an
owner or weaken verification. A separately authorized trusted runtime deployment
is required before rerunning the affected lane on the then-current exact candidate.
The subsequent documentation-only commit records this refusal; it is not a
verified replacement candidate.

### Actual existing-channel link persistence follow-up

Based on current trusted main `99919a0a8049c2da2bfab4c6cdc08bca787b3045`,
candidate head `02ad1af7a848ddaabc0c053d66eda8b6c8a0d3c8` adds
`ExistingChannelLinkPersistenceTest.existingChatLinkNavigatesStoredChannelWithoutApplyingLinkRegion`.
It routes a channel link with an attacker-supplied `region_scope` through the
actual `AppChatsFeature.list.links` adapter and `RoomPersistenceStore`, then
asserts that the selected route is the existing Germany-scoped channel, no
confirmation or radio channel-write is created, and a fresh Room read still
returns Germany. This supplements the feature-level fake-link assertion for
`CHAT-LINK-PERSISTENCE-ORACLE`.

Hosted Android candidate CI run `41389598668`, bound to base
`99919a0a8049c2da2bfab4c6cdc08bca787b3045` and head
`02ad1af7a848ddaabc0c053d66eda8b6c8a0d3c8`, passed scope classification,
controller/workflow policy, the Linux Android build and aggregate `android-ci`.
The hosted JUnit log records the method above with outcome `passed`. The
required checklist passed in run `41389598814`; the fresh Copilot review in
run `41389610630` approved this same head with no open inline findings.

No local Android/Gradle execution occurred; the prior local runner mismatch
remains unresolved. Swift codec and SwiftData restore/export oracle jobs were
skipped by workflow selection and are not claimed as verified.

### Foundation substring-boundary repair candidate

Based on trusted main
`d85ac9f8a5367615099bc215acc8d022c11eb75b`, the next WP-306 candidate updates
`ChatTextMatching.standardContains` and tests its actual
`Conversation.filtered` search consumer across every filter. It rejects
`straße`/`stras`, `ﬁle`/`f`, `한글`/`하`, and `ıstanbul`/`istanbul`, while
retaining full `Straße`/`strasse` folding, folded suffix matches, normal search,
fullwidth digit `１２`/`12`, and the Foundation-standard ZWJ emoji substring
`👨‍👩‍👧`/`👨`.

Independent expected values come from the captured outputs
`docs/android/evidence/WP-311/oracle/wp311_more.output.txt` and
`wp311_strings.output.txt`. The former records `std=true` and `noloc=false` for
the ZWJ example but does not stamp a locale; the latter records
`localizedStandardContains=true` for the digit example under `en_US`. These
are Foundation reference outputs, not Android execution results. No broader
locale-policy or device claim is made.

Hosted exact-candidate verification for head
`46a7303da5ce23cf5d349f1c8d0dcd4a2c74a556`, based on
`d85ac9f8a5367615099bc215acc8d022c11eb75b`, completed successfully in Android
candidate run `41396546339`. Its Linux Android build and aggregate `android-ci`
passed. Hosted JUnit evidence records 444 named `feature:chats` test cases as
passed, with no failed, errored or skipped cases. It positively discovers and passes
`ChatTextMatchingTest.localizedStandardContains oracle`,
`ChatTextMatchingTest.independent Unicode search probes retain search over all
filters`, and `ConversationFilteringTest.search preserves Foundation expansion
boundaries across every filter`. The fresh review on this head found no code
findings but noted that host-JVM tests do not establish Android runtime `\X`
segmentation parity; Android-device/platform validation remains open for human
review. Local Android/Gradle execution, device behavior, and broader locale
tailoring remain unverified. Swift codec/SwiftData oracle jobs were skipped by
workflow selection and are not claimed as verified.

The documentation-only follow-up head
`e772505ba3b2b9e60cd503f34e8273579b252d88` was checked in Android candidate
run `41398170548`. Both attempt 1 and the normal failed-job retry (attempt 2)
failed `:app:testDebugUnitTest` in the unrelated
`AndroidMalwareDomainFilterTest.publishes a new policy revision after a network
refresh[31]`, with `TimeoutCancellationException: Timed out waiting for 5000 ms`
at `AndroidMalwareDomainFilterTest.kt:73`. The earlier exact-code hosted run
`41396546339` passed. Retain both failed attempts and the earlier pass; this
does not establish the timeout's root cause or that it is flaky. No malware
sources were changed, and the failure is not attributed to WP-306. The PR stays
draft and unmerged pending clean required checks and human Android-runtime
validation.

After the coordinator released the diagnostic hold, the supported local
launcher was invoked for head
`0f694e831ceee3aa7c0b07610294a9202809a5fc` with affected base
`d85ac9f8a5367615099bc215acc8d022c11eb75b`. It refused before product
execution with `BLOCKED: Hard-lock collision for external resource
gradle-execution: gradle-execution held`. Read-only recovery status showed a
different live local-check owner and refused recovery. No product tests ran;
the owner was not interrupted or changed, and direct Gradle was not used.

### Capital sharp-S Foundation vector follow-up

The captured WP-311 `localizedStandardContains` output records U+1E9E capital
sharp-S (`ẞ`) searched for `ss` as true (`wp311_more.output.txt`). The matcher
previously folded U+1E9E through `Locale.ROOT` upper/lower conversion to `ß`,
so it did not match the two-character query. WP-306 now explicitly folds this
scalar to `ss`, with a direct helper assertion and a `Conversation.filtered`
assertion across every filter. Existing partial-expansion negatives, full
expansion positives, width, dotless-i, Hangul, ZWJ and ordinary search vectors
remain in both suites.

The exact current-main predecessor candidate
`d0b6ba6ffd81d6b6cb55f239c47a3f6d1bccb4ec` (base
`99479711bfdb7c621765f09d671068ca8b8318ce`) failed hosted candidate run
`41607197452` in `:app:mergeDebugResources` with `Java heap space`; this was
before the capital sharp-S test addition and is not attributed to WP-306
behavior. Its Swift codec oracle was skipped. The new source candidate's
exact-head hosted checks are required before claiming that vector's test
execution. No Android API31/37 runtime evidence is established by host CI.

### Instrumented production search fixture (source only)

The local follow-up integrates main
`c06e9950323ab09ca386fe730f783c6ebc8c3983` without changing the existing search
implementation, JVM cases or oracle outputs. It adds
`android/app/src/androidTest/kotlin/com/meshcoreone/android/app/chats/ConversationSearchParityTest.kt`
using the existing App AndroidJUnitRunner and dependencies. Trusted admission
for WP-306, operation `add-or-update-related-test`, returned
`reviewed-support-test`; this is not a WP-306 primary-owned App path.
The Kotlin-root guard restricts it to related test source and requires actual
native-method evidence for instrumentation acceptance.

`productionStandardContainsMatchesCapturedFoundationVectors` directly calls
the production helper for 21 captured vectors plus the empty-query negative.
`productionConversationFilteredMatchesCapturedVectorsAcrossEveryFilter`
passes the same 21 nonempty-query vectors through the production consumer for
all five filters. Its real ContactDTO, ChannelDTO and RemoteNodeSessionDTO rows
are muted with zero unread counts; positive search must still return all three
row types in their original order, including under UNREAD and category filters.
Negative vectors must return no rows. The test does not copy comparator logic
or mock the filtering consumer.

`productionConversationFilteredRetainsOnlyMatchingRowsInInputOrder` adds six
interleaved, uniquely identified production rows: three matching and three
nonmatching direct/channel/room rows. For each filter, `alpha` must return the
literal room/channel/direct subset in input order, `beacon` only the matching
channel, and `echo` only the otherwise nonmatching room. These 15 intended
list comparisons distinguish row-by-row selection from returning all rows
whenever any row matches. They do not derive expected subsets using the
matcher under test. All 21 original vectors and their assertions are retained.

The expected values retain the original WP-306 oracle and WP-311 `std` outputs,
including partial/full expansion, capital sharp-S, Hangul, fullwidth digits and
letters, dotless-i and ZWJ distinctions. No locale is inferred for `wp311_more`;
the separately captured width/dotless-i vectors are stamped `en_US`.

These are intended assertions, not test discovery or passing runtime results.
No compilation, hosted dispatch, local Gradle or instrumented execution occurred
for this local fixture. WP-503 still needs a trusted exact-source App-test
runner/family binding and actual API31/37 execution of all three methods. No
WP-503 runner/selector or build configuration was changed, and no device or
whole-WP acceptance is claimed.

## Explicit mailto tap fallback candidate

Prepared against main `83fc29b14f082ef1806f2d68516668128f63b1d4`, retaining
source pin `db14559b39d32322b06477c6ae676112f583db50`. This addresses only
`CHAT-LINK-MAILTO-FALLBACK` in #404. It is not whole-WP completion.

The actual path is explicit mailto body text -> `MessageLinkTokenizer` ->
`MessageBody` link action -> `MessageLinkDispatcher` -> `ChatLinkRouter`
declines -> `ChatExternalLinkOpener` -> Android `ACTION_SENDTO`. Both App
direct/channel and room link bindings use this opener. No arbitrary schemes
are admitted, and the shared HTTP(S)-only preview/network policy is unchanged.

Intended controls, not execution results:

- `ChatMailtoPolicyTest` retains recipients/query, rejects malformed/unsafe or
  unsupported links, preserves web-only detection/policy, and checks tokenizer
  punctuation/overlap and unchanged bare-email behavior.
- `ChatMailtoConsumerTest.mountedChatMailtoTapStartsOnlyTheNativeSendToAction`
  mounts the real App conversation and verifies exact action/data at the
  Android context boundary, with no explicit component/selector.
- `mountedChatMailtoWithoutHandlerShowsFailureInsteadOfCrashing` activates that
  same path with an unavailable handler and requires the visible error dialog.
- `unsafeSchemesNeverReachAnyExternalHandlerAndWebStillUsesItsOwnAction`
  exercises the actual router/dispatcher/native adapter with negative schemes
  and a positive HTTPS control.
- `mountedMultipleMailLinksHaveDistinctRecipientActions` requires two distinct
  resource-backed recipient labels and activates both through the mounted App
  conversation. Mail actions reuse the existing localized `Open Link: %1$s`
  label rather than exposing indistinguishable `Open Link` actions. Quoted-link,
  internal-apostrophe, square-bracket wrapper and balanced-subject controls
  preserve the intended URI. Only unmatched closing parentheses/brackets are
  removed; balanced subject punctuation remains part of the link.

The App test is configured for Robolectric API31/37, not real Android hardware.
No new local execution is claimed; the retained local execution-owner hold is
untouched. Canonical exact-head hosted CI and original discovery logs are the
authority for compilation and passing software results, not this source map.
The separate paused #417/#508 work and WP-503 native search work are untouched.
