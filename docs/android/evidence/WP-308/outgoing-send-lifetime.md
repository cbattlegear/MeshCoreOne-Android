# WP-308 outgoing send lifetime (#524)

## Recovery-list consumer preparation (not activated)

The immutable storage handoff
`2e62c5e28f46184c71915f93ba15829fa93f528e` is now composed locally
as `88baffb4732c4dd07774d151dd5b66c9abdf3b59`. Its independent
`ChannelDraftRecoveryPersisting` role takes radio ID and Long limit/offset,
returns original DTOs, and performs eligibility filtering before paging.
It is a per-call snapshot, not an original-display or dispatch witness.
The only textual merge conflict was a duplicate
`ChannelWriteCancellationDisposition` import in MessageService. A prior
merge-tree check isolated that conflict; deterministic `-Xours` retained
the identical existing import. The resulting tree matched the checked
merge tree exactly. No service logic was manually authored or removed.

The separately assigned R1 consumer fix includes known-old-occupant and
unbound outgoing recovery text in a separate list with explicit copy into a
selected composer, never automatic adoption or resend. Storage owns the
independent recovery query and exact eligibility rules; WP-308 does not
reimplement its SQL or change canonical contracts.

`RecoveredDraftStateHolder` prepares the feature-side bounded pagination and
selection/copy state. It preserves rows and selection on storage/copy errors,
propagates cancellation, serializes operations, reports failures, and clears
selection only after explicit copy completion. The port requires copy-time
exact-key text read and protection of an existing destination
draft. It has no sending or source-mutation operation.

Six unit controls cover ordered pagination, failed refresh, explicit selection
and destination, destination-draft conflict, cancelled copy, and a foreign-radio
page. These controls are authored, **not compiled or executed**. They do not
replace required actual App/Room A-to-B/reopen/summary/timeline/copy regressions.
`ChannelDraftRecoveryAdapter` binds that actual recovery query and exact-key
message read without a persistence fallback. It rejects foreign-radio, missing,
incoming and non-channel sources before invoking the destination owner. The
destination callback retains draft-conflict/navigation custody; the adapter has
no queue dependency and never saves or relabels the original.

`RecoveredDraftList` supplies a native scrolling, radio/message-keyed,
single-selection content surface with explicit destination selection and copy.
All content and controls scroll, including at 200% font. UI copy/status labels
are supplied as shared `UiText`, with no production English fallback or
assertion that a recovered row was definitely unsent.

Four App controls are authored, **not compiled or executed**: actual Room
A-to-B recovery and exact-current-text copy, missing/foreign sources, destination
conflict/deleted source, and mounted 200%-font 45-row paging/selection/destination/
explicit-copy into the actual App draft store with no radio or pending send.
The mounted control injects its destination owner; it does not demonstrate
production navigation custody or original-display capture.

There is no production route registration or scoped-read activation in this
preparation. Exact Android execution, App disk-reopen acceptance and the existing
enabled restart Send Again defect remain open, as does the original
display-capture/invalidation prerequisite. Producer execution is not consumer
acceptance, and the incoming-history UNBOUND_HISTORY policy is unchanged.

Local source checks: `python tools\android-port\portmap.py`,
`python tools\android-port\controller\validate.py`, and `git diff --check`
returned 0. No local Android runtime or retained execution owner was touched.
New holder and related-test paths were admitted by the actual reserve tool as
`wp-owned` and `reviewed-support-test`, respectively. The historical failed
identity claim below remains unchanged and is not represented as a new claim.

## Candidate and ownership

Source pin: `db14559b39d32322b06477c6ae676112f583db50`.
Consumer start base: `1e1ff70345f203b41f9a8b04836c2cc4640500c9`.
Current integration base: `796a872a3565a66c74f782b738a9ecb3c273fb25`,
including protected #531's protocol dispatch-proof prerequisite.
Session: `aa4b9d95-ca8d-4023-bb04-049e969e6d6c`, existing managed
`cbattlegear-literate-waffle` worktree. The historical advisory claim failed
`Existing WP lease is bound to different source/base/manifest/backend`;
no acquired claim, borrowed identity, ledger repair or successor authority is claimed.

The original WP-308 service-path admission refusal remains historical fact.
The separately assigned WP-208 producer subsequently supplied
`e98a1ecb633bdc1ac1b9be6d44f4483076fdbc7b`, integrated unchanged as `6fda3fd07`,
with WP-215 receipt prerequisite `bfd2418d9`, integrated unchanged as `6707ed391`.
These are coordinated producer commits, not WP-308 edits to MessageService.
Producer tests and claims are not consumer test results.
The revised producer `0d7c139228679d88c51ad38539819ef81d060cb5` is integrated
unchanged as `786d4c538`, with receipt prerequisite `1cb2b9ae` as `1b746ca48`.
It distinguishes settled protocol-proven unsent cancellation from uncertain
dispatch. This corrects the earlier e98 inline-acceptance classification; it
does not turn creation commitment into queue acceptance or wire delivery.

