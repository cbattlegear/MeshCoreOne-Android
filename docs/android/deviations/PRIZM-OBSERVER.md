# PRIZM-OBSERVER Prizm Mobile Observer (AndroidOnly)

Status: AndroidOnly feature with no Swift counterpart; the frozen Swift tree is untouched.
Scope so far: pure JVM logic in `core:observer` (capture/filter, claim flow, Prizm client interface, upload pipeline) plus
bounded node signing in `core:protocol`. No real network, MQTT library, UI, GPS or HTTP client yet.

## Module placement

`core:observer` depends only on `core:protocol` (same tier as `core:model`; added to
`architecture/modules.md`). It reuses `PacketParser`/`RxLogParser` (0x88 LOG_DATA frame and packet
header/path split), `ChannelInfo` (RESP_CODE_CHANNEL_INFO 0x12 slots from CMD_GET_CHANNEL 0x1F),
`WireCrypto.authenticate` (MAC check), `sha256` and `ChannelSecret.DeriveFromName`. Features must reach it
through an injected interface from `app`, never through `core:services`.
All sources carry `// AndroidOnly:` headers for `tools/android-port/portmap.py`.

## Behaviour

- Capture adds firmware limits the Swift parser lacks: path <= 64 bytes, payload <= 184 bytes; failures are
  `CaptureResult.Malformed`, never exceptions.
- Hash: SHA-256 over payload-type byte, then (TRACE 0x09 only) the path-length byte, then payload; first
  `PACKET_HASH_LENGTH` (8) bytes. Differs deliberately from `ParsedRxLogData.packetHash` (payload only).
- Filter: ADVERT, TRACE, PATH, ACK, CONTROL are Full. GRP_TXT/GRP_DATA are Full only when an allow-listed
  channel key matches the 1-byte channel hash and the 2-byte MAC verifies. Everything else is private.
  Private traffic becomes `MetaOnly` (header, path-length byte, path, packet length, hash, snr, rssi; no payload,
  transport codes or raw bytes) or `Dropped`, controlled by `PrivateTrafficPolicy` (default `META_ONLY`).
- Allow list: Public key `8b3387e9c5cdea6ac9e5edbaa115cd72` plus slots named `#...` whose secret equals the
  first 16 bytes of SHA-256(name). Plaintext is never produced.

## Open questions (unverified)

1. Hash length must match Prizm's fixed observers (`PACKET_HASH_LENGTH`).
2. MetaOnly vs Drop for private traffic (`DEFAULT_PRIVATE_TRAFFIC_POLICY`).
3. MAC scheme follows the repo's existing `WireCrypto` (HMAC-SHA256, 16-byte secret, first 2 bytes, over
   ciphertext), cross-checked by a test using an independent 32-byte-zero-padded key; not checked against
   the firmware source directly.

## Node signing (`core:protocol`)

`MeshCoreSession.signBounded` (also on `ConfigurationSessionOps`) signs in chunks of at most 160 bytes
(`SigningLimits.MAX_CHUNK_BYTES`) and maps firmware ERR replies: BAD_STATE -> `SigningFailed(REASON_NO_SESSION)`,
TABLE_FULL -> `SigningFailed(REASON_BUFFER_FULL)`. Byte layouts were checked against meshcore-dev/MeshCore
`examples/companion_radio/MyMesh.cpp`: SIGN_START 0x21 -> 0x13 `[0x00][u32le max = 8192]`; SIGN_DATA 0x22 -> OK or ERR;
SIGN_FINISH 0x23 -> 0x14 + 64 bytes or ERR(BAD_STATE). The node signs the raw bytes. `signStart`/`signFinish` now fail
fast on an ERR reply (previously they would wait for a timeout). The original `sign` is unchanged.

## Claim (`observer.claim`, `observer.prizm`)

`ClaimMessage` builds `prizm-claim:v1:<node_key hex>:<discord id>:<nonce hex>`. `ClaimService`: claimStart -> (already linked:
skip signing, finish with no signature = info update) | (claimed by other: `NeedsConfirmation`, retry with
`confirmTakeover = true`) | (challenge: check the 2 minute expiry, sign via `NodeSigner`, re-check expiry, claimFinish).
`NodeDeviceInfo` is built field by field and has no PIN or key-export field; tests enforce this by serialization and reflection.
`PrizmClient` is the seam for Prizm; `StandInPrizmClient` stands in until the endpoints exist (paths in `PrizmEndpoints`).
PKCE (`Pkce`, `AuthorizationRequest`) is tested against the RFC 7636 Appendix B vector.

## Upload (`observer.upload`)

`RecordSerializer` (spec JSON), `ObserverTopics`, `ObserverPresence` (retained QoS 1 status/device; the last will equals the
offline status), `UploadPipeline` (15 s / 50 records, 60 s when low-activity; queue cap 50,000 rows and 7 days; delete after ack;
queued rows resent oldest-first with `delayed=true` before live). External pieces are interfaces: `MqttPublisher`,
`UploadQueueRepository`, `UploadClock`, `NodeSigner`, `PrizmClient`.
TODO: the Room-backed `UploadQueueRepository` is not written (core:database is an Android module with schemas and
migrations); `InMemoryUploadQueueRepository` is the only implementation.

## Open questions (unverified), continued

4. Prizm's claim/finish wire contract is a guess: where "claimed by another user" is reported, `confirm_takeover`, the null
   signature info-update, and the JSON field names of `NodeDeviceInfo` other than the ones the spec lists.
5. `rx_time` format (ISO-8601 UTC here) and whether `node_key` in topics is lowercase hex. Location keys now follow the spec
   (`lat`/`lon` at 6 decimals, `h_acc`, `alt`, `v_acc`, `speed`, `bearing`, `fix_age_ms`, `provider`, `interpolated`; absent fields omitted).
   The earlier `acc` key was replaced by `h_acc`.
6. Whether MQTT topic prefix from `mqtt-credentials` replaces the `region` parameter.
7. In-memory live records (at most one batch interval) are lost if the process dies before a flush.

## GPS fields (`observer.location`)

`LocationFix` (plain data; the Android adapter converts `Location`, using `elapsedRealtime` for `fixElapsedMs`), `FixBuffer`
(last 2 minutes, thread safe) and `PositionResolver.resolve(rxElapsedMs, rxTimeMs)`: fixes just before/after rx; both present and
less than 5 s apart -> linear interpolation (lat/lon with antimeridian wrap, alt, speed, bearing the short way round; `h_acc`/`v_acc`
are the worse of the two; alt/speed/bearing only when both fixes have them) with `interpolated=true` and no `fix_age_ms`;
otherwise the nearest fix with `fix_age_ms`; nothing within 30 s -> `PositionResult.NoFix(lastGood, lastGoodAgeMs)` (the last good
fix outlives the 2 minute buffer). `SamplingRatePolicy.intervalMs`: 1 s moving, 10 s once speed < 1 m/s for 30 s (an anchor fix at
or before the window start is required; unknown speed counts as moving).
Hook: a packet heard just after the newest fix has no "after" fix yet; the adapter should resolve after the next fix (at most one
sampling interval, 10 s when still) or accept the nearest-before result.
