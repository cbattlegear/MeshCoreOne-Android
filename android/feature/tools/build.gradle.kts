// AndroidOnly: WP-002 Tools entry shell; no real or simulated radio operation.
// AndroidOnly: WP-314 Tools logic layers' JVM unit suites are registered with the scaffold verify stage.
plugins { id("mesh.android.feature") }

dependencies {
    implementation(project(":core:maps"))
}

rootProject.tasks.named("verifyScaffoldTests") { dependsOn(":feature:tools:testDebugUnitTest") }
tasks.named("check") { dependsOn("testDebugUnitTest") }
