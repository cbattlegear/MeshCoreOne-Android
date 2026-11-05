# WP-215 inactive issued-reply admission boundary

## Scope and ancestry

Repository: `cbattlegear/MeshCoreOne-Android`. Worktree branch:
`cbattlegear-notification-recovery-ingress`. Implementation base:
`c113f700c3adead4ec5e5bc2fd33cf8c5ef98d6a`, whose real parents are
`4705ae8be65d471d95c9bf16a4664a91f6ef2291` and the independently source-reviewed
producer `56e4ac5b6f1c62ac3b42e0ab9482a5974e213838`.
The former preserves `0456f5d` capacity ancestry, the verified #570 source
`af53ae7`, and main `cbd8ea8`. Reference Swift remains read-only at
`db14559b39d32322b06477c6ae676112f583db50`.

The four producer blobs introduced by the merge equal their exact `56e4` blobs.
The nonexported manifest and reject-only `UnactivatedIssuedReplyReceiver` are
unchanged. No producer/store implementation was recreated under WP-215.

## Implemented, deliberately unwired

`NotificationReplyAdmission` is an internal parser-to-journal boundary, not a
receiver registration, UI adapter, current-generation witness or send capability.
It is instantiated only by its tests. It structurally parses the input and calls
the supplied atomic `admitIssued` once only when parsing succeeds.

Parsing failure is explicit and never reaches storage or legacy decoding.
Successful parsing preserves the exact UTF-16 string, including whitespace,
NUL, combining marks and an unpaired surrogate, alongside the journal's unchanged
`StageResult`. `Staged`, `AlreadyStaged`, `ConflictRetained`, `Rejected` and
`Indeterminate` remain distinct. None is a send acknowledgement; rejection does
not prove unsent. An indeterminate outcome is not saved, retryable or durable
merely because this wrapper returns it. The returned string is in-memory only.
Cancellation propagates with no retry or invented storage result.

No automatic sending, route adoption, reassignment, deletion, draft write,
PendingIntent creation/publication or receiver activation was added. This helper
does not supply the missing authenticated/current-generation witness, aa4 UI or
combined publication/admission protocol.

## Admissions

Actual trusted `tools/android-port/local/reserve.py admit --wp WP-215` checks:

| Path suffix | Operation | Capability/result |
| --- | --- | --- |
| `messaging/NotificationReplyAdmission.kt` | `modify-related-consumer` | `reviewed-support-consumer`, passed |
| `messaging/NotificationReplyAdmissionTest.kt` | `add-or-update-related-test` | `reviewed-support-test`, passed |
| `docs/android/evidence/WP-215/inactive-issued-admission.md` | `create` | `wp-owned`, passed |

An initial test admission used the nonexistent operation `add-related-test` and
was refused before editing. The documented `add-or-update-related-test` operation
then passed; no policy or authorization was changed.

## Authored checks and execution status

`NotificationReplyAdmissionTest` declares eight methods, configured at API 31 and
37 (16 expected executions, not a discovered/executed count):

- Real enrollment/journal: exact text and duplicate identity; retained conflict
  and conflict replay; unknown token; empty/oversized input; missing storage.
- Real parser: malformed and stale identity never invoke admission.
- Controlled journal results: every typed rejection and an indeterminate result
  preserve both exact text and the original outcome; cancellation propagates
  with one attempt and no retry.

The real-store tests use a fixture-created witness and the existing registry-only
API. This is not schema6 protocol acceptance, live authentication, a real
PendingIntent delivery test, physical-device evidence or publication evidence.
Schema6 is a separate producer dependency and is not merged here.

Actual commands/results:

- `python tools\android-port\controller\ci.py preflight --local --state
  $env:ANDROID_CI_STATE --output <fresh absolute temporary directory>`:
  passed on native Windows with Python 3.12.4. This checks host inputs only and
  started no Gradle task.
- `git diff --check`: passed.
- `git rev-parse <56e4-or-HEAD>:<producer-path>` for the adapter, its two tests and
  its evidence document: all four pairs equal.
- `git diff 4705ae8 HEAD -- <manifest> <reject-only-receiver>`: empty.

Compilation and all 16 configured executions are **not run** on this candidate.
The retained local Gradle execution reservation was neither cleared nor
contended for. An exact-head hosted diagnostic must discover and execute the new
methods before acceptance; prior component runs do not cover this composition.
No duplicate CI-result bundle is retained.
