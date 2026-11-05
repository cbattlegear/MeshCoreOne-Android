# WP-215 inactive rejected-input transient recovery

## Source and scope

This is a successor to the unchanged diagnostic source `25835ab2558d88a70e389ba4bee73f68cb2e5e6a`
and its producer-attributed verification composition `5168847e16d9b5dd1d294abf8317f4e10a7d09b8`.
Original manual full hosted run
[41981024969](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41981024969)
completed successfully in all six jobs. The original Linux job `127090960287`
proves 352 notification cases discovered/run/passed with zero failures/errors/skips,
including all eight WP-215 methods at both API 31 and 37 (16 actual executions).
Compiler test inputs equal the immutable 12 test blobs; remapped module inputs equal
the immutable 30 inputs. All 352 declaring-class/method pairs match original source
and the unit-test task executed without a cached/up-to-date suffix.

These results cover the original composition only, not this successor.
The original source ref and diagnostic ref are unchanged. No duplicate result
bundle was saved, hashed or uploaded; verification reads the original job log in memory.

Real producer API comes through merge `040f73f10` with parents `5168847e` and
`bb1d7eb99dcc03cc3c6a5185335dd6a9c7470e6e`. The producer ancestry contains corrected
schema6 storage `3768bcf`, closed publication implementation and parser tests.
The parser blob is `15190721b0d18dd32150a3febd8dac8d998f6a9d`; its remapped rejected-input
test blob is `0bbed4eb425644c32854a254f762e583bee92a86`.
No WP-401 source was recreated or relabeled as WP-215.

## Behavior

The internal, unwired `NotificationReplyAdmission` now preserves the producer's
identity rejection reason and explicit `READABLE`, `EMPTY`, `MISSING` or `MALFORMED`
input status. Only nonempty exact readable text supplied by the genuine parser
becomes `TransientNotRetained`: memory-only recovery content, not admission,
dispatch, destination, saved state or definitely-unsent evidence.

Rejected identities never invoke the atomic journal call. Empty, missing and
unreadable parcel data never become invented empty recovery content. Current valid
input still follows the existing exact-text/unchanged-journal-outcome contract.
Diagnostic string formatting redacts both transient and journal-attempt text.

Nothing calls the helper from the receiver or application graph. No UI contract
is invented; no automatic send, adoption, reassignment, deletion, draft overwrite,
PendingIntent publication or live intake is added. Schema6 and closed producer
ancestry are present for testing, not activated by this change.

## Admissions and checks

Actual trusted WP-215 admissions passed before edits:

- `NotificationReplyAdmission.kt`, `modify-related-consumer`: reviewed-support-consumer.
- `NotificationReplyParsedAdmissionTest.kt`, `add-or-update-related-test`: reviewed-support-test.
- This document, `create`: wp-owned.

The original eight test methods remain, with expanded stale/malformed assertions.
Three new API31/37 methods cover missing/empty rejection, hostile nested parcel data
and redacted outcome formatting. Expected WP-215 executions: 11 methods x 2 = 22.
This is not an actual discovery count until the exact successor executes.

Local Gradle is not started or contended for. Declared provenance validation and
diff checks are run separately. The successor needs its own unique-ref, unchanged
full hosted workflow and original-log compiler/source/named-method checks.
Physical device, radio, authenticated generation, shipped UI and Android display
behavior are not established by these tests.
