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
is involved. Results are pending exact-support-commit hosted execution.
