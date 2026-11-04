# WP-306: bounded unresolved-message read surface for #559

Status: **implemented source candidate; Android execution and clean integration blocked**.
This is not whole-WP acceptance, verified sender attribution, receive-path activation,
or bidirectional backup compatibility evidence.

## Immutable scope

- Repository: `cbattlegear/MeshCoreOne-Android`.
- Read-only Swift reference: `db14559b39d32322b06477c6ae676112f583db50`; not advanced.
- Worktree session: `72f92ce2-f5a8-4d56-bb05-a6563c52a39f`.
- Managed branch: `cbattlegear-unresolved-messages`.
- Isolated feature delta: `37810cf57177d8ab61df46e32ec37159243a7bed`, then
  `ba099b3162d713d09d8b8eaf12edfc025d7a1103`.
- Producer read API: `d97365337a1015bd1c74a3565b9b147f2606e290`,
  consumed locally as `8a8bae131`; producer provenance `ac17f2488b246423d6a92790f08e6c66c928fbc8`
  consumed as `9ad611adb`, with producer import repair `95bdabbe7` as `c5f957909`.
- Trusted main inspected: `a6cb5d84ee6853311159747293c07e285a8b70dc`.
  The local inherited producer ancestry is not a clean main-ready publication candidate.
  No feature branch was pushed and no PR was opened.

## Behavior and safety boundary

Chats has a plainly named **Unresolved messages** entry before search/filter results,
including when there are no contacts. It opens a read-only, radio-scoped native
Material screen with Back, refresh, explicit loading/empty/error/retry states and
bounded older-message paging. Current-radio changes discard the previous screen
selection and create a fresh read holder. An old suspended read cannot populate the
new radio holder.

The app adapter consumes only `UnprovedDirectMessageReading`; the UI never parses
storage markers. Rows distinguish `UNRESOLVED`, `LOCAL_CANDIDATE`, and `LEGACY_UNKNOWN`.
They display exact retained text, distinct occurrence identity, original wire time
when retained, and recorded receive time when evidence exists. Missing times are
explicitly unavailable, not reconstructed from legacy creation timestamps.
Same-content occurrences are not grouped or content-deduplicated. Offset paging
advances by stored rows consumed, not the number of unique already-visible rows.

No contact, radio proof, sender name, reply target or dispatch identity is created.
The projection does not expose a contact ID or sender prefix. This screen has no
composer, reply, adoption, deletion or mark-as-read action. Reading never constructs
a radio service graph. Existing producer receive registration remains unchanged
and inactive. Legacy adoption elsewhere and pinned iOS adoption can still discard
provenance; this read surface does not repair or approve either policy.

Shared themes, typography and 48dp touch targets are retained. The read body is
scrollable with native insets, selectable original text and a semantic heading.
All eighteen new copy keys have explicit resources in the twelve supported app
locales; existing generated resource/catalog files were not edited.

## Ownership and admission

Actual trusted `reserve.py admit` admitted WP-306 feature source/tests/docs as
`wp-owned`, App wiring as `reviewed-support-consumer` / `modify-related-consumer`,
and App integration/UI tests as `reviewed-support-test` /
`add-or-update-related-test`. The coordinator separately assigned bounded WP-005
resource work; actual WP-005 `create` admission succeeded for each locale resource
and the compiled-localization test.

The WP-306 and WP-005 `claim` attempts returned
`Existing WP lease is bound to different source/base/manifest/backend`.
No receipt was fabricated, no claim released/recreated, and no shared lock cleared.
This historical advisory mismatch did not prohibit admitted isolated authoring.

Producer session `174c2a9d-d3fa-489c-8caa-80b200272b20` retained the read
contract, DAO, MessageRepository, Room delegate and Room controls together.
The supported `reserve.py transfer` for exact
`android/core/data/src/main/kotlin/com/meshcoreone/android/core/data/repository/MessageRepository.kt`,
capability `reviewed-support-consumer`, operation `modify-related-consumer`,
returned advisory origin WP-306/current session/`37810cf57` and producer
WP-202/`174c...`/`d97365337` attributions with `file_edit_blocked: false`.
The consumer made no independent producer-hunk edits.

