// AndroidOnly: WP-002 Nodes entry shell; auxiliary routes go through app registration.
// AndroidOnly: WP-311 Nodes logic layer (state holders, path editing, share/scan logic) and its JVM unit suite.
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestResult
import org.gradle.process.CommandLineArgumentProvider
import java.io.PrintWriter
import java.io.StringWriter

plugins {
    id("mesh.android.feature")
    id("mesh.android.robolectric")
}

dependencies {
    implementation(project(":core:maps"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)
}

val api37TestSdk = configurations.create("testRobolectricSdk37") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}
dependencies.add(api37TestSdk.name, "org.robolectric:android-all-instrumented:17-robolectric-15733970-i7")

val robolectricSdkDirectory = layout.buildDirectory.dir("robolectric-sdks")
val prepareRobolectricSdks by tasks.registering(Sync::class) {
    from(configurations.named("testRobolectricSdk"))
    from(api37TestSdk)
    into(robolectricSdkDirectory)
}

class NodesRobolectricSdkArguments(
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val directory: Provider<Directory>,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> =
        listOf("-Drobolectric.dependency.dir=${directory.get().asFile.absolutePath}")
}

tasks.withType<Test>().configureEach {
    val failureLogger = logger
    addTestListener(object : TestListener {
        override fun beforeSuite(suite: TestDescriptor) = Unit
        override fun afterSuite(suite: TestDescriptor, result: TestResult) = Unit
        override fun beforeTest(testDescriptor: TestDescriptor) = Unit
        override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {
            if (result.resultType == TestResult.ResultType.FAILURE) {
                failureLogger.error("WP311_NODES_FAILURE|${testDescriptor.className}|${testDescriptor.name}")
                result.exceptions.forEach { failure ->
                    val stack = StringWriter()
                    PrintWriter(stack).use { failure.printStackTrace(it) }
                    failureLogger.error(stack.toString())
                }
            }
        }
    })
    dependsOn(prepareRobolectricSdks)
    testLogging.exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
    jvmArgumentProviders.add(NodesRobolectricSdkArguments(robolectricSdkDirectory))
}

rootProject.tasks.named("verifyScaffoldTests") { dependsOn(":feature:nodes:testDebugUnitTest") }
tasks.named("check") { dependsOn("testDebugUnitTest") }
