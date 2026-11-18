# Accessibility tests

Source-only directory. `android/app/build.gradle.kts` adds these folders to the `:app` source sets, so there
is no separate Gradle module.

| Folder | Runs | Where |
| --- | --- | --- |
| `src/test/kotlin` | JVM tests for shell, appearance, messaging, nodes, onboarding, backup, diagnostics and font audits | `verifyScaffoldTests` in CI |
| `src/shared/kotlin` | Audit helpers used by both source sets (contrast, target size, semantics, font, viewport) | compiled with both |
| `src/androidTest/kotlin` | Native instrumentation tests: real accessibility-service snapshots, IME, focus, locale, map, remote nodes, contrast | needs a device or emulator |

Hosted CI compiles the instrumentation tests (`:app:compileDebugAndroidTestKotlin`) but does not run them, so
no CI result covers TalkBack, API 31/37 device or hardware behavior. Run them on a device or emulator and
report what actually ran.

The earlier WP-503 audit write-up (findings A503-01..04, contrast measurements, run logs) was removed with the
rest of the process documentation; it is in Git history at `b9193a1fb9`
(`docs/android/accessibility/README.md`). Treat it as history to re-verify, not as current findings.
