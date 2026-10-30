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
fresh-connect failure. A serial-stall companion proves the recovery accounting
handoff retires both the next timeout attempt and later serial slot reads before
the new physical connection. The firmware/transport is a test role; App services,
controller, protocol session and Room persistence are production implementations.

## Execution status

Initial exact-head hosted run `41067845663`, Android job `124104216919`,
failed three of 588 `core:data` tests: the existing Room channel companions still
expected same-link reconciliation. Those companions now assert the approved
reconnect-required outcomes while retaining real Room row/history/cache checks.
This is a candidate correction, not a passing execution claim.

Run `41081868192` at `1fab49e3d` stopped before Android execution because the
Room companion header incorrectly combined PortedFrom and AndroidOnly
dispositions. Removing the redundant AndroidOnly header preserves both pinned
source mappings. `python -B -m unittest test_ci_environment -q` then passed
all 19 controller fixtures, including source-disposition validation; this is
not Android execution evidence.

Run `41082777832` at `9426d060a` passed the controller job and executed the new
ChannelRecoveryIntegrationTest suite successfully. The App task ran 1,768
tests with one failure in the existing real pending-envelope recovery case:
retiring an in-flight query during its timeout backoff replaced its precise
correlation cause with NotConnected. The accounting port now carries the actual
controller failure to that admitted fetch without another protocol read. New
reads still reject. A paired service regression checks cause identity and exactly
one session call; the existing App lifecycle case remains unchanged and required.
This correction still needs a fresh exact-head hosted run.

After archive/restore relocated this worktree, the user explicitly directed
correction of its stale reservation path. An exact identity/revision/path CAS
changed only the worktree column; all other columns were asserted unchanged.
Normal supported scope evolution then admitted the Room companion path at
revision 4. Issue #482 records this resolved recovery; no execution lock changed.

No product test pass is claimed yet. Local readiness has no configured native
Windows `ANDROID_CI_STATE`; the supported `python tools\android-port\local\check.py
--worktree` refused because the installed verifier does not support working-tree
overlays. The canonical ledger also retains another execution owner's
`gradle-execution` receipt; it has not been adopted, released or bypassed.

Feature publication is an explicitly unverified candidate until exact-head
hosted checks execute. Their authoritative job result/log belongs on the PR;
no duplicate CI-result bundle is retained here. No hardware, iOS, full-WP
acceptance, dependency unlock, signing or protected-merge approval is claimed.
