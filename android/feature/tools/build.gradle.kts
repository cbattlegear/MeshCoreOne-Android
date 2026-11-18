// AndroidOnly: WP-002 Tools entry shell; no real or simulated radio operation.
// AndroidOnly: WP-314/WP-315 Tools JVM suites and the approved WP-312 map surface dependency.
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test
import org.gradle.process.CommandLineArgumentProvider

plugins {
    id("mesh.android.feature")
    id("mesh.android.robolectric")
}

dependencies {
    implementation(project(":core:maps"))
}

val toolsSdk37 = configurations.create("testRobolectricSdk37") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}
dependencies.add(toolsSdk37.name, "org.robolectric:android-all-instrumented:17-robolectric-15733970-i7")

val toolsRobolectricSdkDirectory = layout.buildDirectory.dir("robolectric-sdks")
val prepareToolsRobolectricSdks by tasks.registering(Sync::class) {
    from(configurations.named("testRobolectricSdk"))
    from(toolsSdk37)
    into(toolsRobolectricSdkDirectory)
}

class ToolsRobolectricSdkArguments(
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val directory: Provider<Directory>,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> =
        listOf("-Drobolectric.dependency.dir=${directory.get().asFile.absolutePath}")
}

tasks.withType<Test>().configureEach {
    dependsOn(prepareToolsRobolectricSdks)
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
    jvmArgumentProviders.add(ToolsRobolectricSdkArguments(toolsRobolectricSdkDirectory))
}

rootProject.tasks.named("verifyScaffoldTests") { dependsOn(":feature:tools:testDebugUnitTest") }
tasks.named("check") { dependsOn("testDebugUnitTest") }
