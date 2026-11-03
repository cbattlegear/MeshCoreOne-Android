# Issue #383: native BLE to process-graph regression candidate

Status at publication: **locally UNVERIFIED candidate**, not acceptance or an
issue-closure recommendation. The maintainer subsequently authorized a normal
scoped PR for hosted exact-candidate verification; its live job/log is
authoritative for reproducible results, not this publication-time note.

- Assigned slice: WP-303 production-composed regression for the remaining software
  integration on #383, not a reopening of #443's approved native BLE contract.
- Trusted base: `1e1ff70345f203b41f9a8b04836c2cc4640500c9`.
- Implementation commit: `9cbf32f423aff4c98f905cd3ad22f45e055267db`.
- Read-only Swift specification: `db14559b39d32322b06477c6ae676112f583db50`.
- Trusted manifest SHA-256:
  `11120a20d04408b3d21fac09180412df0490cabf1dafb8a3c98fe83e0fad31e4`.
- Trusted policy revision:
  `de026f7de86d91bf86ef26cc98075c2707e3f8e153f06d97ef159c401a7a792e`.
- Actual producer: session `b33644ad-3a9a-4a37-af40-9a50b34ced2a`, branch
  `cbattlegear-ble-integration`.

## Exercised composition, pending execution

`BleRecoveryIntegrationTest` constructs the production `AndroidGattFacade`,
`BleTransport`, `AndroidRuntimeLinkFactory`, `BleRuntimeLink`,
`ConnectionManager`, `AppContainer` and per-generation service graph over the
production Room persistence adapter. It does not install `HarnessLink` or a fake
manager-facing `MeshTransport`.

Robolectric API31 framework objects, a controlled `BluetoothGatt` shadow and a
protocol-speaking firmware peer supply platform callbacks and response bytes.
The existing `FirmwareRadio` is used only as that wire peer: its responses enter
the app through native characteristic notifications and the real Android adapter,
not directly into the manager/session. System-connected facts, pairing registry,
notification delivery and process preferences remain injected test roles.

The three proposed regressions assert:

1. A native disconnection tears down the graph without implicitly opening another
   GATT. An explicit manager health check opens a fresh GATT and handshake, retains
   the verified radio's Room partition, and publishes one new graph/token. Old
   native connected, notification/self-identity and authentication-loss callbacks
   cannot overwrite the current device or retire its graph.
2. User stop closes the native GATT, retires all graph jobs, preserves Room data
   and prevents a later health check or stale native callback from rearming.
3. An owned, system-connected idle target with the production-compatible
   always-false adopter remains disconnected after health checking with the
   existing `otherAppConnected` diagnostic. Explicit manual connect falls through
   to one fresh native GATT attempt. No successful adoption or Apple's restoration
   behavior is simulated.

These are proposed software witnesses until their actual methods execute
successfully. They do not prove physical/OEM behavior, Android's system-link probe,
CDM pairing/chooser behavior, DataStore restart durability, forget/reseed/reopen
races, periodic RSSI, bond-refresh consumers, or all remaining original families.
The unexecuted physical scenarios remain on #422. The accepted Android
no-implicit-rearm adaptation is unchanged.

## Admission and actual writers

Trusted `reserve.py admit` maps the new App test path to WP-303 `wp-owned/create`.
The shared historical WP-303 claim is revision 16, bound to session
`be9fa0fd-35b2-4e78-bffc-917adc273f29` and another base/worktree. A new `claim`
correctly refused that different binding; it was not treated as an edit lock.

The supported `reserve.py transfer` path/operation evaluation for each of the two
new Kotlin files and this evidence file returned `advisory: true`,
`file_edit_blocked: false`, with both the historical attribution and the actual
producer above. The old identity, authorization prose and ledger row were not
rewritten, inherited or released.

Actual #519 file overlap was inspected. This slice changes none of its App graph,
factory or shared harness files. The map producer session and the retained
WP-207 recovery producer were notified of the bounded new-file scope.

## Observed validation and execution blocker

With pinned native Windows Python `3.12.4`:

- `python tools\android-port\controller\validate.py`: passed, `result=valid`,
  1,866 tracked reference files, 64 WPs and 178 dependency edges.
- `python tools\android-port\portmap.py`: passed (exit 0).
- `git diff --cached --check` before implementation commit and
  `git diff --check` after it: passed.
- `python tools\android-port\local\check.py --commit 9cbf32f423aff4c98f905cd3ad22f45e055267db --affected-base 1e1ff70345f203b41f9a8b04836c2cc4640500c9 --distribution Ubuntu-22.04`:
  **BLOCKED before product execution**, exit 1,
  `Hard-lock collision for external resource gradle-execution`.

The retained owner is `local-check:f516a7e271f444dd9cb2efcc8b995a16`, bound to
candidate `3c5297acdc083df21ec08afd7654aeaaa103ee59`. Supported
`execution_recovery.py status` reports that the original launcher may still be
active and refuses recovery. PID reuse/absence is not descendant-cleanup
evidence. No owner was cleared, no runtime was deployed, and no direct Gradle or
alternate-ledger bypass was attempted.

**No Kotlin compilation, test discovery or test result is claimed.** Run the
supported affected check against the eventual exact candidate after legitimate
execution-owner recovery, or use normal exact-head hosted PR verification when a
PR is authorized. Do not equate the passing static checks with these three tests.
No duplicate CI-result bundle is retained.
