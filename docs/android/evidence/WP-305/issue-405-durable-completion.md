# WP-305 issue #405 durable completion follow-up

- **Reference:** `db14559b39d32322b06477c6ae676112f583db50`
- **Base:** `1e1ff70345f203b41f9a8b04836c2cc4640500c9` (`origin/main`)
- **Worktree branch:** `cbattlegear-wp-305-onboarding-recovery`
- **Scope:** persist onboarding completion before exposing the completed gate; atomically persist the
  completion flag and device-menu tip donation; retain retry after storage failure; constrain claims
  to the original eligible tabs.
- **Acceptance groups:** `APP-G06` donation/pending/show-once lifecycle and `APP-G08` actual
  completion preference/readback and resume boundary.
- **Changes:** onboarding flag store now has an awaited persistence operation; the app adapter
  acknowledges durable writes, atomically commits the tip event with first-run completion, and reports
  failures to the onboarding flow; no-device and already-applied-preset completion can be retried
  without falsely completing or repeating a radio change. Added focused tests for persistence
  acknowledgement/failure, restart/readback, eligibility, and retry.
- **Original families:** the ten `OnboardingStateTests` source IDs remain attributed in
  `OnboardingStateTests.kt`; the eleventh navigation-default source family remains in the existing
  WP-302-owned `OriginalNavigationCases.kt`. New tests exercise durable DataStore behavior without
  claiming additional original-case credit. No changes were made to the pinned source or source-case
  inventory.

## Verification

| Check | Result |
|---|---|
| `python .\tools\android-port\controller\validate.py` | Passed: trusted manifest/policy valid; 64 WPs and 1,866 tracked reference files. |
| `python .\tools\android-port\portmap.py` | Exit 0: traceability map generated; this is not behavior acceptance. |
| `git diff --check` | Passed on the changed worktree, including this evidence file. |
| Capability admission for WP-owned onboarding source/tests and reviewed app/core UI consumer/test paths | Passed for all changed source/test paths. |
| Android/Gradle unit tests, lint, build and hosted verification | Not run. The shared local Gradle execution reservation is held without cleanup proof; do not clear, bypass or contend for it. |

## Remaining acceptance and deviations

- App-level tip claim/display is not connected to the active WP-302 tab-change/navigation host. Persisted
  donation is available for an eligible host; no visible display or user-seen claim is asserted.
- The native tip presentation remains a separate product/UI-equivalence decision; this work does not
  decide it. The original 1.5-second pre-donation delay is not ported by this follow-up.
- API 31/37, runtime permission, radio, Wi-Fi/BLE, hardware and native Compose flow evidence remain
  unverified. These source changes do not complete the entire WP-305 acceptance set.