Fresh support admissions: AppChatsFeature and the new App test under
`app-build-launcher/add-direct-support-wiring`; AppChatsContent under
`app-build-launcher/update-launcher-or-composition`. Composer contracts,
holder/tests and per-WP evidence/deviations use `wp-owned`.
No policy, settings, localization, build graph, dependency or runtime changes.

## Behavior and native adaptation

Send captures its initiating radio-session identity before clearing the draft.
Accepted creation/enqueue work belongs to the existing process UI owner rather
than the mounted conversation. The initial operation starts undispatched so
screen disposal cannot leave an accepted but never-entered coroutine.
Closing the screen still cancels observers, cooldown and share/location work,
not the radio connection or accepted process-owned send.

The App adapter carries the producer's `PendingMessageCreation` context receipt
through real pending creation. Its identity/outcome survives a service save
followed by cancellation or failure before return. COMMITTED describes only row
creation: never queue acceptance, radio transmission or delivery.
Definitely-unsaved text is restored only if the local draft version and the
process draft revision remain unchanged. A replacement holder's newer typing,
including typing then explicitly clearing, wins.
Observable shared draft changes publish a recovered draft to an already reopened
empty composer; recovery does not require another reconnect or route recreation.
If original-slot lookup also fails during definitely-unsaved recovery, the App
retains the text, original target/generation and draft revision in process memory.
Subsequent real App invalidations retry only local recovery after storage becomes
readable. New typing or slot replacement invalidates that recovery; it never
sends, enqueues or restores the text into a replacement channel. Failed reads are
reported rather than silently interpreted as an absent channel.

Committed text is not restored or created again; its exact row is retained.
Recovered committed creation failures explicitly publish the exact saved message
to the mounted timeline, rather than only publishing an unknown-ID status change.
Direct recovery recomputes contact list metadata from persisted messages so a
first-message failure remains reachable from Chats. Transient local queue-read
or status-write failures retain the committed ID and original slot identity.
Real App invalidations retry only local status recovery and row publication,
serialized against the initial attempt; they never create or enqueue a message.
Newer live whitespace/newline editor content is preserved even though the
shared DraftStore deliberately does not persist whitespace-only drafts.
An uncertain creation is not treated as rollback: the original receipt and
allocated DTO/text are retained in the process adapter, the existing localized
failure is surfaced, and no automatic creation, enqueue or wire retry occurs.
Real App invalidations (including normal reconnect/service changes) now read
these receipts: WRITING waits; a settled unknown outcome is read by its exact
initiating radio/UUID. Matching immutable creation identity/content enters local
committed-row recovery; later read/seen/status fields need not equal the original
allocation snapshot and are preserved.
Proven absence permits only version/slot-fenced draft restoration. Failed or
mismatched reads remain retained and explicitly reported, never interpreted as
absence. Reconciliation uses persistence, not a replacement session's send graph.
Mounted cases cover an existing row, exact absence, newer drafts and same-radio
session replacement after an initially unreadable reconciliation, including a
row marked read before storage recovery.
This is process-memory custody, not a durable operation journal or an automatic
storage-repair UI. An unreadable database remains a storage failure, not success.

Creation and queue access are pinned to the initiating session; a replacement
session for the same radio cannot adopt this accepted send. Channel node-name
metadata is read from the matching current device at enqueue, retaining the
source's after-creation timing rather than freezing it at Send.
The existing AppState channel-slot generation fences old-slot draft recovery.
Channel creation and App queue entry also check the original slot ID/secret and
generation; held creation followed by same-session replacement cannot enqueue
old text. This App guard is not an atomic queue/firmware slot lease: a slot change
after queue entry while service-owned persistence/drain is suspended still needs
coordination with the WP-405/channel and queue producer. That lower-level review
finding is explicitly unresolved, not claimed fixed by a preflight check.
The same prerequisite includes a held pre-commit save that succeeds after slot
replacement: its old text must not become an ordinary retryable row under the
replacement key. The captured App send port now checks before its actual
ChannelMessageReceived/list publication, not only after that event. Immediate
committed recovery checks the captured slot before failing or publishing a row.
An invalidated committed row stays PENDING, with its exact ID/text and original
target/generation retained; same-process explicit Retry rejects that known
invalidated intent instead of making it ordinarily retryable.

