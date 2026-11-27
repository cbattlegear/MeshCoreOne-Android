# PRIZM-OBSERVER Prizm Mobile Observer (AndroidOnly)

Status: AndroidOnly feature with no Swift counterpart; the frozen Swift tree is untouched.
Scope: pure JVM logic in `core:observer` (capture/filter, claim flow, Prizm client interface, upload pipeline, session/runner),
bounded node signing in `core:protocol`, and the Android adapter and UI (see "Android adapter and UI" at the end).
The real Prizm HTTP client and MQTT publisher are written and unit tested against fakes and an in-process HTTP server; **neither has run against the real
server or broker yet**. The offline stand-ins remain, selectable in debug builds only.
Wire formats follow the Prizm server's "Server contract (as built)" (server repo, branch `mobile-observer`, `prizm-app-contract.md`),
which overrides the earlier build spec and every format this document used to guess.

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
  `PACKET_HASH_LENGTH` (8) bytes; the contract confirms this rule for the server's own hash (`ComputeContentHash`). The app keeps the
  hash locally but does not send it. Differs deliberately from `ParsedRxLogData.packetHash` (payload only).
- Filter: ADVERT, TRACE, PATH, ACK, CONTROL are Full. GRP_TXT/GRP_DATA are Full only when an allow-listed
  channel key matches the 1-byte channel hash and the 2-byte MAC verifies. Everything else is private.
  Private traffic becomes `MetaOnly` or `Dropped`, controlled by `PrivateTrafficPolicy` (default `META_ONLY`). A `MetaOnly` record has no
  payload and no whole-packet field; what is sent is its `metaRaw`: header, the 4 transport codes when the route type has them,
  the path-length byte and the path (the server reads exactly that and ignores anything after the path, so the transport codes
  are needed to find the path byte; they are routing data, not payload). Tests assert no payload byte can reach the wire.
- Allow list: Public key `8b3387e9c5cdea6ac9e5edbaa115cd72` plus slots named `#...` whose secret equals the
  first 16 bytes of SHA-256(name). Plaintext is never produced.

## Open questions

Resolved by the contract: hash length (the app no longer sends a hash), meta-only vs drop (meta is a defined record mode), timestamp format
(UTC RFC 3339 with milliseconds), key case (lowercase hex in topics and bodies), the topic prefix and the broker credentials (see below).
Still open:

1. MAC scheme follows the repo's existing `WireCrypto` (HMAC-SHA256, 16-byte secret, first 2 bytes, over ciphertext), cross-checked by a
   test using an independent 32-byte-zero-padded key; not checked against the firmware source directly.
2. `GET /api/prizm/me` is read for `user.nodes` to know which nodes are already claimed, but the shape of an element is not in the contract: the client
   accepts a bare hex string or an object with `pk`/`publicKey`/`public_key`, and treats anything else as "unknown" (the claim then simply signs).
3. The claim `expiresAt` format (unix seconds, or milliseconds above 1e11) is assumed from the token's `expiresAt`; when absent the 5 minute nonce lifetime applies.
4. The beta-server override cannot complete a browser sign-in on its own: Android returns to the app only for hosts declared as App Links in the manifest
   (`mqtt.nodakmesh.org` only), and the server's `assetlinks.json` must list the app. Plain `http://` beta URLs also need a cleartext network security config.

5. In-memory live records (at most one batch interval) are lost if the process dies before a flush.
6. `recv_errors`: firmware without that trailing field parses as 0 in the shared parser, so it is sent as 0 (the server stores a missing
   counter as NULL, which would be more accurate).
7. A rejected record is silent: the server drops invalid records without an error, so only the app-side checks below keep data from being wasted.

## Node signing (`core:protocol`)

