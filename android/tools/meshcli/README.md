# meshcli (WP-109, developer-only)

This is a real JVM TCP client of `WiFiTransport` and `MeshCoreSession`, not an
Android screen, mock radio, certification kit or message-sending application.
It is outside every production APK dependency graph. Nothing connects until an
operator explicitly supplies an endpoint. No Bluetooth, Google service,
account, backend, telemetry, updater or APK installation is involved.

## Operator boundary

Only use a companion endpoint and firmware you are authorized to inspect.
The supported operations read local device capabilities, battery/storage,
device time, contacts and channels. Startup performs the protocol's real
`appStart` handshake. The tool does **not** send mesh messages, drain a message
queue, advertise, change radio/configuration/routing, administer remote nodes,
sign, export keys or print channel secrets/BLE PINs. Mutating/remote operations
are deliberately absent, not hidden behind an assumed confirmation.

Only numeric IPv4/IPv6 literals are accepted. Resolve names separately and
confirm the address yourself. This bounded harness does not invoke potentially
uninterruptible OS DNS/mDNS resolution or browse for radios.

Build only using the repository's checksum-pinned JDK21/Gradle9.8.0 and strict
dependency verification; no SDK/JDK/bootstrap/updater is run by meshcli. The
compiled JVM17 distribution runs on a compatible JDK without Android APIs.

The owned module declares the following actual tasks:

```powershell
# At repository root, with JDK 21 on JAVA_HOME:
& .\android\gradlew.bat -p android :tools:meshcli:installDist --dependency-verification strict

# Examples below are MANUAL operator actions, never autonomous verification:
& .\android\tools\meshcli\build\install\meshcli\bin\meshcli.bat --help
& .\android\tools\meshcli\build\install\meshcli\bin\meshcli.bat `
  --host 192.168.1.50 --port 5000 --deadline-ms 5000 device
& .\android\tools\meshcli\build\install\meshcli\bin\meshcli.bat `
  --host 192.168.1.50 --deadline-ms 10000 channels --indices 0,2,7
```

On Linux the generated `bin/meshcli` launch script is equivalent. `run` is also
provided by the declared application plugin; its Gradle exit is a build-tool
exit, while the installed launcher preserves the CLI's exact process exit.
The default command is `device`, the default port is5000 and the overall
deadline is5000ms (allowed25..120000ms). The endpoint has no default.

## Output and failure contracts

UTF-8 JSON goes to stdout; fixed-code, sanitized errors go to stderr. Device
strings/explicit contact names escape quotes, terminal controls and bidi
controls. Contact diagnostic IDs use the source's complete lowercase
public-key hex identity; these intentional diagnostic results can identify
nodes, so treat captured stdout as private operator data. Generic errors never
echo arguments, host, keys, passwords, foreign exception prose or message
content. Device PINs and channel secrets are never output.

| Exit | Meaning |
| --- | --- |
|0|Validated complete query and successful awaited cleanup|
|2|Invalid usage, forbidden operation or nonliteral endpoint; no socket created|
|3|Overall, response or transport timeout|
|4|Malformed/unknown packet, firmware error (including unknown/missing raw code), unsafe correlation|
|5|Connection/IO/EOF or physical cleanup failure|
|6|Real partial contact snapshot or missing channel slots|
|7|Explicit unsupported/disabled operation or unavailable channel capacity/index|
|8|Output IO failed, including closed stdout/stderr pipes; no transport is left open|
|130|Cooperative caller cancellation; cleanup still runs, and cancellation propagates to its caller|

Only a real matching response can complete a read: an ACK/bare OK/push is not a
battery, channel or capability result. A full contact header/count, unique
canonical IDs, current completeness and terminal receipt distinguish a genuine
empty table from a partial stream. Channels use the session's actual transport
capability/window/refill/index matching and preserve missing slots; no timeout,
unanswered write or reconciliation is converted into an empty success.

Unlike the reusable library's deliberately permissive unrelated-error handling,
this one-shot diagnostic client fails closed on any observed ERR/disabled or
malformed packet. A filtered critical-event monitor registers before startup
and drains on finish before success is published, including an ERR coalesced
with a valid response. It is a session subscription, **not a second transport
ingestion**. No automatic reconnect/retry can consume an uncertain late reply.

The overall injected monotonic deadline covers connect/startup/query together,
not one restarted deadline per exchange. Teardown closes the actual socket and
releases the physical claim even after caller-job cancellation. Cooperatively
cancelled tasks propagate cancellation after sanitized reporting; abrupt OS
process termination is not claimed to execute JVM coroutine cleanup. No
portable Ctrl+C/signal-handler or OS scheduling guarantee is manufactured.
The deployed main checks the JVM PrintStream error flag after flushing; a
broken pipe cannot silently become exit0. If stderr is also unavailable, exit8
still signals failure without trying to publish an exception stack.

## Verification and licensing

`test` executes the real parser/session/socket and the actual deployed main in
separate bounded JVMs using a production-only classpath. Loopback peers encode
the pinned Swift layouts independently; injected clocks/barriers replace
arbitrary timing sleeps. Loopback is **not** physical-radio evidence.

Normal verification runs the actual protocol and CLI JVM tests directly from
the root `verifyScaffoldTests` graph. Gradle's configured
`failOnNoDiscoveredTests` behavior is authoritative for zero discovery, and
the test tasks fail directly on assertion, error, or skipped-test failures.
The former raw-JUnit retention, executor-binding forwarding, and post-test
collector layer was retired because exact-commit CI logs are the authoritative
record for reproducible automated checks. Protocol vectors, source fixtures,
dependency locks, notices, and genuine hardware or human evidence remain
independent inputs and are not generated from CI result reports.

The app/tool remains under the repository's GPLv3 terms. Linked MeshCore
protocol and BouncyCastle retain their MIT notices; Kotlin/coroutine/Gradle
components retain their original terms. Generated distributions include the
existing GPL/MeshCore-MIT/BouncyCastle-MIT/Apache notices. This is preservation
of notices, not a new human legal admission or release authorization.

**Hardware status: BLOCKED.** No physical endpoint, device/firmware identity or
operator authorization was supplied or used. Real radio, BLE, Android API31/37,
background/OEM, backup interoperability, source acceptance, signing and release
gates remain separate.
