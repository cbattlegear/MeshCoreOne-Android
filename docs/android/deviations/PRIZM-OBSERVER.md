# PRIZM-OBSERVER Prizm Mobile Observer (AndroidOnly)

Status: AndroidOnly feature with no Swift counterpart; the frozen Swift tree is untouched.
Scope: pure JVM logic in `core:observer` (capture/filter, claim flow, Prizm client interface, upload pipeline, session/runner),
bounded node signing in `core:protocol`, and the Android adapter and UI (see "Android adapter and UI" at the end).
Still no real network, MQTT library or HTTP client: sign-in and uploading run against stand-ins.

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

## Location sharing and privacy zones (`observer.location`)

`LocationRedactor(LocationSharing, PrivacyZoneSource)` is the only door a position passes before it is attached to a `TimedRecord`.
It returns null (so the record has none of lat/lon/h_acc/alt/v_acc/speed/bearing/fix_age_ms/provider/interpolated) when sharing is
off or the position is within any `PrivacyZone` (circle, default 300 m, haversine). A fix whose accuracy circle touches a zone is
also withheld (stricter than the spec's "inside"; set deliberately so a poor fix beside home never leaks). It fails closed if the
setting or the zone source throws. Packets are still uploaded, just without location. `PrivacyZonePipelineTest` drives records
through `UploadPipeline` into a fake publisher and asserts no coordinate, accuracy or other location key appears in any payload.

## Geofence (`observer.geo`)

Uploads are limited to North Dakota and Minnesota, enforced app-side. `Geofence.contains(lat, lon)` includes a 2 km outside
buffer; `RegionGeofence.regionFor` returns the topic segment (`nd` or `mn`). `PolygonGeofence` does point-in-polygon and
distance-to-edge on a local equirectangular projection centred on the query point; per region the signed distance is negative inside,
positive outside, and the smallest wins (so the shared Red River border goes to the containing or nearer state, and overlapping
polygons to the deeper one). Tests for buffer, corner, concave and multi-polygon logic use small synthetic polygons.

**PLACEHOLDER DATA - MUST BE REPLACED BEFORE RELEASE.** `src/main/resources/.../geo/placeholder-nd-mn.txt` (text format parsed by
`GeofenceData`, loaded by `PlaceholderGeofence`) is a coarse, hand-simplified, generous outline of ND and MN written from rough
knowledge, not from a data source. It is wrong by kilometres in places, including the shared Red River line. Replace it with US
Census cartographic boundary data (`cb_2023_us_state_20m` or `_500k`, simplified to about 100 m); steps are in
`README-PLACEHOLDER.md` beside the file. Sanity tests (Fargo, Moorhead, Minneapolis, Bismarck inside; Sioux Falls, Billings,
Winnipeg outside) must keep passing with the real data.

## Upload gate and ingest (`observer.geo.UploadGate`, `observer.ingest`)

`UploadGate`: INSIDE -> uploading, region (`nd`/`mn`) from the position; OUTSIDE -> paused, notification text
"Paused: outside ND and MN", packets heard meanwhile are rejected and never queued (so nothing heard outside is uploaded
retroactively on return). No fix: accepted only when the last good fix is at most 10 minutes old and inside (it then goes to that fix's
region with no location); older, outside or none -> rejected ("Paused: waiting for a location fix" is an added state text). The geofence
always uses the real position, also when location sharing is off. A point inside the geofence with no region is rejected (`NO_REGION`).
`ObserverIngest.onRecord(record, rxElapsedMs, rxTime)` is the single entry in a fixed order: resolve -> gate -> redact (sharing/zones)
-> build `TimedRecord` -> `RegionalPipelines` (one `UploadPipeline` per region topic, created lazily by a factory that owns the
per-region queue). `UploadPipeline.publishHealth` and `ObserverTopics.health` carry health (see below).
Tests: `ObserverIngestTest` drives real pipelines into a fake publisher and asserts a packet heard outside ND/MN, or without an
acceptable fix, never reaches the publisher or the queue (even with the broker down), and that a privacy-zone record uploads with no
location keys.

## Health records (`observer.health`)

core:protocol already had `PacketBuilder.getStatsCore/Radio/Packets` (0x38 + type), `getBattery` (0x14) and the parsers for RESP_CODE_STATS
(0x18) and RESP_CODE_BATT_AND_STORAGE (0x0c), so those are reused; nothing was added to core:protocol.
**Firmware layouts verified** against `examples/companion_radio/MyMesh.cpp` (local checkout `/Users/joshreign/Documents/Gat/meshcore`; no git
revision recorded): core = batt_mv u16, uptime_s u32, err_flags u16, queue_len u8; radio = noise_floor i16, last_rssi i8, last_snr i8 (x4),
tx_air_s u32, rx_air_s u32; packets = recv, sent, sent_flood, sent_direct, recv_flood, recv_direct, recv_errors (u32 each); battery reply =
mv u16, used KB u32, total KB u32; all little endian. `FirmwareLayoutTest` builds frames exactly that way and runs them through `PacketParser`.
Caveat: firmware without the trailing `recv_errors` field parses as `rx_errors = 0` (the shared parser cannot tell "absent" from zero).

`HealthSnapshot` (all fields optional) -> `HealthRecord` -> `HealthRecordSerializer`:
`{"ts","batt_mv","uptime_s","noise_floor","tx_air_s","rx_air_s","rx_pkts","tx_pkts","rx_errors","ble_connected","lat","lon"}`, absent fields omitted,
millivolts only (never a percentage; 0 mV is treated as "no reading"). Sent on `meshcore/<region>/mobile/<node_key>/health`, QoS 1, not retained,
through `UploadPipeline.publishHealth` (never queued or retried: a stale reading is worse than none).
`ProtocolHealthSource` tries core stats first; if the node answers with a device error or "feature disabled" (firmware older than v8) it falls back to
`CMD_GET_BATT_AND_STORAGE` for `batt_mv` only and remembers that; timeouts are not taken as "unsupported". Radio and packet stats fail independently;
failures are returned, never thrown. `HealthPoller`: due at once, then every 5 minutes after each attempt (one poll after a long gap; a failing poll
still waits the full interval); tested with a fake clock. `HealthPublisher.pollOnce` applies the same gate as packets (paused -> the node is not even
read), and lat/lon go through `LocationRedactor` (sharing switch, privacy zones). With the node link down a record with `ble_connected:false` and no
stats is still sent.

## Hook points for the Android service and UI adapter

- Location: convert `Location` to `LocationFix` (`fixElapsedMs` = `elapsedRealtime`), call `PositionResolver.add`; request updates at
  `SamplingRatePolicy.intervalMs(resolver.buffer.snapshot(), now)` and re-request when it changes. Resolve after the next fix (see above).
- Settings: implement `LocationSharing` (switch) and `PrivacyZoneSource` (zones, default radius 300 m).
- Wiring: `PositionResolver` + `UploadGate(PlaceholderGeofence.load())` + `LocationRedactor` + `RegionalPipelines { region -> UploadPipeline(...) }`
  (own queue repository per region) -> `ObserverIngest.onRecord` for every `ObserverRecord`; timer: `RegionalPipelines.tick`; after each connect
  `RegionalPipelines.onConnected` and `ObserverPresence.announce` per used region.
- Notification: show `UploadGate.notificationText` (null when uploading); `UploadGate.state` for the UI.
- Health: `HealthPublisher(ProtocolHealthSource(session.statsReader()), ...)` driven by `HealthPoller.runIfDue` on the service timer; `bleConnected` from the
  transport; log `HealthReading.failures`.
- Open: ObserverPresence status/device messages are per topics instance, so they must be announced for each region pipeline in use.

## Android adapter and UI

### Module wiring
`core:observer` gained `session/`: `ObserverSession` (one run against one node: 0x88 `MeshEvent.RxLogData` -> `PacketCapture` -> `PrivacyFilter`
-> `ObserverIngest`; `ChannelAllowList` rebuilt from `MeshEvent.ChannelInfo` replies and from a full slot read on start, reconnect and every
10 minutes; fixes -> `PositionResolver` and `SamplingRatePolicy` (re-requests when the interval changes; nearest-before result is accepted, the
"resolve after the next fix" option is not taken); 5 s timer -> `RegionalPipelines.tick`, presence per region, `HealthPoller` every 5 minutes;
low-activity = screen off and still; `stop()` ends publishing at once, discards in-memory records, sends the offline status per region used)
and `ObserverRunner` (one session while the switch is on, signed in and a node link exists; counters carry across reconnects). Both take ports
(`ObserverNodeLink`, `LocationUpdates`, `MqttPublisher`) so they are tested on the JVM with fakes.
`app` now depends on `core:observer` (it already owns every production core module). `feature:settings` does **not**: it declares plain types and
`PrizmObserverPort`/`PrizmPermissionRequester` (`feature/settings/.../prizm`) and `app/.../prizm` implements them on top of `core:observer`
(`PrizmObserverRuntime`). Nothing goes through `core:services`. The session event feed is `MeshCoreSession.events()`.
UI: Settings > Prizm Observer (`SettingsDetail.PRIZM_OBSERVER`, an AndroidOnly extra in both the feature and app `SettingsDetail` enums; the
"app pages" count in `SettingsNavigationTest` went from 8 to 9) with sign in/out, the two switches, counters and gate text, a Privacy page
(zones, plain-words upload lists, delete-my-data) and the claim prompt, node-type picker and takeover confirmation. The claim dialogs are drawn at
the app root (`PrizmClaimHost` in `MainActivity`) so they appear on any tab after a signed-in user connects an unclaimed node. Copy is English only,
in `core/l10n/.../prizm_observer_strings.xml` (same pattern as `remote_login_strings.xml`).

### Service, permissions, manifest
`PrizmObserverService` (thin; type `connectedDevice|location`, location only while precise location is granted, not sticky) shows the notification
(counters, `UploadGate.notificationText`, Stop) and reports screen on/off. Switching on needs a signed-in user plus `ACCESS_FINE_LOCATION` and, on
API 33+, `POST_NOTIFICATIONS` (requested at the toggle); the foreground start happens while the app is visible. After a process or service death the
switch stays on and the service is restarted the next time the app comes to the foreground (a background start is not allowed).
**Manifest change:** the WP-312 `tools:node="remove"` for `ACCESS_FINE_LOCATION` is gone and `ACCESS_FINE_LOCATION` plus
`FOREGROUND_SERVICE_LOCATION` are declared, because a coverage fix at city-block accuracy needs a precise fix. The app still never requests background
location, and the permission is only asked for at runtime when uploading or sharing is switched on. The WP-312 androidTest assertion "precise location must
not be requested" was replaced by "background location must not be requested" (androidTest was not run here). An App Link filter for
`https://mqtt.nodakmesh.org/app/callback` (autoVerify) was added to `MainActivity`; `assetlinks.json` is server side.
Location comes from the framework `LocationManager` (fused provider on API 31+, GPS fallback): there is no Play services dependency in the lock.

### State and secrets
Switches, zones and the node type chosen at claim time live in an app-private `SharedPreferences` file (`prizm_observer`), not in the backup-contract
`PreferenceStore` (its keys are enumerated by the backup format and adding Prizm keys would touch that contract). The OAuth session and the pending PKCE
verifier are sealed with a non-exportable Android Keystore AES-256-GCM key (`KeystoreSecretBox`, alias `com.meshcoreone.android.prizm.session`) because
`core:datastore`'s secret store is keyed per radio. Backup is off for the app. Sign-out revokes (best effort) and removes tokens; switches, zones and node types stay.

### Stand-ins (not real)
- `StandInPrizmClient`: fake user "Stand-in Observer", no network. Sign-in therefore skips the browser; the browser path (`AuthorizationRequest` via
  `Intent.ACTION_VIEW`, since `androidx.browser` is not in the dependency lock, then the App Link, state check, token exchange) is written and unit tested but
  never ran against a server. The OAuth `client_id` (`meshcore-one-android`) is a guess.
- `StandInMqttPublisher`: acknowledges every message locally and logs topic and payload size to logcat (tag `PrizmObserver`), never payloads. No MQTT library is in
  the lock. The real client must connect with `PrizmClient.mqttCredentials`, use `MqttConnectionConfig.forNode` (last will) and call `onConnected`.
- "Delete my data on Prizm" returns "not available yet" (`PrizmDeleteResult.Unavailable`).
- The upload queue is `InMemoryUploadQueueRepository`, kept per region for the process, so rows survive a node reconnect but not a process death (the Room TODO above stands).
- Geofence data is still the placeholder outline.
- "Uploaded" in the counters means accepted for upload (handed to the pipeline), not a per-record broker ack.

### Not verified without hardware or Prizm
Real fixes and sampling changes, foreground-service survival and notification behaviour with the screen off, App Link verification, the browser sign-in round trip,
the real MQTT broker, a real claim signature over BLE against Prizm, health stats from a node, and the cadence of channel refresh on a live node.
There is no Compose UI test: the repo has no standalone compose rule (its Compose tests launch the full `MainActivity`), so only state holders and plain classes are tested.
