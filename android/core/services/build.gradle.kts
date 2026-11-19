// AndroidOnly: WP-002 Android-free service boundary; consumes contracts, never concrete runtime/data.
// AndroidOnly: WP-218 Pure-JVM content safety/location services; no java.net.http/java.awt/javax.imageio.
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestResult

plugins { id("mesh.jvm.library") }
dependencies {
    implementation(project(":core:protocol"))
    implementation(project(":core:model"))
    implementation(project(":core:contracts"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
    // Manual JsonElement DOM for InlineImageDimensionsStore's flat JSON file; no serialization plugin.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}

val repository = rootProject.projectDir.parentFile
rootProject.tasks.named("verifyScaffoldTests") { dependsOn(":core:services:test", ":app:testDebugUnitTest") }
tasks.named("check") { dependsOn("test", ":app:testDebugUnitTest") }

// Failing tests print their identity and stack trace at every Gradle log level, including `--quiet`.
// Diagnostic only; no test behavior or assertions change.
tasks.withType<Test>().configureEach {
    testLogging.quiet {
        events(TestLogEvent.FAILED)
        exceptionFormat = TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
    val failureLogger = logger
    addTestListener(object : TestListener {
        override fun beforeSuite(suite: TestDescriptor) = Unit
        override fun afterSuite(suite: TestDescriptor, result: TestResult) = Unit
        override fun beforeTest(testDescriptor: TestDescriptor) = Unit
        override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {
            if (result.resultType == TestResult.ResultType.FAILURE) {
                failureLogger.error("WP218_SERVICES_FAILURE|${testDescriptor.className}|${testDescriptor.name}")
                result.exceptions.forEach { failure ->
                    failureLogger.error("WP218 services native failure stack", failure)
                }
            }
        }
    })
}
