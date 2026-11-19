# Third-party notices and data provenance

The Android app is GPLv3 and ships the MIT MeshCore notice plus every dependency/data/asset notice it
redistributes. Notices are packaged in the APK under `android/app/src/main/assets/licenses/`.

## What is byte-pinned

| Notice | Source of truth | Checked by |
| --- | --- | --- |
| `GPL-3.0.txt` | `LICENSE` at the pinned Swift reference commit | `python android/scaffold/sync_notices.py` |
| `MeshCore-MIT.txt`, `android/core/protocol/LICENSE` | `MeshCore/LICENSE` at the pinned reference commit | `python android/scaffold/sync_notices.py` |
| `Apache-2.0.txt` | `LICENSE` in the pinned Gradle 9.8.0 distribution (refresh with `--gradle-archive`) | `python android/scaffold/sync_notices.py` |
| `DataStore-Protobuf-BSD-3-Clause.txt` and other dependency notices | Published dependency artifacts | `python docs/android/evidence/WP-204/verify_notices.py --normalize` |
| Theme/designsystem notices | Pinned GPL/MIT bytes | `python docs/android/evidence/WP-301/verify_packaging.py --normalize --self-test` |

`sync_notices.py` reads the reference commit from git, so CI checks out full history. Run it with
`--write` to refresh a drifted generated copy; it never edits the Swift reference.

When a dependency is added or upgraded, add or refresh its notice in `assets/licenses/` and extend the
verifier that owns it. Google services remain optional and are not a license exception: mesh messaging,
persistence and core navigation must work without them.

## Unicode data

`docs/android/notices/unicode-data-notice.txt` and the packaged `Unicode-License.txt` are the Unicode
License V3 text (<https://www.unicode.org/license.txt>) that covers the Unicode Character Database
properties used by the message-text bidi/format-control checks (`MessageTextTest`). The data was taken
from Unicode 17.0.0 UCD files:

| File | Property used | SHA-256 |
| --- | --- | --- |
| `https://www.unicode.org/Public/17.0.0/ucd/PropList.txt` | `Bidi_Control` | `130dcddcaadaf071008bdfce1e7743e04fdfbc910886f017d9f9ac931d8c64dd` |
| `https://www.unicode.org/Public/17.0.0/ucd/DerivedCoreProperties.txt` | `Default_Ignorable_Code_Point` | `24c7fed1195c482faaefd5c1e7eb821c5ee1fb6de07ecdbaa64b56a99da22c08` |
| `https://www.unicode.org/Public/17.0.0/ucd/extracted/DerivedGeneralCategory.txt` | `Cc` / `Cf` | `d62e5bab70ca74f099343f71224fa051cb1fdd61a1ab45c0488c44cfc0b6102e` |

Upgrading the Unicode version means re-fetching these files, updating the hashes above, and re-running
the message-text tests.
