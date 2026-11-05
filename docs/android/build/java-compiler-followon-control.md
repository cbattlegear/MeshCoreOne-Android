# WP-002 follow-on Java compiler guard comparison

This comparison targets exact follow-on
`21495c6b40df071413fa777a1411c03cb842132b`, tree
`41995fdd2aecc070d1612bac788d36d7d92a0724`, collector Git blob
`344863a604e12420873cb3ba9ce7f9bc39987b2a`.
The generated resource `compiler-control/21495c6b.gradle` is a byte-exact Git
export of that commit's `tools/android-port/controller/verify_candidate_task_graph.gradle`;
the test verifies its blob before use. It is not modified runner policy.

The original frozen evidence document and hosted run 42035123447 are unchanged.
The existing real-AGP TestKit route reruns both original runtime controls and adds
three fresh follow-on controls: conventional Kotlin-only positive execution,
conventional Java rejection, and the same additional Java input rejection.
The additional source is attached directly to the real JavaCompile task after
project evaluation, exactly as in the successful original experiment.
Its required Java blob remains `143b56a921c4ef5fbf1b1a9b379053e1272b1b1f`.

The rejection controls require successful actual Java/Kotlin compilation, source
and task-input membership, the emitted payload class and its destination's
membership in both Test classes and runtime classpath. They require the exact
Java guard error, failed Test task, absent named-case report and empty collector
inventory, not a missing-task or compile failure. The positive control requires
one actual passed named JUnit case and exact Kotlin inventory.
Fresh original-collector controls separately prove the Java runtime code-source
relationship; rejected cases are never represented as executions.

Execution uses only the existing hosted scaffold/verifyScaffoldTests route.
No local Gradle, controller activation, production edits, protection changes,
allowlist edits, #580/#567 branch mutation or end-to-end admission-bypass claim
is involved.

## Exact hosted observation

[Run 42038400104](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/42038400104)
executed support commit `10986b66d6e1433c7781474137a8adbef95be33f`, tree
`6052f16e0ff8c562c76c3c8f729babda9f8cba57`. Build-logic reports **47 discovered,
47 passed, zero failures/errors/skips**, including these three new actual cases:

- `follow-on collector accepts conventional Kotlin-only named execution()`
- `follow-on collector rejects conventional Java compiler runtime input()`
- `follow-on collector rejects the same additional Java compiler runtime input()`

Both original-collector real Java runtime controls also passed freshly in this
run; no previous result was transferred. Their nested
`fixture.SelectedRuntimeTest.selectedTestLoadsJavaInput` executions each passed
1/1 without skips and identified the emitted Java code-source directory.

The follow-on Kotlin-only positive executed that same named case **1/1 passed**
without skips, with Kotlin blob `7b94289bd712d5dea999ab4e88ef43abe187cf9c` and
an exact collector inventory. Both follow-on Java negatives completed real
Java/Kotlin compilation successfully, then failed the Test task with exactly:

```text
Unsupported Java compiler inputs in notification JVM runtime
```

The Test failure is the expected guard behavior, not a passed or failed nested
JUnit case: no selected case executed, no JUnit case report existed, and the
collector inventory remained empty. The outer TestKit rejection assertions passed.

| Follow-on input | Java Git blob | Kotlin Git blob |
| --- | --- | --- |
| Conventional Java | `a3f36e74dfaba681d6cbb6c7a547b871987bb71e` | `720e9a7fe8577eb4880f62f9e89d9af468a07998` |
| Additional Java | `143b56a921c4ef5fbf1b1a9b379053e1272b1b1f` | `43103167b11d8719f116162a513e2f3618efebe8` |

The actual additional source/task input was
`/tmp/junit11250319390287924794/additional-java/fixture/JavaRuntimePayload.java`.
Its emitted class existed under the real Java destination:
`/tmp/junit11250319390287924794/platform/notifications/build/intermediates/javac/debugUnitTest/compileDebugUnitTestJavaWithJavac/classes`.
That exact directory belonged to both `Test.testClassesDirs` and `Test.classpath`
before the guard rejected execution. The fresh original-collector additional
control used the identical source blobs under `/tmp/junit9984195947957565128`;
its selected test loaded the Java class from the corresponding emitted Java
directory and passed. Full paths and classpaths are in the authoritative hosted
`WP002_JAVA_FOLLOWON` / `WP002_JAVA_CONTROL` log lines.

**Conclusion:** exact collector `344863a6` fails closed on the demonstrated
additional-Java-source/runtime case, unlike frozen collector `88eb6653`.
This is bounded compiler/collector proof, not an end-to-end controller bypass
claim, product acceptance, instrumentation execution or device verification.

**Overall hosted result: failed**, independently of the 47/47 build-logic result.
The existing app test
`ChatMapPreviewTest.sharedLoadingFailureAndRetryReachBothCardsWithoutDuplicateRenders[31]`
failed (`app`: 2004 discovered, 2003 passed, one failed); Gradle stopped before
several other mandatory reports existed. No complete scaffold, overall passing
candidate or merge-verification result is claimed. This support assignment does
not modify or diagnose that separately owned app behavior.

WP-002 / android-build-engineer; frozen Swift remains
`db14559b39d32322b06477c6ae676112f583db50`; repository manifest/policy and runner
files were not edited. Reservation scope evolved through the supported typed
same-owner mechanism; historical authorization and locks were preserved.
The exact hosted log is authoritative; no duplicate result bundle is retained.
