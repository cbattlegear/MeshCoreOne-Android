# TS-26: actual remote CLI output types

## Candidate provenance and boundary

This source-only isolation has the single parent
`99479711bfdb7c621765f09d671068ca8b8318ce` (main, independently matched to
`git ls-remote origin refs/heads/main` during construction). The Swift reference
remains `db14559b39d32322b06477c6ae676112f583db50`.

The renderer and mounted-test blobs are carried exactly from
`d6f0e0674698b381c56da5a223318e815a86821a`, which strengthens the original
TS-26 work in `1fad841cebb186c67a93e4c2e9fa98b711634e7c`. Those old commits
remain historical inputs, not candidate ancestors. Before TS-26, the two Kotlin
inputs on the retained branch were byte-identical to this main. The holder,
typed service error, error projection, test fixture and string accessors already
exist on main; none is changed.

Only the App renderer, its CLI regression, this evidence and the TS-26-only
deviation section are included. No TS-07/TS-39 changes, old build settings or
WP-312 map hunks are imported. Main's App `forkEvery = 0`, existing test
imports/setup/cleanup/map methods and all other paths remain unchanged.

## Gap and native adaptation

Pinned `MC1/Views/RemoteNodes/NodeCLIView.swift` delegates typed output to
`MC1/Views/Tools/CLI/CLITerminalView.swift`, which uses `line.type.color`.
The native shared holder already retains these types, but `AppRemoteNodeCli`
previously rendered every line as plain monospace.

The actual App renderer now maps command/success/error/response to the existing
Tools terminal's Material color roles. Success also uses bold and the existing
localized `remoteNodesSettingsSuccess` state description. Error also uses italic
and Android error semantics containing the unchanged text. Command and response
remain normal and have neither success nor error semantics. These non-color
distinctions are native accessibility adaptations, not Swift spoken-label
claims. Text remains unchanged and selectable. Holder ownership, confirmation,
reboot classification and request timeouts are untouched.

## Mounted coverage authored, not executed

The existing SDK31/37 `RemoteNodesComposeTest` gains
`nativeCliOutputTypesPreserveTextAndExposeSuccessErrorAndNeutralSemantics`.
It mounts the real App renderer, sends through its input/confirmation controls
and exercises the unchanged holder:

- `reboot` timeout: success text, localized success semantics, bold and primary.
- `set tx 22`: actual `RemoteNodeError.PermissionDenied`, exact error text,
  error semantics, italic and error color.
- `rebootnow`: exact `Unknown command` response, neutral semantics/style and
  on-surface color.
- All three command echoes: exact text, normal monospace, on-surface-variant
  color and neither error nor success semantics.

All six command/result lines must remain displayed with exact text and
non-overlapping top-to-bottom bounds in send/result order. Independent request
expectations remain 2s/10s/10s. The existing configuration-change case also
requires the success state description in the newly selected German locale.
Existing cases and assertions remain intact.

The typed permission fixture matches pinned
`MC1Tests/Views/RemoteNodes/NodeCLIViewModelTests.swift`; it exercises the actual
service error at the App sender boundary, not service permission enforcement.
These are source obligations for original families 468/469/477, not discovered
or passing test results.

## Admission, custody and coordination

The trusted public `tools/android-port/local/reserve.py` was staged byte-for-byte
from the exact main above. Each `admit --wp WP-313 --path <path> --operation
<operation>` returned exit 0 for:

- App renderer: `modify-related-consumer` / `reviewed-support-consumer`.
- Mounted test: `add-or-update-related-test` / `reviewed-support-test`.
- This evidence and WP-313 deviation: `modify` / `wp-owned`.

Generic `modify` returned exit 1 for both Kotlin paths. No wiring capability is
substituted. Support invariants require directly necessary scope, preserved
cases/contracts, serialization of genuine overlap, positive hosted discovery
and exact-candidate trusted review before merge. Those latter execution/review
requirements are not satisfied by this source-only construction.

The coordinator relayed WP-312 owner26eff's disposition for PR #519 at
`9007983f9a2b13199cdd4acbc53da896e9981001`: map/shared-harness changes remain
with that producer, with no CLI renderer/output-method edits or additional
unpublished/planned remote-node changes. This is scoped hunk information, not
blanket file clearance. No shared harness hunk is needed or changed here.
Combined integration execution is not established.

The candidate is constructed with a separate Git index and `commit-tree`, retained
under a local source-candidate ref; the managed branch, its index/worktree and
old ancestry are not reset, rebased or rewritten. `commit-tree` does not invoke
commit hooks; hook configuration is not changed. The required Copilot App
coauthor and session trailers are included. Exact candidate/parent/tree, path
blobs, complete patch and actual command records are retained in the session's
`wp313-ts26-current-main-source-001` handoff, outside this self-referential note.

## Verification limits

Source-only checks establish the exact four-path inventory, original Kotlin
blob equality, TS-26-only appended deviation, unchanged App build blob, clean
Git whitespace and unchanged managed-branch custody. Actual command arguments,
outputs, exits and times are retained in `commands.jsonl` alongside that handoff.
These are not Kotlin or native execution results.

The first candidate-index whitespace check rejected CRLF in the newly authored
Windows evidence input. Documentation blob inputs were normalized to LF before
repeating the check. The original failed command is retained; no Kotlin blob
or existing managed-worktree byte was changed for this correction.

No compilation, Kotlin/Gradle/SDK execution, test discovery, hosted run, push,
PR, physical device, TalkBack or whole-WP acceptance is claimed. Historical
source-light results on the old branch are not current-candidate evidence.
Readiness/native checks are not run under this source-only grant. The retained
Gradle execution owner and all publication, acceptance and paused-owner holds
remain unchanged; no reservation, execution-lock or hold operation is performed.
