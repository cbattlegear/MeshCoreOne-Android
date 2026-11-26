# MeshCore One (MC1) — Android port

This fork hosts an in-progress **native Android port** of MeshCore One, built by a fleet of
specialized Copilot agents against a pinned, read-only Swift/iOS reference. The original Swift
app (below) remains the behavior specification; Android work lands under `android/`.

See [`docs/android/PORTING_PLAN.md`](docs/android/PORTING_PLAN.md) for the full plan and
[`docs/android/port-manifest.json`](docs/android/port-manifest.json) for the authoritative,
machine-readable work package (WP) list.

### Android port status

22 of 65 planned work packages are merged, covering: automation bootstrap, architecture ADRs,
the pinned Gradle scaffold, Linux-only CI with cross-workflow fail-fast, the parity-test
foundation, 12-locale localization, protocol byte/value/crypto/packet/parser parity, Cayenne LPP,
immutable events, JVM session/transport (TCP, mock, NUS BLE), Room-backed persistence and
migrations, Keystore-backed preferences, bidirectional iOS/Android backup compatibility, and
native theme/identity foundations.

In progress: connection-runtime lifecycle ownership (WP-207) and content/location safety
primitives (WP-218). Everything else in the manifest is still pending.

This status is updated as work-package pull requests merge — see merged PR titles
(`[WP-xxx] ...`) for the detailed, per-package history.

---

# MeshCore One (MC1)

A MeshCore client built for Apple devices in Swift.   
Disclaimer: Decisions are made by a human, but almost all code is created with AI.

Download from the App Store or sideload using unsigned IPA files under [Releases](https://github.com/Avi0n/MeshCoreOne/releases).

<a href="https://apps.apple.com/app/meshcore-one/id6757419477">
  <img src="https://developer.apple.com/assets/elements/badges/download-on-the-app-store.svg" alt="Download on the App Store" height="50">
</a>

## Features

### Messaging
- Direct messages with delivery status and flood retry
- Channels (public, private, and hashtag)
- Room Server connections with guest/participant modes
- Heard repeats tracking
- Message reactions (emoji)
- View Path Hops (list and map)
- Link/gif previews and inline images
- Coordinate map previews
- @Mentions
- Per-conversation notification levels
- Hashtag channel deep links
- Filtering and blocking

### Contacts
- Auto-discovery on the mesh
- QR code and advert sharing
- Favorites
- Zero-hop ping
- Telemetry fetch
- Edit Out Path

### Map
- Contact positions
- Map layers (standard, satellite, topography)
- Offline download

### Network Tools
- **Trace Path** - Route through specific repeaters with option to save paths
- **Line of Sight** - Terrain analysis with Fresnel zone and RF parameters
- **RX Log** - Live packet capture
- **Noise Floor Monitor** - Live dBm chart with signal quality stats
- **CLI Terminal** - Remote command-line access to repeaters and rooms

### Remote Node Management
- Node status (telemetry such as battery and uptime. Neighbors for repeaters)
- Remote repeater/room configuration (radio, behavior, identity, reboot)
- Telemetry history charts
- Admin and guest authentication for repeaters/rooms

### Companion Device
- Bluetooth and WiFi pairing
- Radio presets and manual tuning (frequency, TX power, spreading factor, bandwidth)
- Battery monitoring with OCV curves
- Repeat mode

### General
- Live Activity
- Themes
- Offline mesh networking (no internet required)
- Push notifications with quick reply
- Location sharing controls
- Config import/export
- App data backup/restore


## Requirements

-   **iOS/iPadOS 18.0+, or Apple Silicon Mac**
-   **MeshCore-compatible hardware**

## Getting Started

1.  Install [XcodeGen](https://github.com/yonaskolb/XcodeGen).
2.  Run `make generate` (creates a gitignored `dev.yml` — set your Apple team ID there for local signing).
3.  Open `MC1.xcodeproj`.

For more details, see the [Development Guide](docs/Development.md).

## Acknowledgements
Thank you to the MeshCore dev team and all its contributors for contributing their time, money, and energy. This project would not exist without their hard work and dedication.
- [MeshCore](https://github.com/meshcore-dev/meshcore)
- [meshcore_py](https://github.com/meshcore-dev/meshcore_py)

This project uses a fork of [swiftui-messaging-ui](https://github.com/FluidGroup/swiftui-messaging-ui) which has helped tremendously with the chat view experience.

Maps use [MapLibre Native](https://maplibre.org/). Emoji data comes from [Emojibase](https://github.com/milesj/emojibase), through the [emojibase-bindings](https://github.com/matrix-org/emojibase-bindings) package. 

## License

MeshCore One - GNU General Public License v3.0   
Swift MeshCore - MIT
