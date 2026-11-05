# WP-311 immediate contact admission

## Bounded contract and provenance

Concern: `NM-CONTACTS-IMMEDIATE-ADMISSION`, issue #410. Acceptance references:
`WP-311-behavior`, `WP-311-boundaries`, `WP-311-source-test-parity`, limited to this concern.
Swift pin remains `db14559b39d32322b06477c6ae676112f583db50`; it has not advanced.
The four assigned `MC1Tests/ViewModels/ContactsViewModelTests.swift` families are:

- `ContactsViewModelTests::loadContacts keeps an upserted contact until the fetch includes it()` / `single`
- `ContactsViewModelTests::upsert appends a contact that is not already in the list()` / `single`
- `ContactsViewModelTests::upsert replaces an existing contact with the same id()` / `single`
- `ContactsViewModelTests::upsert replaces an existing contact with the same public key and radio()` / `single`

Continuation base: `9b27f8b1aa6d6d37b99aa4d37646280d9917096b`.
Preserved source-only candidate: `c8fcab3585fa22c04bf02ae04b44d697bfa52890`, produced by
session `869123e3-46d4-474e-94aa-4bc2d026143b`. Its original branch, refs and unrelated dirty
files were not modified. The candidate was integrated with a three-way cherry-pick without
copying its obsolete hop-sort test implementation over main's newer mounted hop-sort tests.

Actual continuation session: `49b656d2-1cf3-434a-936d-24910c41cfda`.
Assignment: `assignment:fc9361f4-ec03-4739-994b-ddb9b4e4cec9:call_tqssyWRHTk4RQCDcE4jxcy2X`.
The coordinator confirmed no current semantic authoring or explicit hold on this slice.
The historical claim retains its original source/base/identity bindings. The supported claim
attempt refused the different binding; supported advisory transfer retained both producer
attributions, without releasing/recreating or impersonating the prior owner.
WP-owned paths are admitted by `wp-owned`; the existing App nodes adapter's stable connection
identity is admitted by `reviewed-support-consumer` / `modify-related-consumer`.

## Real consumer wiring

At the pinned source, `ContactsViewModel.upsert` appends/unmasks, replaces by UUID then radio/key,
and retains admissions until a fetch includes their identity. The sidebar upserts an incoming
detail-navigation contact. Manual Swift add dismisses after the service save; scan explicitly
fetches the stored DTO from the captured services before completing and navigating.

Android manual add now reads and returns the actual locally stored DTO through the existing
`NodesContactService.getContact` after saving. The Boolean `add()` entry remains compatible.
Missing or mismatched radio/key identities report the existing error, never a fabricated UUID.
Manual and scan completion call the mounted list's `upsert` before dismissal/navigation, without
starting another list reload. Scan reads the original captured store, not a replacement session.

The continuation adds a stable service-graph identity in the existing App adapter, because its
service/store wrappers are recreated by each provider call. Both flows check cancellation and
the current graph/radio after suspended operations. Scan reset invalidates a held completion.
The mounted list holder is keyed by radio so pending admissions cannot cross into another radio.
Already admitted rows remain visible during a held refresh, rather than behind its spinner.
That refresh resets only its captured advertisement service, never a replacement session's pipeline;
`ContactsNativeTest.held sync resets only its original sessions advert pipeline` discriminates this.

A fetch begun before an admission overlays the newer admitted DTO when its old result includes
the same UUID or radio/key; it does not restore the superseded row or clear retention.
Subsequent fetches keep the source identity-resolution policy. Sorting and durable storage are
unchanged. This is the explicitly documented lifetime adaptation, not a claim that the pinned
Swift implementation already protected every native session/replacement interleaving.

## Discriminating regression obligations

The four original unit families and their independent expectations remain, with additional
unmasking assertions. `ContactsViewModelTest.real add result survives an already held first
fetch until store includes its identity` uses the real add and list holders and a held query.

`AddAndScanNativeTest` covers returned stored identity, missing/mismatched identity, lookup
cancellation, an actual same-radio service replacement during a held manual lookup, an actual
radio/service/store replacement during a held scan add, and reset during a held scan lookup.
These assert no stale completion, success feedback, replacement-store read or fabricated row.
Independent review additionally identified a late-error race after Scan again. Failure catches now
validate the captured review/session before publishing; cleanup can clear only that captured review.
Four named `old add/lookup table-full/ordinary failure cannot fail or stop a newer scan import`
controls hold the old failing operation while a new import is actively suspended. They require
no stale error, announcement, feedback or flag reset, followed by exactly the new stored DTO's success.

The independent review also identified adjacent row-confirmation lifetime coupling to the new
radio-owned holder. Pending confirmations are keyed by both radio and service graph; retained row,
menu and confirmation callbacks validate that owner before state, service or navigation effects.
Four additional mounted controls (`mountedDelete/ChatConfirmationRetiresOnDifferentRadio` and
`mountedDelete/ChatConfirmationRetiresOnSameRadioSessionReplacement`) require the old confirmation
to retire, invoke its retained callback and old row navigation with zero effects on either service
or navigation, then exercise the fresh radio/session's real Sync, row and confirmation actions.
No WP-209 service or held work is modified.

An open row menu now belongs to its opening graph too. Each row's interaction subtree is keyed
by graph and radio, because retaining a Compose semantics action across recomposition can otherwise
invoke the updated callback on the surviving modifier node. Menu visibility and its opening/item
callbacks are owner-fenced as well. Four `mountedOpenDelete/ChatMenuRetiresOnDifferentRadio` /
`mountedOpenDelete/ChatMenuRetiresOnSameRadioSessionReplacement` controls leave A's menu open,
replace the graph, invoke retained A menu-button/item/row callbacks and require zero effects,
then require fresh B actions. Existing confirmation controls and their expectations are unchanged.

