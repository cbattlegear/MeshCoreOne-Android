---
applyTo: "android/**"
---

# Android implementation boundaries

Use the approved contracts in `docs/android/architecture/` and `docs/android/adr/`. Edit any file the change
needs; there are no per-path owners, permissions or reservations. Ask the maintainer only about product,
license, or hardware/credential questions.

Features do not depend on other features; protocol/model/contracts are Android-free where specified.
Production code never depends on testing helpers. Do not choose speculative dependency versions.

Port original raw values/identity/units/order/validation/retry behavior. Byte arrays need content
equality; payload limits use UTF-8 bytes. Explicit queues/locks protect operations across suspension:
`limitedParallelism(1)` is not thread affinity or a full-operation lock, and SharedFlow does not close.
Use process, connection-generation and screen lifetimes correctly; no unowned scopes or duplicate monitors.

Every Kotlin production/test file has one or more `// PortedFrom: <Git path>@<reference SHA>`
headers or `// AndroidOnly: WP-xxx <reason>`. Generated files identify generator and pinned inputs.
Keep many-to-many mappings and original assertion/parameter-family acceptance distinct from headers.

Compose uses immutable route/content state, Material tokens, shared typed strings, native Back/IME,
adaptive current-window layout, 48dp targets and 200% font/accessibility states. Effects never send,
connect or import during composition. GPS/notifications/camera/GMS denial affects only its capability.

CDM does not establish GATT or grant every background exemption. Use one connection owner,
API-gated FGS/LAN/Bluetooth permissions, no hidden APIs and honest force-stop/OEM limitations.
Ambient radio/chat status is not automatically eligible for promoted Live Updates.
Alpha AppFunctions is not a release prerequisite.

Report typed errors and actual unsupported/loading/cancelled outcomes. Never destructively recreate
Room/keys, invent Gradle tasks, loosen goldens/coverage, or claim physical/device evidence from a proxy.
