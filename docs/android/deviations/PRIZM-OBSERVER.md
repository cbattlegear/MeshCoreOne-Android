# PRIZM-OBSERVER Prizm Mobile Observer (AndroidOnly)

Status: AndroidOnly feature with no Swift counterpart; the frozen Swift tree is untouched.
Scope so far: build-order steps 2-3 only, pure JVM logic in `core:observer`. No network, MQTT, UI, GPS or OAuth.

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
