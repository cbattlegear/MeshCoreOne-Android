// AndroidOnly: WP-002 Tools entry shell; no real or simulated radio operation.
// AndroidOnly: WP-314/WP-315 Tools JVM suites and the approved WP-312 map surface dependency.
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestResult
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    id("mesh.android.feature")
    id("mesh.android.robolectric")
}

dependencies {
    implementation(project(":core:maps"))
}

rootProject.tasks.named("verifyScaffoldTests") { dependsOn(":feature:tools:testDebugUnitTest") }
tasks.named("check") { dependsOn("testDebugUnitTest") }

// AndroidOnly: WP-002 Match app/services failure diagnostics in hosted --quiet runs.
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
                failureLogger.error("WP002_TOOLS_FAILURE|${testDescriptor.className}|${testDescriptor.name}")
                result.exceptions.forEach { failure ->
                    failureLogger.error("WP002 tools failure stack", failure)
                }
            }
        }
    })
}
