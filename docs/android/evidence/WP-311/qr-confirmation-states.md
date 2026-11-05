# WP-311 QR confirmation state binding

Bounded issue #410 follow-up. Source remains read-only at
`db14559b39d32322b06477c6ae676112f583db50`:
`MC1/Views/Contacts/ContactAddConfirmationContent.swift` exposes the target-specific
primary accessibility label and replaces its primary text with progress during import.
The existing Android `ContactAddConfirmation` already models these states.

The mounted `NodesListScreen` scan confirmation now binds that existing label to the
real primary button and renders indeterminate progress while `isAdding`. It retains
the existing idle Add/View titles, `isPrimaryEnabled`, scanner reset behavior,
import/lookup ownership and completion/navigation callbacks. No service, persistence,
translation or camera policy changes are made.

`ContactsImmediateAdmissionApi31ComposeTest` and
`ContactsImmediateAdmissionApi37ComposeTest` inherit three focused mounted controls:

- `mountedNewScanConfirmationBindsAddAndImportingState`: real Add -> decoded-text
  scanner -> review -> held import. Requires the idle enabled Add label, disabled
  Importing accessibility label and actual indeterminate progress on that primary
  control, one service write, no premature navigation, then the stored DTO.
- `mountedExistingScanConfirmationNamesTheStoredTargetAndOpensWithoutImport`:
  saved nickname differs from both saved name and QR claim. Requires View's
  accessibility label to identify the saved target, no progress, normal enablement,
  exact DTO navigation and no duplicate import.
- `mountedFailedScanImportRestoresTheEnabledAddLabelWithoutProgress`: holds and
  fails the real import, requires importing semantics while held and restores the
  enabled Add label without progress, storage mutation or success navigation.

These are deterministic native-graphics Robolectric mounted assertions at API31/37,
200% font and RTL. The denied-camera decoded-text seam is not physical QR-camera,
TalkBack, hardware-radio or durable Room evidence. Existing admission/lifetime
controls and source expectations are unchanged.

Execution must bind to the exact published successor's hosted named test results;
authored controls and prior #586 checks are not execution acceptance for this repair.