Sync is explicitly graph-owned within the radio-owned holder. Graph changes retire the active
operation's progress subscription and publication rights, without removing cached contacts or
pending admissions. Progress, success reloads, errors and cleanup are fenced to that operation;
old cleanup cannot finish B's sync. Three `ContactsNativeTest.held same-radio sync ...` controls
hold A across actual graph replacement, require B's own progress and active state while A succeeds,
emits progress or fails ordinarily, prohibit any A-driven read through B and then require B success.
`mountedSameRadioReplacementAllowsFreshSyncWhileRetainingAdmission` exercises the real add, Sync
and rebind effects while A is held and later fails. It requires fresh B Sync without losing the
stored DTO admitted by the actual manual-add consumer.

The mounted list lifecycle-collects radio/graph snapshots rather than depending on an unrelated
recomposition to discover a changed provider. The App adapter observes registry publication and
withdrawal directly alongside existing App state signals. The registry support edit only exposes
its existing serialized current-graph value as a read-only StateFlow; factory creation, retirement,
accounting and stale-withdrawal guards remain unchanged. Trusted `reviewed-support-consumer` and
`reviewed-support-test` admission covers this directly necessary adapter/registry support and the
additional observations in `FactoryPublicationIntegrationTest`. The existing failed-factory and
successful-successor expectations remain intact. Menu, confirmation and held-sync replacement
controls no longer mutate padding or any unrelated Compose state to force a rebind.

Sync captures the graph identity before reading either service provider and revalidates it after
both reads, before progress registration, advertisement flags or commands. A concurrent registry
replacement therefore cancels a mixed-generation capture rather than labelling A's service as B.
Deterministic controls swap the same-radio graph during contact-service acquisition, during
advertisement-service acquisition and while setting A's advertisement flag. They require no stale
command/subscription/publication, no B advertisement mutation by A, cleanup of A's flag when it was
set, retained admissions and an independent fresh B sync. Existing held-A/B controls remain intact.

If a held sync fetch resumes after graph replacement but before the screen observes it, cleanup
retires the mismatched operation before dropping its active identity. Success and ordinary-failure
fetch controls require loading and sync state to stop without waiting for the screen effect,
prohibit stale publication, preserve A-only advertisement cleanup and require fresh B success.

The Sync button's interaction subtree and coroutine scope are graph/radio-owned as well. Its
captured click validates that owner before starting work, and its retired semantics callback cannot
acquire B's click handler during recomposition. Mounted same-radio and different-radio controls
retain A's actual Sync callback, quietly rebind, require no A/B command or advertisement effects
from it and then require a fresh B click to sync and render B's fetched contact.

Connection snapshots include device and readiness facts, so the registry's early publication does
not suppress a later same-graph device update and leave Share My Contact disabled. The mounted
readiness control changes only connection facts; actual factory observation additionally requires
the ready device. If a graph changes before the first local load completes, the graph-keyed load
effect starts a replacement local read after retiring Sync, without starting a radio command.
Mounted held-initial-read controls cover both empty state and an actual manual admission, require
B's row before A's read is released, retain the admitted DTO and then require fresh B Sync.

The original control owner is passed into the holder rather than recaptured there. The holder
validates it before acquisition and around readiness evaluation; the not-ready error is owned by
the same captured graph. Mounted controls replace A with same-radio B during readiness handoff,
covering both ready and not-ready A, and require no A/B commands, flags or stale error before
fresh B success. A holder-entry control independently rejects an already-retired initiating owner
before any provider acquisition. Existing acquisition and held-operation controls are preserved.

Both `ContactsImmediateAdmissionApi31ComposeTest` and
`ContactsImmediateAdmissionApi37ComposeTest` inherit six mounted tests:

- `mountedManualAddAppendsBeforeListFetchIncludesContact`
- `mountedManualAddReplacesSameUuidBeforeListFetchIncludesContact`
- `mountedManualAddReplacesSameKeyAndRadioBeforeListFetchIncludesContact`
- `mountedScanAddAppendsBeforeListFetchIncludesContact`
- `mountedScanAddReplacesSameUuidBeforeListFetchIncludesContact`
- `mountedScanAddReplacesSameKeyAndRadioBeforeListFetchIncludesContact`

Each operates the actual list/dialog controls, completes Add while an older Sync fetch is held,
asserts immediate visibility, releases the old snapshot, checks exactly one correct row and
the full returned DTO at navigation, and verifies that completion did not launch another fetch.
It then switches the actual session radio while admission is still pending, requiring only
that radio's same-key row; switching back to the caught-up store yields exactly one saved row.
The scanned path operates the actual scanner's decoded-text fallback, review and confirm controls.
It is a mounted scan consumer test, not a physical-camera or QR-decoder execution claim.
Fake-service/store seams distinguish saved identity from a stale list snapshot; they are not Room
or durable persistence evidence.

## Verification boundary

Use the existing Android candidate CI workflow's affected-candidate selection and named JUnit
evidence. The exact commit's hosted job and log are authoritative; no duplicate reproducible CI
result bundle is retained here. The PR supplies exact head/tree, command results, discovery counts
and hosted links when available. Source presence alone is not an executed pass.

Windows readiness was initially refused for insufficient commit memory. Bounded JVM startup
options allowed the documented preflight to pass; this is readiness only, not product tests.
No local Gradle execution, runtime deployment, owner recovery or device execution was performed.
Existing local execution owners and the #495/#402 holds and separate #519 work remain untouched.
Physical radio, camera, TalkBack and controller/device acceptance remain separate later gates.
This repair does not close #410 or establish whole-WP completion.
