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

Execution is pending until the support commit's hosted run finishes. Source
inspection alone is not evidence that the extra Java input reaches the runtime.
