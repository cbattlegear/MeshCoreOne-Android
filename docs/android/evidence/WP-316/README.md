# WP-316 evidence

The initial external logic contribution from PR #66 is completed here by the native
diagnostics route and the admitted WP-303 App composition adapters. This lists only
commands that actually ran; no physical device or radio was used.

- **Reference:** `db14559b39d32322b06477c6ae676112f583db50`
- **Completion base:** `e2a9653df43a0939cd3c6919a298ea76c2c2403d`
- **Host:** Windows, JDK 21.0.12, pinned SDK `platforms;android-37.2` and build-tools `37.0.0`

| Command | Observed |
| --- | --- |
| `./gradlew :feature:tools:testDebugUnitTest :feature:tools:lintDebug validateModuleGraph` | exit 0; JUnit XML **307 tests, 0 failures, 0 errors, 0 skipped**; lint no issues; graph valid |
| `python tools/android-port/portmap.py` / `controller/validate.py` | both exit 0 |
| `gradlew.bat :feature:tools:testDebugUnitTest :feature:tools:lintDebug :app:testDebugUnitTest validateModuleGraph --max-workers=1 --no-daemon -Pkotlin.compiler.execution.strategy=in-process` | exit 0; Tools **640 tests**, App **547 tests**; 0 failures/errors/skips; lint no issues; graph valid |
| `python tools\android-port\portmap.py` / `python tools\android-port\controller\validate.py` | both exit 0 after App support reconciliation |

All **242** source case ids that `docs/android/test-cases.json` assigns to WP-316's
test files are bound with `@OriginalCase` and run: none missing. The remaining 65
tests are native.

The Swift oracle sources used for formatting expectations are committed as `.swift.txt`
(`oracle.swift.txt`, `oracle2.swift.txt`) with their outputs, so the repository-wide
SwiftLint pass skips them. Adaptations are in `docs/android/deviations/WP-316.md`.

Native flow evidence is deterministic state/Compose compilation and JVM behavior tests.
The CLI requires an explicit submitted command and source-equivalent confirmation for
dangerous changes; RX export is deterministic CSV shared through Android; the chart has
textual statistics and keyboard focus state. Physical reception, radio administration,
Android device rendering, signing and hardware behavior are **not verified**.
