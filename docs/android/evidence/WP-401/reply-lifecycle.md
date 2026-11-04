# WP-401: conservative durable REPLY custody prerequisite

Repository: `cbattlegear/MeshCoreOne-Android`; source reference remains
`db14559b39d32322b06477c6ae676112f583db50`.
This follows the verified, unchanged intake candidate
`22a38f188a559c85d2140e172a2754353b00a04d`
([hosted run](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41880913359)).
Current main `a6cb5d84e` was merged non-destructively at
`4f7cbdbb0e0fa6137f3ed4a50928c631a680524b`; the verified candidate remains an ancestor.
This is an isolated Android-only WP-401 prerequisite, not feature completion or activation.

## What exists

The same private SQLite journal supports an explicit, transactional v1-to-v2
`initializeLifecycle(maxTotalReceiptBytes)` migration. The existing per-record/intake byte
limits, exact text, routing payloads, record IDs, outgoing UUID reservations, conflicts and
no-eviction behavior are preserved. Missing stores are never created by this migration.
Custody receipts have a separate, caller-selected serialized-byte budget, including their
SHA-256 integrity bytes, not SQLite page/file overhead. No production budget or retention
policy is chosen. Insufficient migration or mutation budget rolls back, never drops text.
Migration replay requires the same budget; the persisted policy digest binds store identity.

Every retained row has exactly one custody receipt:

| State | Meaning and permitted transition |
| --- | --- |
| UNCLAIMED | Primary intake only; one atomic claim may establish custody |
| UNBOUND | Conflicting intake; never claimable or associated with a send |
| CLAIMED_POSSIBLE_SEND | Durable owner/claim/revision; never presumed unsent, even after restart |
| INDETERMINATE | Conservative uncertainty settlement; no release, resend or retirement |

`claim(identity, claimId, ownerId)` requires caller-retained UUIDs. The first atomic
UNCLAIMED transition returns `NewlyClaimed`; exact replay only returns `Observed`.
Other owners are `Fenced`, including after process/store recreation. A claim UUID cannot
be reused for another record. There is no expiry, takeover, reset or release. Claim IDs and
owner IDs are journal CAS tokens, **not authentication, RF authority or send permission**.
No dispatch callback is registered or invoked.

`settleIndeterminate(expectedReceipt, reason)` compares the exact identity, revision,
owner and claim. It records revision 2 and a typed uncertainty reason, retaining all original
text and identity evidence. Replaying the same settlement observes the same record; a
different reason or stale token cannot overwrite it. Recovery may conservatively quarantine
an abandoned claim using its retained receipt, but cannot stop an in-flight wire operation.
This is a custody receipt, **not an accepted/unsent radio receipt**.

Inventory includes the persisted custody receipt on primary and conflict entries.
The original target authority remains explicitly UNPROVED. Replaying stage returns the
same intake identity; it must not be interpreted as permission to send after a claim.

Every new/update mutation reloads and validates the retained row, custody inventory and
byte budget inside the same transaction before reporting success. Missing custody rows,
unknown states, changed store/claim/owner/revision bytes, policy changes, ignored writes,
partial writes and unsupported schemas fail closed. Receipt integrity hashes detect
accidental corruption; they do not authenticate an adversary with database write access.

Commit errors return `Indeterminate`, never a fresh claim. A cancellation-hidden successful
return is reconciled by reading/retrying the same identity and UUIDs; exact replay cannot
yield a second `NewlyClaimed`. The internal post-commit test observer can lose/cancel a
return **after a real SQLite commit**; it is not dispatch evidence or a production witness.

## Deliberately unavailable settlement

WP-208 and the action consumer both confirmed no durable dispatch-owner evidence API exists.
Merged `NotificationActionReceipt` is process-memory only. A transient protocol
`MessageWriteDispatch` observation, successful return, FAILED/PENDING row, UUID, missing ACK
or cancellation is not a durable accepted or definitely-unsent attestation.

There is therefore **no accepted, definitely-unsent, committed-send, release-to-retry or
notification-retirement API**. Those require admitted actual dispatch-owner records bound
to original post/action, outgoing ID, claim/attempt and trustworthy terminal evidence,
including crash/restart/cross-handle fencing controls. Current receipts cannot supply that.
The direct original-key/revision and firmware six-byte-prefix recipient ambiguity (#559),
channel origin/fence (#557/#550), recovery UX and retention prerequisites are unchanged.
No synthetic target identity, RF proof, automatic resend or runtime wiring is introduced.

## Admission and verification

Trusted `reserve.py admit` accepted WP-owned source modification, lifecycle test creation
and this evidence path. Automatic advisory claim bookkeeping again refused the existing
historical WP lease's different source/base/manifest/backend binding; it was not rebound,
released, or edited. No shared local execution lock or installed runtime was changed.

The documented native Windows readiness preflight passed with pinned Python 3.12.4,
existing same-host state and a fresh absolute output directory. Product execution uses
the existing exact-commit hosted workflow, not an execution-interlock bypass.

The real SQLite lifecycle tests cover explicit migration, exact raw text/conflicts,
independent concurrent owners and duplicate claims, stale CAS tokens, reopen/abandoned
custody, one-way uncertainty settlement and competing reasons, duplicate claim IDs,
ABORT/IGNORE mutation controls, committed-but-lost returns, post-commit cancellation,
receipt-capacity rollback, tampered/missing custody/policy and missing storage.
They run through the existing notification module Robolectric API31/37 mapping.
Authorship is not execution evidence. The exact candidate's hosted job/log is authoritative;
no duplicate CI result bundle is committed or retained.