## Authored controls, not executed discovery

| Class | Authored methods | Assertions covered |
| --- | ---: | --- |
| `UnresolvedMessagesStateHolderTest` | 8 | No radio; no contacts and same-content distinct occurrences; bounded history; overlapping-page offsets; loading/error/retry; cancellation propagation; suspended old-radio read; foreign-radio rejection |
| `UnresolvedMessagesFeatureTest` | 4 | Real Room -> App adapter -> feature; two radio partitions; no contacts; historical ambiguity and local candidate uncertainty; unavailable historical times; file close/reopen; actual Android codec/export/import into independent Room |
| `UnresolvedMessagesComposeTest` | 5 | Reachable entry with no contacts and two same-content rows; loading/error/retry/empty; radio switching; unknown-time/candidate labels; expanded 200% font German and mixed-direction original text |
| `UnresolvedMessagesLocalizationTest` | 1 | All twelve AAPT-compiled locale variants, explicit uncertainty states and format arguments |

These are eighteen authored methods, **zero executed product-test methods in this
session**. The backup control is Android-to-Android only. No iOS runtime, device,
physical radio, TalkBack or deterministic native screenshot evidence was produced.

## Actual commands and results

Native Windows used pinned `C:\Python312\python.exe` / Python 3.12.4 and the
existing same-host `ANDROID_CI_STATE`; no tools were provisioned.

```powershell
python .\tools\android-port\controller\ci.py preflight --local --state $env:ANDROID_CI_STATE --output (Join-Path $env:TEMP ('MeshCoreOne-unresolved-'+[guid]::NewGuid().ToString('N')))
```

Result: **PASS**, exit 0. This verifies host inputs only.

```powershell
python .\tools\android-port\local\check.py --commit 37810cf57177d8ab61df46e32ec37159243a7bed --toolchain-state $env:ANDROID_CI_STATE
python .\tools\android-port\local\check.py --commit ba099b3162d713d09d8b8eaf12edfc025d7a1103 --affected-base a6cb5d84ee6853311159747293c07e285a8b70dc
```

Both results: **BLOCKED before Gradle**, exit 1:
`Installed execution runtime is old or mismatched: controller/authority.py`.
No direct Gradle bypass, runtime installation or execution-owner recovery was attempted.
An earlier working-tree/partial-stage adapter probe correctly refused its unsupported
shape; it was not a test execution.

```powershell
git diff --check
python .\tools\android-port\controller\validate.py
python .\tools\android-port\portmap.py
```

Results: **PASS**, exit 0. The validator reports 1,866 tracked reference files and
the unchanged 64-WP manifest. Portmap reports 2,049 mappings; this is traceability,
not feature parity.

The Impeccable `context.mjs --target android` was run once, then native Android,
established-surface and craft-floor guidance was followed. Its `detect.mjs --json`
against the three changed UI files returned `[]`, exit 0; a static detector does
not verify Compose rendering. A Python ElementTree check parsed all twelve
`unresolved_messages.xml` files, asserted the same eighteen nonempty keys in every
locale, matching `%1$s` placeholders and explicit localized titles; **PASS**.

## Remaining gates

1. Recover/deploy the reviewed execution runtime through its separately authorized
   owner; run real affected Android checks and resolve their actual findings.
2. Assemble on the clean, stabilized producer/main candidate rather than publishing
   inherited stale-base changes. The producer's hosted run
   [41888953429](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41888953429)
   does not include this UI and is not its execution evidence.
3. Keep production attribution/dispatch activation gated on honest provenance policy
   and actual Android-to-iOS adoption/backup interoperability. This view alone cannot
   close #559 or certify that any tentative local match is the RF sender.