`MeshCoreSession.signBounded` (also on `ConfigurationSessionOps`) signs in chunks of at most 160 bytes
(`SigningLimits.MAX_CHUNK_BYTES`) and maps firmware ERR replies: BAD_STATE -> `SigningFailed(REASON_NO_SESSION)`,
TABLE_FULL -> `SigningFailed(REASON_BUFFER_FULL)`. Byte layouts were checked against meshcore-dev/MeshCore
`examples/companion_radio/MyMesh.cpp`: SIGN_START 0x21 -> 0x13 `[0x00][u32le max = 8192]`; SIGN_DATA 0x22 -> OK or ERR;
SIGN_FINISH 0x23 -> 0x14 + 64 bytes or ERR(BAD_STATE). The node signs the raw bytes. `signStart`/`signFinish` now fail
fast on an ERR reply (previously they would wait for a timeout). The original `sign` is unchanged.

## Claim (`observer.claim`, `observer.prizm`)

`ClaimService`: if the node is already in `claimedNodes` -> `PUT /api/prizm/app/node` (info update, no signing); else claim/start -> the node signs the
`message` the server returned **exactly** (its UTF-8 bytes; it contains the Discord id the app does not know) -> claim/finish. Because a node signs any bytes it is
given, `ClaimMessage.validate` refuses anything that is not exactly `prizm-claim:v1:<this pk lowercase>:<digits>:<this nonce>`, so a faulty server cannot get
the node to sign something else (a broker token, say). Nonce lifetime is 5 minutes (the response's `expiresAt`). Ownership by someone else is reported at
finish (409 `owned`, with `ownedBy`); after the user confirms, the flow starts over with `takeOver: true` (a fresh nonce and one more signing).
Request bodies follow the contract Request bodies follow the contract:
`claimFinish` = `{"pk","nonce","signature","nodeType","device":{...},"takeOver"}`; `device` = `name, model, fwVersion, fwBuild, fwVerCode, txPowerDbm,
maxTxPowerDbm, radio{freqMHz,bwKHz,sf,cr}, clientRepeat, pathHashMode` (no public key, never the BLE PIN). `NodeType` wire values are
`handheld`, `vehicle`, `home`, `portable` (the earlier `mobile` value does not exist on the server and was removed).
`NodeDeviceInfo` is built field by field and has no PIN or key-export field; tests enforce this by serialization and reflection.
`PrizmClient` is the seam for Prizm; `HttpPrizmClient` (over `HttpTransport`; `OkHttpPrizmTransport` in the app, OkHttp was already a dependency) is the
real one: `Authorization: Bearer pza_...` on every call but the token exchange, 401 -> `PrizmUnauthorizedException` (tokens last 180 days with no refresh, so the
app signs the user out and asks for a new sign-in; the old refresh code is deleted), other failures become short user-facing `PrizmException` messages (never
raw server text), and bodies are parsed for known fields only. Calls: token exchange, `me`, claim start/finish, `PUT` node, `DELETE` node, data delete, logout.
`StandInPrizmClient` is the offline fake (its claim message has the server's shape so the real validation runs). The server returns only a display name
(no Discord id), so `PrizmUser` has only that. PKCE (`Pkce`, `AuthorizationRequest`) is tested against the RFC 7636 Appendix B vector; the
authorization URL is `/api/prizm/auth/app/login?code_challenge=...&code_challenge_method=S256&state=...` and nothing else.

## Broker login (`observer.broker`)

WebSocket over TLS to `mqtt.nodakmesh.org:443` (MQTT subprotocol `mqtt`, path `/`; `GET /api/config/observer-join` returns host, port, audience).
Username `v1_<PK>` (64 hex; written upper case). Password `base64url(header).base64url(payload).hex(signature)` (no padding):
header `{"alg":"Ed25519","typ":"JWT"}`, payload `{"publicKey":"<PK>","iat":<s>,"exp":<s>,"aud":"mqtt.nodakmesh.org"}`; the node signs the ASCII bytes
`<b64 header>.<b64 payload>` with `CMD_SIGN_*` (`SessionNodeSigner`, bounded 160-byte chunks; the input is about 228 bytes). The server accepts
`exp - iat` up to 168 h and 5 minutes of skew; the app mints 24 h tokens (as meshcore-packet-capture does). `BrokerToken` is pure and has a
known-answer test with a fake signer; `BrokerLoginProvider` caches a token and signs a new one when it expires within 2 hours (a failed signing
keeps the old token and throws). `ObserverSession.brokerLogins` exposes it (its audience comes from `BrokerDirectory`); the publisher asks it on every (re)connect, so every reconnect gets a
current token and a cached one is reused until it nears expiry. There is no last will and no status message: the broker allows only
`meshcore/client/<pk>/packets` and `/rf`, and only once the node is claimed.

## MQTT publisher (`observer.mqtt`)

**Library choice: none.** The dependency lock and `verification-metadata.xml` are strict (`lockAllConfigurations`, SHA-256 per artifact); Paho and HiveMQ would add
several transitive artifacts to every locking configuration and to verification, and neither is needed for a publish-only client. Instead a small MQTT 3.1.1
codec (`MqttPackets`: CONNECT with username/password/clean session and no will, PUBLISH QoS 1, PINGREQ, DISCONNECT out; CONNACK, PUBACK, PINGRESP in; byte-level
tests from the spec layout) runs over OkHttp's WebSocket (already a dependency; subprotocol `mqtt`, binary frames) behind the `MqttTransport` seam.
`WebSocketMqttPublisher`: `ObserverSession.start` calls `MqttPublisher.open(scope, brokerLogins, onConnected)`; the loop fetches broker config, takes a fresh login,
opens the socket, sends CONNECT, waits for CONNACK, calls `onConnected` (= `UploadPipeline.onConnected`, which resends the backlog), pings at half the keep-alive
(60 s) and closes if the broker is silent for 1.5 periods; on any drop it waits (2 s doubling to 60 s; 60 s or more after a refused login, CONNACK code 4/5, or a
login the node could not sign) and starts over. `publish` sends a QoS 1 PUBLISH and suspends until the matching PUBACK (15 s timeout = `Failed`); with no
connection it returns `NotConnected`, and the pipeline queues and resends oldest first. Nothing is subscribed. `BrokerDirectory` reads
`GET /api/config/observer-join` (host, port, audience; `enabled:false` means no connection is tried; `tls:false` is never honoured) and falls back to the base URL's
host on 443. The publisher is covered by a scripted fake broker (connect, PUBACK, missing PUBACK, drop and reconnect with a fresh token, backoff, refused login,
protocol error, close); that says nothing about how the real EMQX broker behaves.
Which publisher runs: the real one, except in a debuggable build with the "offline stand-ins" switch on. There is **no automatic fallback** to the stand-in when the
server is unreachable, because the stand-in acknowledges everything and records would be dropped from the queue as if sent.

## Upload (`observer.upload`)

`RecordSerializer` (contract JSON), `ObserverTopics`, `UploadPipeline` (15 s / 50 records, 60 s when low-activity; queue cap 50,000 rows and 7 days;
delete after ack; queued rows resent oldest-first before live; no `delayed` flag, a resent record keeps its heard `timestamp`). Messages are at
most 500 records (the pipeline sends at most 50). External pieces are interfaces: `MqttPublisher`, `UploadQueueRepository`, `UploadClock`, `NodeSigner`.

Packets, `meshcore/client/<pk>/packets` (`<pk>` lowercase hex, QoS 1, not retained):
`{"origin":<node name>,"origin_id":<pk>,"direction":"rx","records":[...]}`; record = `{"mode":"full"|"meta","raw":hex,"SNR","RSSI","timestamp",
"gps":{lat,lon,acc_m,alt_m,v_acc_m,speed_mps,bearing_deg,fix_age_ms,provider,interpolated}}`. `lat`/`lon` have 6 decimals, absent optional fields
are omitted, `timestamp` is the heard time as `yyyy-MM-ddTHH:mm:ss.SSSZ`. No hash, channel name, session id or delayed flag is sent. A record
without `gps` is dropped by the server, so `TimedRecord.location` is not nullable.
RF samples, `meshcore/client/<pk>/rf`: see "Health records". No region segment exists: the server decides ND/MN from the position.
TODO: the Room-backed `UploadQueueRepository` is not written (core:database is an Android module with schemas and migrations);
`InMemoryUploadQueueRepository` is the only implementation (one queue for the process).

## Decided with the contract

- Records without a position are not uploaded (the server drops them and the data would be wasted). The app skips them: sharing off, no usable
  fix, outside the geofence, inside a privacy zone. There is no "last good fix" acceptance and no location-less upload any more.
- The app also skips what the server would drop for position quality: accuracy over 50 m, fix older than 10 s, and (0,0)
  (`ServerGpsLimits`); such a record is "waiting for a fix". The server rule `acc_m > 50` and `fix_age_ms > 10000` is from the contract.

## GPS fields (`observer.location`)

`LocationFix` (plain data; the Android adapter converts `Location`, using `elapsedRealtime` for `fixElapsedMs`), `FixBuffer`
(last 2 minutes, thread safe) and `PositionResolver.resolve(rxElapsedMs, rxTimeMs)`: fixes just before/after rx; both present and
less than 5 s apart -> linear interpolation (lat/lon with antimeridian wrap, alt, speed, bearing the short way round; `acc_m`/`v_acc_m`
are the worse of the two; alt/speed/bearing only when both fixes have them) with `interpolated=true` and no `fix_age_ms`;
otherwise the nearest fix with `fix_age_ms`; nothing within 30 s -> `PositionResult.NoFix(lastGood, lastGoodAgeMs)` (the last good
fix outlives the 2 minute buffer). `SamplingRatePolicy.intervalMs`: 1 s moving, 10 s once speed < 1 m/s for 30 s (an anchor fix at
or before the window start is required; unknown speed counts as moving).
Hook: a packet heard just after the newest fix has no "after" fix yet; the adapter should resolve after the next fix (at most one
sampling interval, 10 s when still) or accept the nearest-before result.

## Location sharing and privacy zones (`observer.location`)

`LocationRedactor(LocationSharing, PrivacyZoneSource)` is the last door a position passes before it is attached to a `TimedRecord`. It returns
null when sharing is off or the position is within any `PrivacyZone` (circle, default 300 m, haversine). A fix whose accuracy circle touches a
zone is also withheld (stricter than the server, set deliberately so a poor fix beside home never leaks). It fails closed if the setting or the
zone source throws. Because the server needs a position, a null result means the packet is **not uploaded or queued at all** (counted as "not
sent (private place)"): nothing from a private place leaves the phone, neither the packet nor the position. `PrivacyZonePipelineTest` drives
packets through `ObserverIngest` into a fake publisher and asserts nothing is published or queued.
"Share my location" off therefore pauses uploads (it no longer uploads packets without location); the Settings copy says so.

## Geofence (`observer.geo`)

Uploads are limited to North Dakota and Minnesota, enforced app-side with the **server's own file**: `src/main/resources/.../geo/nd-mn.json` is a
byte-for-byte copy of `cmd/ingestor/fences/nd-mn.json` (US Census `cb_2023_us_state_5m`, simplified to 0.5 km; 454 Minnesota and 106 North Dakota
vertices; about 11 KB, a reasonable app resource), with provenance in `nd-mn.PROVENANCE.md`. Format
`{"source":...,"polygons":{"<name>":[[lat,lon],...]}}`, read by `GeofenceJson` (via the small `MiniJson` reader; the module has no JSON library) and
evaluated by `PolygonGeofence`: inside any polygon, or within 2 km of an edge, passes (the server's rule), so both make the same call. Distances use
a local equirectangular projection centred on the query point (error far below the 2 km buffer). The server also rejects (0,0); the gate does too.
The geofence no longer reports a region: there is one pipeline and one topic. Sanity tests (Fargo, Moorhead, Minneapolis, Bismarck, Minot, Duluth
inside; Sioux Falls, Billings, Winnipeg, 0/0 outside; the 2 km buffer at the real Minnesota-Iowa line) are in `NdMnGeofenceTest`. The placeholder data,
its text format and its README are deleted.

## Upload gate and ingest (`observer.geo.UploadGate`, `observer.ingest`)

`UploadGate(geofence, sharing)` states: `LOCATION_OFF` (sharing off, checked first, fails closed; text "Upload paused: location is off"),
`WAITING_FOR_FIX` (no fix, or one the server would drop; "Paused: waiting for a location fix"), `OUTSIDE` ("Paused: outside ND and MN"; packets heard
meanwhile are rejected and never queued, so nothing heard outside is uploaded on return) and `INSIDE` (upload). `admit(position)` returns
`Accept(location)` or `Reject(reason)`; `refreshSharing` is called on the session timer so the text updates when the switch is flipped without a packet
(and re-decides from the current position when it is flipped back on). `ObserverIngest.onRecord(record, rxElapsedMs, rxTime)` is the single entry
in a fixed order: resolve -> gate -> redact (privacy zones) -> build `TimedRecord` (always with a position) -> the single `UploadPipeline`.
Outcomes: `Uploaded`, `Paused(text)`, `Withheld` (privacy zone), `Dropped`.
Tests: `ObserverIngestTest` drives a real pipeline into a fake publisher and asserts a packet with sharing off, without a usable fix, outside ND/MN or
inside a zone never reaches the publisher or the queue (even with the broker down).

## Health records (`observer.health`)

core:protocol already had `PacketBuilder.getStatsCore/Radio/Packets` (0x38 + type), `getBattery` (0x14) and the parsers for RESP_CODE_STATS
(0x18) and RESP_CODE_BATT_AND_STORAGE (0x0c), so those are reused; nothing was added to core:protocol.
**Firmware layouts verified** against `examples/companion_radio/MyMesh.cpp` (local checkout `/Users/joshreign/Documents/Gat/meshcore`; no git
revision recorded): core = batt_mv u16, uptime_s u32, err_flags u16, queue_len u8; radio = noise_floor i16, last_rssi i8, last_snr i8 (x4),
tx_air_s u32, rx_air_s u32; packets = recv, sent, sent_flood, sent_direct, recv_flood, recv_direct, recv_errors (u32 each); battery reply =
mv u16, used KB u32, total KB u32; all little endian. `FirmwareLayoutTest` builds frames exactly that way and runs them through `PacketParser`.
Caveat: see open question 6.

Health goes to `meshcore/client/<pk>/rf`, one sample per message, QoS 1, not retained, through `UploadPipeline.publishRf` (never queued or retried: a stale
reading is worse than none). `HealthRecordSerializer`: `{"timestamp","gps":{"lat","lon","acc_m"},"stationary","uptime_secs","battery_mv","queue_len","errors",
"noise_floor","last_rssi","last_snr","tx_air_secs","rx_air_secs","recv","sent","flood_rx","direct_rx","flood_tx","direct_tx","recv_errors"}`; absent fields
omitted (never zero-filled), millivolts only. **`uptime_secs` and `gps.lat`/`gps.lon` are required** or the server drops the sample, so
`HealthRecord.create` returns null without uptime and no sample is sent when there is no position (sharing off, no fix, outside, privacy zone), when the node
link is down, or when the firmware cannot report core stats (battery-only fallback has no uptime). `stationary` comes from the sampling policy.
`ProtocolHealthSource` tries core stats first; if the node answers with a device error or "feature disabled" (firmware older than v8) it falls back to
`CMD_GET_BATT_AND_STORAGE` for `battery_mv` only and remembers that; timeouts are not taken as "unsupported". Radio and packet stats fail independently;
failures are returned, never thrown. `HealthPoller`: due at once, then every 5 minutes after each attempt (one poll after a long gap; a skipped or failing
poll still waits the full interval); tested with a fake clock. `HealthPublisher.pollOnce` applies the same gate as packets (paused -> the node is not even read).

## Hook points for the Android service and UI adapter

- Location: convert `Location` to `LocationFix` (`fixElapsedMs` = `elapsedRealtime`), call `PositionResolver.add`; request updates at
  `SamplingRatePolicy.intervalMs(resolver.buffer.snapshot(), now)` and re-request when it changes. Resolve after the next fix (see above).
- Settings: implement `LocationSharing` (switch) and `PrivacyZoneSource` (zones, default radius 300 m).
- Wiring: `ObserverSession` owns `PositionResolver`, `UploadGate(NdMnGeofence.load(), sharing)`, `LocationRedactor`, one `UploadPipeline` and `ObserverIngest`;
  timer: `ObserverSession.tick`; after each connect `UploadPipeline.onConnected`.
- Notification: show the gate text (null when uploading); `UploadGate.state` for the UI.
- Health: driven by `HealthPoller.runIfDue` on the session timer; `bleConnected` from the transport; log `HealthReading.failures`.
- Broker: `ObserverSession.start` opens the `MqttPublisher` with `brokerLogins` (see "MQTT publisher").

## Android adapter and UI

### Module wiring
`core:observer` gained `session/`: `ObserverSession` (one run against one node: 0x88 `MeshEvent.RxLogData` -> `PacketCapture` -> `PrivacyFilter`
-> `ObserverIngest`; `ChannelAllowList` rebuilt from `MeshEvent.ChannelInfo` replies and from a full slot read on start, reconnect and every
10 minutes; fixes -> `PositionResolver` and `SamplingRatePolicy` (re-requests when the interval changes; nearest-before result is accepted, the
"resolve after the next fix" option is not taken); 5 s timer -> `UploadPipeline.tick`, gate refresh, `HealthPoller` every 5 minutes;
low-activity = screen off and still; `stop()` ends publishing at once and discards in-memory records)
and `ObserverRunner` (one session while the switch is on, signed in and a node link exists; counters carry across reconnects). Both take ports
(`ObserverNodeLink`, `LocationUpdates`, `MqttPublisher`) so they are tested on the JVM with fakes.
`app` now depends on `core:observer` (it already owns every production core module). `feature:settings` does **not**: it declares plain types and
`PrizmObserverPort`/`PrizmPermissionRequester` (`feature/settings/.../prizm`) and `app/.../prizm` implements them on top of `core:observer`
(`PrizmObserverRuntime`). Nothing goes through `core:services`. The session event feed is `MeshCoreSession.events()`.
Counters: heard, uploaded, metadata only, queued, and "not sent (private place)" (packets heard in a privacy zone).
UI: Settings > Prizm Observer (`SettingsDetail.PRIZM_OBSERVER`, an AndroidOnly extra in both the feature and app `SettingsDetail` enums; the
"app pages" count in `SettingsNavigationTest` went from 8 to 9) with sign in/out, the two switches, counters and gate text (including "Upload paused: location is off", also before a node connects), a Privacy page
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

### Stand-ins and debug options
- `StandInPrizmClient` and `StandInMqttPublisher` (acknowledges every message locally and logs topic and payload size to logcat, tag `PrizmObserver`, never payloads) are
  used only when a debuggable build has Settings > Prizm Observer > "Use offline stand-ins" on (stored in the private prefs file; release builds ignore it).
- Server base URL: default `https://mqtt.nodakmesh.org`; a debuggable build can override it in the same screen (stored in the private prefs file, validated by
  `PrizmBaseUrl`: https, or http only for localhost and private networks). Changing the server or the stand-in switch signs the user out locally (tokens belong to one
  server). `PrizmCallback.isCallback` accepts the override's host; see open question 4 for why that alone cannot finish a browser sign-in.
- Settings > Prizm Observer has "Stop claiming this node" (confirm, then `DELETE /api/prizm/app/node/<pk>`), and Privacy has "Delete my data on Prizm" (confirm, then
  `POST /api/prizm/app/data/delete`; Prizm queues the deletion and completes it within about a minute).
- The upload queue is `InMemoryUploadQueueRepository`, one for the process, so rows survive a node reconnect but not a process death (the Room TODO above stands).
- "Uploaded" in the counters means accepted for upload (handed to the pipeline), not a per-record broker ack.

### Not verified without hardware or Prizm
The whole real network path (browser sign-in, claim, the node-signed broker token being accepted, PUBACKs from the real broker, delete and remove), real fixes and sampling changes, foreground-service survival and notification behaviour with the screen off, App Link verification, the browser sign-in round trip,
the real MQTT broker, a real claim signature over BLE against Prizm, health stats from a node, and the cadence of channel refresh on a live node.
There is no Compose UI test: the repo has no standalone compose rule (its Compose tests launch the full `MainActivity`), so only state holders and plain classes are tested.
