// AndroidOnly: WP-202 Admitted Room repository dependencies and complete native assertion hook.
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    id("mesh.android.library")
    id("mesh.android.robolectric")
}
dependencies {
    implementation(project(":core:protocol"))
    implementation(project(":core:model"))
    implementation(project(":core:contracts"))
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
    implementation(libs.androidx.room.runtime)
    implementation(libs.kotlinx.coroutines.core)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":core:runtime"))
    testImplementation(project(":core:services"))
}
dependencyLocking {
    lockFile.set(layout.projectDirectory.file("gradle.lockfile"))
}

tasks.withType<Test>().configureEach {
    systemProperty("wp203.interop.output", layout.buildDirectory.dir("reports/wp203/interop").get().asFile.absolutePath)
    providers.gradleProperty("wp203InteropInputDir").orNull?.let { systemProperty("wp203.interop.input", it) }
    testLogging.quiet {
        events(TestLogEvent.FAILED)
        exceptionFormat = TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
    testLogging.lifecycle {
        events(TestLogEvent.FAILED)
        exceptionFormat = TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
        stackTraceFilters.clear()
    }
}

val repository = rootProject.projectDir.parentFile
val resolvePersistenceDependencies by tasks.registering {
    group = "verification"
    description = "Resolve every owned data configuration strictly and retain the actual component graph."
    notCompatibleWithConfigurationCache("Inspects only the owning data module configuration model")
    val report = layout.buildDirectory.file("reports/wp202/dependency-graphs.tsv")
    outputs.file(report)
    outputs.upToDateWhen { false }
    doLast {
        check(project.path == ":core:data") { "Persistence dependency resolution is restricted to its owner" }
        val resolved = buildString {
            appendLine("module\tconfiguration\tcomponent")
            for (configuration in project.configurations.filter { it.isCanBeResolved }.sortedBy { it.name }) {
                val result = configuration.incoming.resolutionResult
                val unresolved = result.allDependencies.filterIsInstance<UnresolvedDependencyResult>()
                if (unresolved.isNotEmpty()) {
                    throw GradleException(
                        "Unresolved owned graph ${project.path}:${configuration.name}: " +
                            unresolved.joinToString { it.attempted.displayName },
                        unresolved.first().failure,
                    )
                }
                for (component in result.allComponents.sortedBy { it.id.displayName }) {
                    appendLine("${project.path}\t${configuration.name}\t${component.id.displayName}")
                }
            }
        }
        report.get().asFile.apply {
            parentFile.mkdirs()
            writeText(resolved)
        }
    }
}

val verifyPersistenceRepositoryTests by tasks.registering {
    group = "verification"
    description = "Run the actual persistence repository test suite."
    dependsOn("testDebugUnitTest", resolvePersistenceDependencies)
}

rootProject.tasks.named("verifyScaffoldTests") { dependsOn(verifyPersistenceRepositoryTests) }
tasks.named("check") { dependsOn(verifyPersistenceRepositoryTests) }

val verifyBackupTests by tasks.registering {
    group = "verification"
    description = "Run the actual codec and Room backup assertion suites."
    dependsOn("testDebugUnitTest")
}

rootProject.tasks.named("verifyScaffoldTests") { dependsOn(verifyBackupTests) }
tasks.named("check") { dependsOn(verifyBackupTests) }
rootProject.tasks.named("verifyScaffoldTests") {
    dependsOn(":core:services:test", ":core:data:testDebugUnitTest")
}
tasks.named("check") {
    dependsOn("testDebugUnitTest", ":core:services:test")
}
