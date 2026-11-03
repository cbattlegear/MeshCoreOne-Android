# WP-308 outgoing send lifetime (#524)

## Candidate and ownership

Source pin: `db14559b39d32322b06477c6ae676112f583db50`.
Consumer start base: `1e1ff70345f203b41f9a8b04836c2cc4640500c9`.
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

Committed text is not restored or created again; its exact row is retained.
An uncertain creation is not treated as rollback: the original receipt and
allocated DTO/text are retained in the process adapter, the existing localized
failure is surfaced, and no automatic restore, enqueue or retry occurs.
This is process-memory custody, not a durable operation journal or an automatic
storage-repair UI. An unreadable database remains a storage failure, not success.

Creation and queue access are pinned to the initiating session; a replacement
session for the same radio cannot adopt this accepted send. Channel node-name
metadata is read from the matching current device at enqueue, retaining the
source's after-creation timing rather than freezing it at Send.
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
original pre-commit loss schedule. APIs31/37 are configured, not yet observed.
An additional feature test covers typing then clearing before creation fails.

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

**Compiler UNVERIFIED; new Android/JVM tests UNEXECUTED at authoring.**
The coordinator reports a retained shared Gradle execution owner without safe
cleanup proof. No competing Android/Gradle/JVM launch, lock/cache cleanup,
runtime deployment, emulator or hardware operation is performed.
Only documented source-light provenance, ownership and whitespace checks run
locally. Exact-candidate hosted CI must establish compilation, discovery,
method outcomes and existing-regression preservation; declarations/counts above
are not passing tests. No hosted dispatch, rerun or budget change is requested.
