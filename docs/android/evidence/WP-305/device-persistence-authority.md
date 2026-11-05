# WP-305 device-persistence authority repair candidate

Status: **source candidate, Kotlin execution and runtime integration unverified**.
This repair does not accept WP-305 or lift any existing issue, channel ERROR2,
frozen-candidate, device, hardware, signing, release or review hold.

## Immutable inputs and ownership

- Repository: `cbattlegear/MeshCoreOne-Android`.
- Assigned base and clean initial HEAD:
  `7cff2cca21bfa906687980bdf653ef0727376352`.
- Read-only Swift specification:
  `db14559b39d32322b06477c6ae676112f583db50`.
  The device persistence source has no diff from that pin.
- Assigned persistence producer session:
  `896808b8-b03c-48e0-b93f-870598b885bd`.
- Assignment:
  `assignment:fc9361f4-ec03-4739-994b-ddb9b4e4cec9:call_45oLsJfphuqa5a9FoO7CxNQd`.
- Dedicated branch: `cbattlegear-device-persistence-authority`.
- Only the agreed device-writer prerequisites were imported:
  `40e6c1e98ac7c4b9499bb2524378c9a9b3f6b4ab`
  (parent is exactly the assigned base), as `035311a20`;
  `bd9e98e9d8ba53d4e11046cb67e8853b0d7c15f8`, as `796a32c5f`.
- Persistence implementation and twelve new test methods:
  `90cd18e789673209219c912a382963047b50fc5c`.

The existing device-writer producer, session
`36c8c079-3643-4bc2-876b-f4c80c3bdf4d`, transferred the Context seam and retained
editor, region consumers, event-token, RX and ConnectionManager integration.
The Settings owner retains its AppState/SettingsService consumer work. No shared
consumer/test hunks or prohibited schema candidate `6d41fc45` were imported.

## Actual path and operation admission

`python tools\android-port\local\reserve.py admit --wp WP-305 --path <path> --operation <operation>`
accepted the following trusted-base admissions:

| Path | Operation | Capability |
|---|---|---|
| `android/core/data/src/main/kotlin/com/meshcoreone/android/core/data/repository/DeviceRepository.kt` | `modify-related-consumer` | `reviewed-support-consumer` |
| `android/core/data/src/main/kotlin/com/meshcoreone/android/core/data/repository/RoomRepositoryContext.kt` | `modify-related-consumer` | `reviewed-support-consumer` |
| `android/app/src/test/kotlin/com/meshcoreone/android/app/container/DevicePersistenceAuthorityRoomTest.kt` | `add-or-update-related-test` | `reviewed-support-test` |
| `docs/android/evidence/WP-305/device-persistence-authority.md` | `modify` | `wp-owned` |

The initial `reserve.py claim` attempt used the actual assigned producer session,
base and assignment text. It returned
`BLOCKED: Existing WP lease is bound to different source/base/manifest/backend`.
The historical WP-305 receipt is still attempt
`6e503330-9c0f-426b-86c3-fa52f404dcab`, revision 1, original session of that same
UUID, original branch `wp303-bind-onboarding`, original base
`680246f7914a5b4e5bf5167a916b620f83a79dee`. It was not evolved, rebound, released,
deleted or impersonated.

The trusted `AUTOMATION.md` local-authoring rule permits isolated assigned
source work after actual capability admission and attempted bookkeeping; a
historical advisory/base mismatch is not a pre-edit file lock. The coordinator
confirmed this rule and the real shared-surface handoff.

`reserve.py transfer` for the Context path, capability
`reviewed-support-consumer`, operation `modify-related-consumer`, named actual
origin session `36c8c079-3643-4bc2-876b-f4c80c3bdf4d` with evidence `40e6c1e...`,
and actual producer session `896808b8-b03c-48e0-b93f-870598b885bd` with the
assignment above. It returned `advisory: true`, `file_edit_blocked: false` and
both actual attributions. This does not mutate the historical lease, admit a
successor, authorize a merge, or prove acceptance.

## Behavioral changes and boundaries

