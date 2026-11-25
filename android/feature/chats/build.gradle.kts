// AndroidOnly: WP-002 Chats entry shell.
// AndroidOnly: WP-306 Chat list logic + Compose UI; JVM logic tests only (JUnit4 + kotlin-test are already locked).
// AndroidOnly: WP-309 Message reactions, actions, path/repeat details, block/mute, and shared-map UI.
// AndroidOnly: WP-310 Room login/sync and channel create/join/share feature seams and UI.
import org.gradle.api.tasks.PathSensitivity

plugins { id("mesh.android.feature") }

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:maps"))
}

tasks.withType<Test>().configureEach {
    inputs.files(
        fileTree("src/main/kotlin/com/meshcoreone/android/feature/chats/composer/ui") { include("**/*.kt") },
        fileTree("src/main/kotlin/com/meshcoreone/android/feature/chats/list/ui") { include("**/*.kt") },
    ).withPropertyName("chatUiSourceScans").withPathSensitivity(PathSensitivity.RELATIVE)
}

rootProject.tasks.named("verifyScaffoldTests") { dependsOn(":feature:chats:testDebugUnitTest") }
tasks.named("check") { dependsOn("testDebugUnitTest") }
