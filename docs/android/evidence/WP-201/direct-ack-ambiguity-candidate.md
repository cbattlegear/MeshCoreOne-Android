# WP-201 nonactivated direct-ACK ambiguity candidate

Repository: `cbattlegear/MeshCoreOne-Android`.
Parent: `fe13e3b694fcf9775b168acbb33823871f5b336f`.
Reference: `db14559b39d32322b06477c6ae676112f583db50`.
Manifest SHA-256: `11120a20d04408b3d21fac09180412df0490cabf1dafb8a3c98fe83e0fad31e4`.
Assignment: `assignment:fc9361f4-ec03-4739-994b-ddb9b4e4cec9:call_u6kHc0yY75k8H2uYUKaxFFim`.

## Candidate boundary, not product policy

The coordinator requested a separate nonactivated executable candidate, not an
ACK expiry, permanent send block, active-ref change, or final user-visible policy.
`DirectAckAmbiguityCandidate.assess` is an isolated pure classifier over immutable
nonpayload evidence. Nothing calls it from production. `DirectAckAuthority`,
its outcomes and all existing consumers remain unchanged.

The classifier returns either `NoKnownAmbiguity` or `Nonsettling` with all
applicable reasons. The former is deliberately not permission, success, a
storage receipt, or proof of ACK origin. A storage implementation would have to
capture complete relevant radio history and validate issuer, lease, incarnation,
revision and current grant atomically with settlement. Public diagnostic values
cannot manufacture authority. No stub repository or default implementation exists.

An eventual optional persistence/settlement interface is not frozen or authored
in this successor. Storage owner `f8058663-e138-4456-9d4d-6ad11522824c` owns that
transactional boundary. Service owner `13a03908-afb8-42bd-9c96-57a27920076e`
confirmed its Room firmware harness has a suspending before-send hook preceding
mock packet recording. Such a hook can mark potential emission only, not origin.
Neither owner's implementation or conformance execution is claimed here.

## Evidence distinctions

`PREPARED` requires affirmative evidence that the candidate's pre-send marker
never committed. `POTENTIAL_EMISSION` says only that it might have emitted.
`LEGACY_UNKNOWN` prevents historical registered-only wires from being silently
classified as definitely unsent. The active storage design was reported to have
no such marker, so a migration cannot relabel those old rows PREPARED by default.

Within one still-valid logical claim, ordinary retries share a delivery target.
A different logical claim remains distinct after independent mutation, even if
message ID, incarnation or payload is equal. Replacement is not continuity.
The producer must persist globally distinct claim identity within its radio
history, including across leases; the classifier does not authenticate UUIDs.

An earlier different claim with unknown actual acceptance is a wildcard for
incoming code ambiguity. A known predicted or accepted overlap is separately
reported, including after current acceptance. Both reasons can coexist.
An affirmative prepared-only competitor and another radio are excluded.
Prepared or legacy-unknown matching wires alone do not become settling candidates;
an eligible matching earlier wire of the same valid claim can supply evidence.

Nonsettling means no DELIVERED mutation from that ACK. It does not gate wire
sending, accepted-send binding or explicit `SendCompleted`. Confirmation
uncertainty can remain indefinite: timeout, reconnect, revocation and elapsed
time do not prove an old ACK impossible. No TTL or origin is invented.

## Conformance controls

Nine new Kotlin test methods exercise the actual classifier:

- Prepared target before its first wire.
- Unique predicted-code candidate before accepted result.
- Earlier retry code during a third wire within the same valid claim.
- Identical accepted code across distinct claims after both accepted.
- Shared old prediction after different actual acceptance, and distinct actual.
- Unknown prior actual code despite different predictions.
- Single-wire actual-code mismatch: prediction stops matching after actual
  acceptance; a matching earlier accepted same-claim wire remains eligible.
- Accepted actual supersedes a cross-claim prediction for known-code overlap.
- Legacy unknown evidence and simultaneous retained ambiguity reasons.
- Affirmative prepared competitor and another radio.
- Unregistered unsigned-boundary code.

The first hosted revision contained twelve methods. The currently authored
mismatch-corrected successor contains fourteen methods and has not yet been
hosted or executed. These are executable policy-kernel controls, not actual Room all-writer,
atomicity, transport, permission, or integration evidence. Existing tests and
assertions are unchanged. Service's separate immutable `e266620cba6194d0eebc952c31cf3516af89a101`
diagnostic does not contain these new methods and is not advanced here.

## Admissions and verification

Actual trusted admissions succeeded before edits:

- Production candidate create: `wp-owned`.
- Related conformance test: `reviewed-support-test/add-or-update-related-test`.
- WP evidence create: `wp-owned`.

