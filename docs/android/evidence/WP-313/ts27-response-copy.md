# TS-27: remote CLI response-block copy

Issue #408, bounded WP-313 behavior/boundaries slice. Base:
`d933cc8d08f8d9cb19fab14236535065871ec642`.
Pinned Swift: `db14559b39d32322b06477c6ae676112f583db50`.

`MC1/Views/Tools/CLI/CLITerminalView.swift` supplies each remote CLI output line
with a localized Copy action invoking `onGetResponseBlock`. The node view model
returns the whole contiguous non-command block, strips MeshCore prefixes and
returns only the command text for command lines. The Kotlin holder and terminal
state already implement these rules; the App renderer previously exposed only
selection.

The App now uses an explicit Material Copy toolbar button with the existing
`toolsRxLogCopy` localization (the same source label), opening a line chooser
with 48dp copy targets. Each reading line also exposes a Copy accessibility
action. Controls live outside the selection container so labels do not enter
cross-row selections; reading rows retain their original compact height.
This makes the action available without long press. Clipboard writes use the existing App clipboard
adapter pattern. The output text retains selection and its original style and
status semantics. No protocol, confirmation or holder logic changes.

`AppRemoteNodeCliCopyTest` mounts the real App CLI and sends two confirmed
commands. It copies from the interior of a multiline response and requires the
entire block including its blank line but no adjacent command/response. Controls
require separate second-block output and prompt-free command copying, asserting
no additional sends. Cases are configured for Robolectric SDK 31 and 37.
An additional mounted test at 320dp width and 200% font uses a real mouse drag
across two rows and Ctrl+C, requiring exact clipboard text without action labels.
It also checks compact row height, the per-line accessibility Copy action and
keyboard activation of the 48dp chooser control. These cases are authored,
not claimed as executed while hosted CI is queued.
The chooser opens at the reading log's first visible item rather than restarting
at the oldest line. A 1,000-line mounted control scrolls the reading log near its
end and requires the recent response to be immediately visible in the chooser.
Large-font height is compared with equivalent Text-only content at the same
font scale, not a fixed pixel limit.

Typed admissions on the base: renderer `modify-related-consumer` uses
`reviewed-support-consumer`; separate mounted test `add-or-update-related-test`
uses `reviewed-support-test`. Evidence/deviation are WP-owned. The advisory claim
attempt reported an existing WP lease bound to a different source/base/manifest/
backend; historical bookkeeping was not rewritten. User-directed lifting of
historical agent-created reservation waits permits isolated implementation.

Windows documented `ci.py preflight --local --state $env:ANDROID_CI_STATE
--output <fresh-absolute-directory>` passed. An initial worktree-mode affected
check was refused because affected verification requires an immutable commit;
no tests ran in that attempt. Exact-commit verification is reported in the PR
and authoritative hosted run, not duplicated here as retained CI logs.
Physical clipboard/device/TalkBack acceptance is not claimed.
