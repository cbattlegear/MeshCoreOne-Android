# Unattributed completion identity repair

Source repair: `52747b7acef2b8abdf743f9100b1515befef8bbc`, parent
`6a89528a90bc1f29f0a9e562ddfd8e97e43d70aa`, on the preserved local
`wp305-main-durable-tip-prerequisite-41552` branch. Its underlying main is
`41552c85b659396111cee6b0e0f70c404ac5a8ee`; Swift reference remains
`db14559b39d32322b06477c6ae676112f583db50`.

The original extraction record describes source80a and evidencef552, not this
later successor. Source6a added two staged-ID/reopen controls. Source527 adds
three more methods and changes one production statement: no-ID completion
removes the previous finalized ID inside the same preference transaction that
sets completion/donation and removes pending staging. Exact-ID completion is
unchanged. This prevents completion(A), reset false, then legacy completion()
from making readback(A) true again.

The public legacy writer path in b2 OnboardingFlags.load calls the no-ID helper
for setBoolean(completed,true). This is a source-derived supported-path concern,
not an executed mounted schedule. No legacy adapter is imported into this
independent prerequisite, and no existing b2 result covers this repair.

New actual PreferenceStore controls:

- `unattributedCompletionCannotReviveAnEarlierFinalizedAttemptAfterResetAndReopen`:
  establishes A, writes false, closes/reopens, completes without attribution,
  checks true flag/donation but absent exact ID, then reopens and rejects A.
- `failedUnattributedCompletionPreservesTheExactPriorDecisionAndStagedReplacement`:
  establishes A, resets, stages B, forces real filesystem write failure, checks
  the entire preference snapshot is unchanged, closes/reopens, retries B and
  confirms only B remains finalized across another reopen.
- `backupCompletionFlagDoesNotExportOrRestoreAnExactAttemptIdentity`:
  uses the existing BackupPreferenceStore snapshot/restoreMissing path into a
  fresh real store, confirming completion flag transfer without attempt markers
  and no exact-A readback across reopen.

Backup implementation is unchanged: its allowlist contains the completion flag
but not the internal pending/finalized keys; restoreMissing does not overwrite
an existing reset-false value. Reset still writes false through the existing
writer queue, so exact-ID readback is false while reset. Existing staged-ID,
exact-ID finalization/readback and ordering tests/assertions are preserved.
No new reset authority, backup codec behavior or held producer changes are made.

Fresh trusted support-consumer/test admissions and wp-owned evidence admission
passed. The two test insertions shared an anchor with source6a; an initial
three-way cached application reported that insertion conflict. It was resolved
deterministically by retaining both independent blocks, without dropping or
changing any previous assertion. All 17 prior method declarations and assertion
lines remain in order; the class now declares 20 methods.
Exact-parent `git diff --check` passed. The active PR worktree was restored to
its unchanged b2 bytes after preserving the separate integration commit.

These are source/preservation results only: **no Kotlin compilation or test
execution occurred on527**, no hosted run was dispatched, and no local Gradle
ran under the retained f516 hold. No second CI-result bundle was retained.
The branch remains local/unpushed and requires exact-source review before any
hosted run. Prior b2 app1944/19 failures and owned249 relevant method passes
remain historical, not successor acceptance. Full integration awaits the
separately assigned reviewed token/seal foundation; #495 and original navigation,
presentation and hardware limitations remain held.
