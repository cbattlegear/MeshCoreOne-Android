# Issue #380 composed ChannelService recovery

- Repository: `cbattlegear/MeshCoreOne-Android`; WP-209 / services-porter, with
  capability-admitted WP-207 lifecycle and App composition support.
- Frozen source: `db14559b39d32322b06477c6ae676112f583db50`.
- Implementation base: `b1b5155d5f9a6a6934ce0cfb260e6b78a73d57f6`.
- Actual session/attempt: `05c48790-1100-4158-b981-c97a525a6dcb`.
- Managed branch: `cbattlegear-wp-209-composed-session-recovery`.
- Supported successor admission archives terminal PR #455's reservation without
  adopting its identity, releasing/recreating it or changing its authorization.
  The new reservation binds the current manifest/policy and actual worktree.

## Behavioral boundary

The landed #436 protocol guard remains unchanged. This repair implements the
approved WP-107 G01/CM-01 downstream obligation: partial pipeline results persist
before correlation recovery tears down the transport, and a fresh graph performs
a new appStart handshake and channel read. A missing slot is never fabricated as
empty, and a scripted same-link retry is not recovery evidence.

The real App integration suite holds the first Room commit to prove the runtime
cannot close early, inspects old/new transport command traces, checks persisted
partial/missing rows, then checks the replacement graph and recovered row. It
also exercises old callbacks/frames, caller cancellation, manual disconnect and
fresh-connect failure. The firmware/transport is a test role; App services,
controller, protocol session and Room persistence are production implementations.

## Execution status

No product test pass is claimed yet. Local readiness has no configured native
Windows `ANDROID_CI_STATE`; the supported `python tools\android-port\local\check.py
--worktree` refused because the installed verifier does not support working-tree
overlays. The canonical ledger also retains another execution owner's
`gradle-execution` receipt; it has not been adopted, released or bypassed.

Feature publication is an explicitly unverified candidate until exact-head
hosted checks execute. Their authoritative job result/log belongs on the PR;
no duplicate CI-result bundle is retained here. No hardware, iOS, full-WP
acceptance, dependency unlock, signing or protected-merge approval is claimed.
