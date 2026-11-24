// AndroidOnly: WP-205 Native GATT transport; permissions and process ownership belong to the host.
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test
import org.gradle.process.CommandLineArgumentProvider

plugins {
    id("mesh.android.library")
    id("mesh.android.robolectric")
}
dependencies {
    implementation(project(":core:protocol"))
    implementation(project(":core:model"))
    implementation(project(":core:contracts"))
    testImplementation(project(":core:testing"))
}

val additionalTestSdks = listOf(
    "32" to "12.1-robolectric-8229987-i7",
    "33" to "13-robolectric-9030017-i7",
    "37" to "17-robolectric-15733970-i7",
).map { (api, version) ->
    configurations.create("testRobolectricSdk$api") {
        isCanBeConsumed = false
        isCanBeResolved = true
        isTransitive = false
    }.also {
        dependencies.add(it.name, "org.robolectric:android-all-instrumented:$version")
    }
}

val verifiedSdkDirectory = layout.buildDirectory.dir("robolectric-sdks")
val prepareTestSdks by tasks.registering(Sync::class) {
    from(configurations.named("testRobolectricSdk"))
    additionalTestSdks.forEach { from(it) }
    into(verifiedSdkDirectory)
}

class BleTestSdkArguments(
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val directory: Provider<Directory>,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> =
        listOf("-Drobolectric.dependency.dir=${directory.get().asFile.absolutePath}")
}

tasks.withType<Test>().configureEach {
    dependsOn(prepareTestSdks)
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
    jvmArgumentProviders.add(BleTestSdkArguments(verifiedSdkDirectory))
}

rootProject.tasks.named("verifyScaffoldTests") { dependsOn(":core:ble:testDebugUnitTest") }
tasks.named("check") { dependsOn("testDebugUnitTest") }
