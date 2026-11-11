// AndroidOnly: WP-312 Shared map contracts and the user-approved MapLibre Native adapter.
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.CommandLineArgumentProvider

plugins {
    id("mesh.android.library")
    id("mesh.android.compose")
    id("mesh.android.robolectric")
}
dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:contracts"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:ui"))
    implementation(project(":core:l10n"))
    implementation(libs.maplibre.android)
    implementation(libs.kotlinx.coroutines.core)
}

class MapPlatformSdkArguments(
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    val directory: Provider<Directory>,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> =
        listOf("-Drobolectric.dependency.dir=${directory.get().asFile.absolutePath}")
}
tasks.withType<Test>().configureEach {
    dependsOn(":core:designsystem:prepareThemePlatformSdks")
    jvmArgumentProviders.add(MapPlatformSdkArguments(
        rootProject.project(":core:designsystem").layout.buildDirectory.dir("theme-platform-sdks"),
    ))
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
}

rootProject.tasks.named("verifyScaffoldTests") { dependsOn(":core:maps:testDebugUnitTest") }
tasks.named("check") { dependsOn("testDebugUnitTest") }