Source-only commands:

- `git diff --check` and `git diff --cached --check`: passed.
- `python tools\android-port\controller\validate.py`: passed.
- `python tools\android-port\controller\verification_config.py --check`: passed.
- `python tools\android-port\portmap.py`: passed; output retained in session file
  `ambiguity-candidate-portmap.json`.

At initial authoring, no Kotlin compilation or test execution occurred locally.
Retained local execution hold `local-check:f516a7e271f444dd9cb2efcc8b995a16`
was not bypassed. Exact-candidate hosted verification is recorded below.

## Same-valid-claim retry correction

Independent coordinator review of parent `400c73d5bda08e1e6df68615134ae704bffdd4c8`
found that the original same-claim control selected the earlier wire as target,
masking a latest-retry caller gap. The classifier now treats target as claim/radio
context and searches all matching wires of that same still-valid claim. A
matching potential-emission wire from an earlier retry prevents PRE_WIRE or
UNREGISTERED_CODE merely because the latest retry is prepared or predicts
another code. A prepared retry's own unmatched-by-emission code remains PRE_WIRE.
Producer validation of claim continuity is still mandatory; this does not
reconnect a new claim, independently invalidated grant or replacement.

Three added methods cover the exact latest-emitting and latest-prepared retry
regressions plus simultaneous cross-claim collision/wildcard protection when
the match comes from an earlier wire. All original nine methods/assertions
remain; twelve methods are authored, not executed here. The unchanged
cross-claim loop still examines the whole supplied radio history.
Actual trusted production/test/evidence admissions were rechecked before edits.
Both owners were notified of this claim-scoped usage contract. This separate
successor remains nonactivated, with no expiry, send block or authority claim.

For this successor, `git diff --check`,
`python tools\android-port\controller\validate.py`,
`python tools\android-port\controller\verification_config.py --check` and
`python tools\android-port\portmap.py` passed. Provenance output is retained as
session file `ambiguity-retry-portmap.json`. Kotlin compilation/test execution was not performed locally under the same
retained execution hold; exact hosted execution is recorded below.

## Exact hosted verification for the retry-corrected candidate

The existing unchanged `Android candidate CI` workflow was dispatched on fresh
ref `diagnostic/wp201-ack-ambiguity-167b8dbef-20261105a`, exact head
`167b8dbef7558ef617bb03ae82232dd342a498a4`. Run
[41982164017](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/41982164017)
completed **success**. The completed Linux build job log records
`:core:contracts:compileTestKotlin`, `:core:contracts:test`, and the repository
scaffold/Room verification commands in the successful build.

The actual dedicated `core/contracts` JUnit summary is discovered 43, run 43,
passed 43, failed 0, errors 0, skipped 0. Its exact test inventory includes all
twelve methods in `DirectAckAmbiguityCandidateTest`, including the two
latest-retry regressions and the simultaneous cross-claim/wildcard control.
The workflow's mandatory Android baseline aggregate is passed 4,926, failed 0,
errors 0, skipped 0. Controller/workflow tests, frozen source checks, Swift
codec oracle, and actual SwiftData restore/export oracle also completed
successfully. This is hosted CI evidence only; it is not Room collision
enforcement, device/radio evidence, or authorization to activate the candidate.

## Separate accepted-code-mismatch successor

After preserving the successful exact-167 evidence above, owner review found
that a wire's predicted code must stop being eligible once its own accepted
actual code differs. The nonactivated classifier now uses each wire's
`acceptedCode ?: predictedCode` only as the current matching code; retained
predicted values remain diagnostic history. A matching prediction while the
potentially emitted target still lacks an accepted result is explicitly
`TARGET_ACCEPTANCE_UNRESOLVED`, not settling evidence. The consumer buffers
pre-response raw ACKs, persists actual acceptance, and replays only an ACK
matching the actual code. Earlier actual codes from eligible wires of the same
still-valid claim remain eligible when the latest retry is prepared or returns
a different code. Cross-claim unresolved emission remains wildcard ambiguity.

Canonical `DirectAckAuthority` KDoc now specifies this same storage/consumer
boundary; signatures and active production callers are unchanged. Service
owner reports its separate consumer candidate `1cd63` implements buffering;
storage owns per-wire `acceptedCode ?: predictedCode` and Room enforcement in
`4e25`. Those owner candidates are not activated here and their execution is
separate. No delayed-ACK expiry, origin proof, send block or policy activation
is introduced. This mismatch-corrected successor requires its own exact-head
hosted compile/test run; the successful `167b8db` run above does not verify it.
