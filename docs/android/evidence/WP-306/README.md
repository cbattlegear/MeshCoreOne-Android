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
