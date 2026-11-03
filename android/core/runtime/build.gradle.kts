// AndroidOnly: WP-207 Pure-JVM runtime, owned dependency lock and complete source-family assertion hook.
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins { id("mesh.jvm.library") }
dependencies {
    implementation(project(":core:protocol"))
    implementation(project(":core:model"))
    implementation(project(":core:contracts"))
    testImplementation(libs.kotlinx.coroutines.test)
}

dependencyLocking {
    lockFile.set(layout.projectDirectory.file("gradle.lockfile"))
}

val repository = rootProject.projectDir.parentFile
tasks.withType<Test>().configureEach {
    systemProperty("repositoryDirectory", repository.absolutePath)
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
                failureLogger.error("WP207_RUNTIME_FAILURE|${testDescriptor.className}|${testDescriptor.name}")
                result.exceptions.forEach { failure ->
                    failureLogger.error("WP207 runtime failure stack", failure)
                }
            }
        }
    })
}

rootProject.tasks.named("verifyScaffoldTests") {
    dependsOn(":core:runtime:test", ":core:data:testDebugUnitTest", ":core:datastore:testDebugUnitTest")
}
tasks.named("check") {
    dependsOn("test", ":core:data:testDebugUnitTest", ":core:datastore:testDebugUnitTest")
}
