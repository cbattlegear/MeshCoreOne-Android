// AndroidOnly: WP-002 Chats entry shell.
// AndroidOnly: WP-306 Chat list logic + Compose UI; JVM logic tests only (JUnit4 + kotlin-test are already locked).
// AndroidOnly: WP-309 Message reactions, actions, path/repeat details, block/mute, and shared-map UI.
// AndroidOnly: WP-310 Room login/sync and channel create/join/share feature seams and UI.
plugins { id("mesh.android.feature") }

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(project(":core:model"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:maps"))
}

rootProject.tasks.named("verifyScaffoldTests") { dependsOn(":feature:chats:testDebugUnitTest") }
tasks.named("check") { dependsOn("testDebugUnitTest") }
