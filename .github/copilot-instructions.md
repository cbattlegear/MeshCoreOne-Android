# MeshCore One Android port

Native Kotlin / Material 3 Compose port of the Swift app, in `android/`. The Swift tree is the
read-only specification, pinned at `db14559b39d32322b06477c6ae676112f583db50`.
Scope and work packages: `docs/android/PORTING_PLAN.md`, `docs/android/port-manifest.json`.
Build, test and CI: `docs/android/README.md`.

## How work gets done

There is one maintainer, @cbattlegear. Nothing here needs anyone's permission.

- Take the work you were asked to do, on its own branch, and open one PR for it. Edit whatever files the
  change needs: no path has an owner, reservation, lease or lock. If branches collide, rebase.
- A prerequisite that is not merged yet is not a blocker. Build on its branch or implement the small piece
  you need. Never fake success to get around a missing piece.
- Hosted `Android CI` is the verification. Run what is cheap locally; a check you could not run is reported
  as "not run" and never holds a push.
- The maintainer merges. Do not wait for bots, labels, receipts, reviewer approval, issue state or another
  session. Do not start extra agents or sessions unless asked.
- Stop and ask only for a product decision, a licensing question, or something that needs credentials,
  hardware or a device you do not have.

## Product constraints

- Native Kotlin/Material 3 Compose; the protocol module is pure JVM. minSdk 31, compile/target SDK 37
  (`android-37.2`). Package and applicationId `com.meshcoreone.android`; debug adds `.debug`.
- Preserve the GPLv3 app and MIT MeshCore notices and every dependency/model/data/asset obligation. Google
  services are optional and never required for mesh messaging, persistence or core navigation.
- Message translation is removed from scope, not deferred. Add no translation providers or models.
  Preserve original message text and app localization. Bringing it back needs a new request from the maintainer.
- All themes are unlocked. No billing, new account/backend, analytics, updater or APK-installation behavior.
  Releases are sideload APKs through GitHub Releases only (WP-506).
- Backups: envelope v1, Unix-seconds dates, Codable bytes/UUIDs, zlib, 50 MiB compressed / 512 MiB expanded.
  Real bidirectional iOS/Android evidence is required; never manufacture compatibility.
- The process database and preferences survive connections; radio services are per connection
  (WP-207 factory-based lifecycle, WP-303 graph assembly).
- Port the original cases and parameter families, typed errors, cancellation and consumer behavior. No silent
  failure, dropped data or fake success. `PortedFrom` / `AndroidOnly` / `GeneratedFrom` headers are
  traceability, not parity; `tools/android-port/portmap.py` checks them.
- The Swift reference stays untouched. If it looks drifted or a needed source is missing from the manifest
  inventory, tell the maintainer. Never advance the pin silently.
- Never claim iOS, device, hardware, license or signing verification that did not happen.

## Pull requests

Title `[WP-xxx] <what changed>`. Fill in the PR template with the commands you actually ran and their results.
Do not invent an issue number for `Closes`. Record a deliberate difference from Swift behavior in
`docs/android/deviations/WP-xxx.md` (original behavior, Android behavior, why, which test covers it).
