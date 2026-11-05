# WP-002 Java compiler input control

This bounded support fixture investigates #516 / #580, based on frozen candidate
`2b7fde5df2cded0e852ba02a863eb4ae95bc880a`. It does not repair the WP-003 collector,
change runner policy, or attest notification product/device acceptance.

`JavaCompilerInventoryTest` uses Gradle TestKit and the real `mesh.android.library`
plugin, Android Java/Kotlin compilers and `:platform:notifications:testDebugUnitTest`.
It executes the candidate's unchanged `verify_candidate_task_graph.gradle`
(Git blob `88eb66536d20a34118686a498449d9b04b470399`), not an inventory substitute.
The fixture does not impersonate an approved notification build profile: it isolates
the compiler/collector task graph, not the controller's immutable-profile admission.

The positive control places Java in `src/test/java`; the adversarial control adds
`additional-java/fixture/JavaRuntimePayload.java` directly to
`compileDebugUnitTestJavaWithJavac.source`, outside conventional source roots.
Both select `fixture.SelectedRuntimeTest.selectedTestLoadsJavaInput`, which loads
the Java class reflectively and asserts its independently specified marker.

Assertions require successful real Java/Kotlin/Test task outcomes, actual Java
source and task-input membership, the emitted class, Java output membership in
both `Test.testClassesDirs` and runtime classpath, the runtime code-source URL,
and exactly one passed/unskipped named JUnit case. The unchanged collector must
emit the exact Kotlin path/blob inventory. Java omission is an explicit
characterization assertion, not a passing correctness verdict for that collector.

Run only through the supported hosted scaffold route (`ci.py run --stage scaffold`,
which includes `verifyScaffoldTests` and the included build's `:convention:test`).
The nested controls use the provisioned SDK/JDK and verified, already resolved
Gradle cache, offline, with one worker and fresh fixture outputs. No dependencies,
locks, SDK terms, execution locks or installed runtime are changed.
`WP002_JAVA_CONTROL` lines in the exact-commit hosted job log expose source blobs,
real compiler/runtime paths, collector records and named-case outcomes. That log
is authoritative; no duplicate CI-result bundle is retained.

## Observed result

[Hosted run 42035123447](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/42035123447)
**passed** at support commit `82dd07361203c47a6957f8e9c4dde27893f2a580`,
tree `d8af2aa0c888281819f3857a8450d2e80b33689a`. The supported scaffold route
reported build-logic **44 discovered/passed, zero failures/errors/skips**, including:

- `conventional Java input reaches the Android test runtime beside inventoried Kotlin()`
- `additional JavaCompile input reaches the Android test runtime without collector rejection()`

Each nested execution passed the single named
`fixture.SelectedRuntimeTest.selectedTestLoadsJavaInput` case, without skips.
All three required compiler/Test tasks completed successfully in each fixture.
The exact Git blob identities of the fixture inputs were:

| Control | Java input blob | Kotlin input blob |
| --- | --- | --- |
| Conventional | `a3f36e74dfaba681d6cbb6c7a547b871987bb71e` | `720e9a7fe8577eb4880f62f9e89d9af468a07998` |
| Additional | `143b56a921c4ef5fbf1b1a9b379053e1272b1b1f` | `43103167b11d8719f116162a513e2f3618efebe8` |

The additional control's actual source/task input was
`/tmp/junit8494187976992520622/additional-java/fixture/JavaRuntimePayload.java`.
Its Java destination was
`/tmp/junit8494187976992520622/platform/notifications/build/intermediates/javac/debugUnitTest/compileDebugUnitTestJavaWithJavac/classes`.
That directory contained `fixture/JavaRuntimePayload.class`, belonged to both
`Test.testClassesDirs` and `Test.classpath`, and was the runtime class's
`ProtectionDomain.codeSource` location. The collector emitted only the exact
Kotlin input path/blob listed above; it did not include or reject the Java input.
The conventional control established the same Java output/runtime relationship.
The hosted log preserves complete compiler source/output and Test classpath
identities for both controls.

This is a demonstrated omission in the **frozen collector**, not proof of an
end-to-end controller admission bypass. The conventional Java input is separately
rejected by `_notification_jvm_test_sources`; this fixture does not invoke that
reader. The additional input demonstrates that an actual Java compiler source
outside conventional roots can reach the selected test runtime while invisible
to the Kotlin-only collector. Its lifecycle placement matters: AGP overwrites an
early `configureEach` source addition, whereas adding it after project evaluation
preserves the input. No collector redesign is included.

Earlier hosted attempts remain authoritative failed attempts, not transferred
success: `42029420050` and `42031179393` failed fixture task-registration timing;
`42032901199` passed the conventional control but its early additional source
was overwritten (javac `NO-SOURCE`, runtime `ClassNotFoundException`).

At final observation #580 had advanced to
`06c2c5eb7444f3a4a252052ced06507be534aff3`. This result is not rebound to that
successor: it tests the frozen collector blob above from `2b7fde5d`.
The coordinator received the confirmed finding for relay to its current owner.
Independent review and any successor verification remain separate; no local
Gradle, notification product/device acceptance or #567/bootstrap work occurred.
