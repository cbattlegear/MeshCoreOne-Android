// AndroidOnly: WP-002 Settings entry shell; WP-318 adds appearance, backup, about and support logic (JVM tests: JUnit4 + kotlin-test already locked).
import org.gradle.api.tasks.PathSensitivity

plugins { id("mesh.android.feature") }

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:designsystem"))
}

tasks.withType<Test>().configureEach {
    inputs.file("src/main/kotlin/com/meshcoreone/android/feature/settings/device/ui/AdvancedSettingsScreen.kt")
        .withPropertyName("settingsSourceScan").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(fileTree(rootProject.file("core/l10n/src/main/res")) { include("values*/l10n_strings.xml") })
        .withPropertyName("settingsLocalizationInputs").withPathSensitivity(PathSensitivity.RELATIVE)
}

rootProject.tasks.named("verifyScaffoldTests") { dependsOn(":feature:settings:testDebugUnitTest") }
tasks.named("check") { dependsOn("testDebugUnitTest") }