The additional mounted schedule holds save before commit, replaces the slot
through the actual ChannelService, lets the save succeed, and invokes the real
timeline Retry adapter. A positive control retries a committed failure under the
unchanged original occupant. These consumer regressions do not prove persistent
origin or wire authority: raw index-only timeline/history queries can still find
the row independently of publication, and process-local custody does not survive
restart. Durable origin-aware query/retry disposition and the actual queue/slot
fence remain required; no App preflight or green aggregate claims them.
The restart regression extends that held successful-save schedule with a named
on-disk Room database. It closes the original App container, store, database and
store owner, reopens the same database with fresh owners, and reconnects a new
process container whose firmware reports replacement B. Exact-key recovery must
preserve the old DTO/text. The actual App Send Again adapter must then return the
existing typed refusal without a message wire call or pending submission. This
is a required safety assertion, not an expected-failure/skip annotation: the
current index-only/process-local implementation is expected to expose the known
restart defect until the producer and consumer integration is implemented.
No replacement-history filtering, unbound-history presentation or new UI policy
is selected by this regression. Exact hosted execution remains authoritative.
On creation cancellation, only bounded local recovery completes noncancellably;
the original cancellation propagates. An interrupted enqueue is not blindly
failed because the queue may still own durable acceptance. A generic enqueue
failure does not mark a row failed when its durable queue row already exists.

Frozen source correspondence remains ChatInputBar / ChatConversationInputBar
and ChatViewModel+Messages, +Channels and +SendQueues. This is a native ownership
repair, not a change to original text, UTF-8 limits, queue attempts, ACK semantics,
wire serialization or source-family accounting.

## Mounted regression candidate

`ComposerNavigationLifetimeTest` mounts production AppChatConversationScreen,
ChatViewModel, ComposerRoute and the production App composer factory over
ContainerHarness, real Room and the real session/send-queue graph. Fake firmware
returns deterministic terminal rejections; no device result is inferred.

The direct/channel matrix covers disposal immediately after acceptance, a gate
inside saveMessage before Room commit, a gate after actual Room commit but before
save returns, and gates before/after real durable pending-send insertion.
It checks exact row identity/text/radio, draft versions, one creation/one queue
submission, one terminal wire attempt, and no leftover terminal queue row.
Additional schedules replace the initiating same-radio session, preserve newer
drafts/explicit clears, restore definitely-unsaved text, throw after committed
creation, fail reconciliation reads, and throw after durable queue insertion
before return (then reconnect to drain the one retained operation).
Two controls retain the former screen-owned holder wiring to demonstrate the
original pre-commit loss schedule. APIs31/37 are configured; exact-head mounted
discovery and outcomes are recorded in the PR's authoritative hosted references.
An additional feature test covers typing then clearing before creation fails.
Mounted retained-route cases check that committed-before-return failures actually
appear as retryable rows without reopening. Same-session slot-replacement cases
check unsaved draft suppression and committed old-text enqueue rejection.
Unreadable-channel cases exercise failure both before creation and during
recovery, then restore readability and invalidate the App, checking original-slot
recovery, newer typing preservation and replacement-slot rejection.

## Coordination and preservation

WP-302 owns #495's distinct stale pending-list-handoff finding:
projected A must not navigate after notification B becomes authoritative.
The only AppChatsContent consumer hunk here is private AppChatConversation's
sender decoration/holder construction at original base lines219-243.
No ChatsEntry/list effect, openChat, explicit user selection, pending handoff
acknowledgement or NavigationCoordinator changes. #495 should land first;
later integration must reconcile these disjoint hunks, not replace its file.
WP-405 external-route/channel-confirmation changes are outside this hunk.

The earlier untracked TimelineChromeGeometryComposeTest.kt and chrome-geometry.md
are preserved unchanged and excluded from this candidate. Historical merged
PR328 evidence remains separate.

## Verification

The initial draft was compiler-unverified and its tests unexecuted.
Historical head `5fb559fc5` subsequently passed automatic hosted run41589333033,
including the mounted consumer matrix and the unchanged original failed-send
two-refresh assertion. That result predates the current-base/producer integration
and does not verify its new bytes. Exact-candidate results remain in PR530 and
the hosted logs, not a second retained CI-result bundle.

The coordinator reports a retained shared Gradle execution owner without safe
cleanup proof. No competing Android/Gradle/JVM launch, lock/cache cleanup,
runtime deployment, emulator or hardware operation is performed.
Only documented source-light provenance, ownership and whitespace checks run
locally. Exact-candidate hosted CI must establish compilation, discovery,
method outcomes and existing-regression preservation for each new candidate.
No hosted dispatch, rerun or budget change is requested.