Device write and authority revisions now belong to each device ID, shared by
stores using the **same MeshCoreDatabase instance** under its coroutine mutex.
Bookkeeping publishes only after a successful Room commit. Full replacements,
identity changes, deletion/demotion and affected activation rows invalidate
authority; targeted changes invalidate only the matching device's write revision.
Activation affects its target and previously active rows, not unrelated inactive
devices. Even an identical full replacement remains an authority barrier.

This fixes the observed single-store unrelated-device invalidation. The
multiple-store stale-transform finding is **conditional**: the reviewed
production factory uses one store. The added controls deliberately construct two
stores over the same actual Room database instance. Neither cross-process stores,
separate Room handles for the same file, nor arbitrary direct-DAO authority
changes are claimed to share these process-owned revision maps.

Versioned generic edits now persist `knownRegions` exactly rather than using the
legacy full-save mapping that deliberately retains old regions. Removing regions
atomically resets channels scoped to those regions. A transform removing its
default region must explicitly clear/change `defaultFloodScopeName`; otherwise
the repository reports typed `InvalidData`. It never returns a DTO normalized
silently to different persisted values. Supported direct add/remove operations
remain intact and seed all region controls. No Room schema changes are made.

Tickets carry the connection/runtime owner's suspending authority lease around
the actual conditional write, outside the database mutex. Lock order is runtime
authority then database mutex. Initial and before-commit guards reject retired
work; late rejection throws a private rollback signal inside Room and returns
false only after the transaction rolls back, without publishing revisions.

**A before-commit Boolean guard is mitigation, not durable fencing.** Runtime
invalidation can otherwise occur after the guard and before Room commits.
ConnectionManager must order activation and every ownership-invalidating state
transition through the same suspending authority lease; that integration remains
with the device-writer owner. The default ticket lease does not establish runtime
ordering. The injected-lease Room controls prove only the persistence seam, not
the actual ConnectionManager activation/retirement integration.

## Concrete controls and execution status

`DevicePersistenceAuthorityRoomTest` contains twelve new source-defined methods:

1. A paused actual manager edit survives an unrelated same-store device demotion.
2. A second same-database store's conditional key change rejects a stale transform
   without rerunning that transform under the new authority.
3. A second store's targeted watermark edit permits a lossless targeted retry.
4. An identical cross-store full replacement still invalidates the old ticket.
5. Activation invalidates only its target and previously active device.
6. Generic multi-region edits return exactly the persisted DTO and reset only
   removed scopes, preserving unrelated/all/inherit/other-radio scopes.
7. An omitted removed-default clear reports typed failure with no row mutation.
8. A real SQLite channel-update trigger failure rolls back the region/device
   write and both revision counters.
9. Retirement detected after staged region/channel writes rolls back all rows.
10. Retirement detected after staged activation preserves the prior active row.
11. An injected authority lease spans an actual Room transaction wait, commit
    and revision publication before queued retirement can acquire the lease.
12. Retirement winning that lease prevents the conditional write.

These are real Room/production-manager test bodies, **not executed test results**.
Source method count is twelve; actual Kotlin runner discovery count is unknown.
No local Gradle, Kotlin build, Room test execution or Android acceptance is claimed.
The retained execution owner
`local-check:f516a7e271f444dd9cb2efcc8b995a16` was observed and left unchanged.
The documented verification virtualenv is absent, but native `python` is the
pinned version `3.12.4`; no environment installation or recovery was attempted.

Declared source controls, executed with that interpreter:

| Exact command | Result |
|---|---|
| `python tools\android-port\controller\validate.py` | Passed, exit 0 |
| `python tools\android-port\portmap.py` | Passed, exit 0 |
| `python tools\android-port\controller\verification_config.py --check` | Passed, exit 0 |
| `git diff --check` | Passed, exit 0 |

No hosted result exists for this persistence candidate at authoring time. The
published base's run is not evidence for these changes. The existing general
`workflow_dispatch` requests full verification, not a bounded filtered test run.
Exact-candidate hosted Kotlin execution with positive discovery and composed
runtime-owner interleaving evidence remains required before acceptance. Hosted
results/logs, when available, are authoritative; no duplicate CI bundle is retained.
