// AndroidOnly: WP-301 Native themes, actual preference consumer, pinned tests and module-owned verification.
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestResult
import org.gradle.process.CommandLineArgumentProvider

plugins {
    id("mesh.android.library")
    id("mesh.android.compose")
    id("mesh.android.robolectric")
}
dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:l10n"))
    implementation(project(":core:datastore"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.activity.compose)
}

dependencyLocking {
    lockFile.set(layout.projectDirectory.file("gradle.lockfile"))
}

val repository = rootProject.projectDir.parentFile
val converter = repository.resolve("tools").resolve("android-port").resolve("theme_convert.py")
val themeEvidence = repository.resolve("docs").resolve("android").resolve("evidence").resolve("WP-301")
val packagingInspector = themeEvidence.resolve("verify_packaging.py")

val themePlatformSdk37 = configurations.create("themePlatformSdk37") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}
dependencies.add(themePlatformSdk37.name, "org.robolectric:android-all-instrumented:17-robolectric-15733970-i7")
val prepareThemePlatformSdks by tasks.registering(Sync::class) {
    from(configurations.named("testRobolectricSdk"))
    from(themePlatformSdk37)
    into(layout.buildDirectory.dir("theme-platform-sdks"))
}
class ThemePlatformSdkArguments(
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    val directory: Provider<Directory>,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = listOf("-Drobolectric.dependency.dir=${directory.get().asFile.absolutePath}")
}
tasks.withType<Test>().configureEach {
    dependsOn(prepareThemePlatformSdks)
    jvmArgumentProviders.add(ThemePlatformSdkArguments(layout.buildDirectory.dir("theme-platform-sdks")))
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
    systemProperty("repositoryDirectory", repository.absolutePath)
    systemProperty("themeArtifactDirectory", layout.buildDirectory.dir("reports/wp301/ui").get().asFile.absolutePath)
    forkEvery = 1
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
                failureLogger.error("WP301_NATIVE_FAILURE|${testDescriptor.className}|${testDescriptor.name}")
                result.exceptions.forEach { failure ->
                    failureLogger.error("WP301 native failure stack", failure)
                }
            }
        }
    })
}

val verifyThemeConversion by tasks.registering(Exec::class) {
    group = "verification"
    description = "Verify all frozen theme/color/icon/recovery inputs and nonzero fail-closed converter cases."
    workingDir(repository)
    commandLine("python", converter.absolutePath, "--check", "--self-test")
}
tasks.named("preBuild") { dependsOn(verifyThemeConversion) }
val verifyThemeNotices by tasks.registering(Exec::class) {
    group = "verification"
    description = "Preserve exact frozen GPL/MIT bytes; normalize only byte-equivalent owned Windows notices."
    workingDir(repository)
    commandLine("python", packagingInspector.absolutePath, "--normalize", "--self-test")
}
tasks.named("preBuild") { dependsOn(verifyThemeNotices) }

rootProject.tasks.named("verifyScaffoldTests") { dependsOn(":core:designsystem:testDebugUnitTest") }
tasks.named("check") { dependsOn("testDebugUnitTest") }
val verifyThemePackaging by tasks.registering(Exec::class) {
    group = "verification"
    description = "Inspect actual debug APK identity, exact theme notices/source map and test-fixture absence."
    dependsOn(":app:assembleDebug")
    workingDir(repository)
    commandLine("python", packagingInspector.absolutePath, "--self-test", "--apk",
        rootProject.project(":app").layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile.absolutePath)
}
rootProject.tasks.named("verifyScaffoldTests") { dependsOn(verifyThemePackaging) }
tasks.named("check") { dependsOn(verifyThemePackaging) }

val resolveThemeDependencies by tasks.registering {
    group = "verification"
    description = "Resolve only owned module configurations; no consumer lock writes."
    notCompatibleWithConfigurationCache("Reads the current owned Gradle configuration model")
    doLast {
        val output = layout.buildDirectory.file("reports/wp301/owned-dependency-graphs.tsv").get().asFile
        output.parentFile.mkdirs()
        output.writeText(buildString {
            appendLine("module\tconfiguration\tcomponent")
            configurations.filter { it.isCanBeResolved }.sortedBy { it.name }.forEach { configuration ->
                val resolution = configuration.incoming.resolutionResult
                val unresolved = resolution.allDependencies.filterIsInstance<UnresolvedDependencyResult>()
                if (unresolved.isNotEmpty()) throw GradleException(
                    "Owned theme graph is unresolved: ${configuration.name}", unresolved.first().failure,
                )
                resolution.allComponents.sortedBy { it.id.displayName }.forEach {
                    appendLine("${project.path}\t${configuration.name}\t${it.id.displayName}")
                }
            }
        })
    }
}

val inspectThemeConsumerGraphs by tasks.registering {
    group = "verification"
    description = "Read-only strict graph/missing-lock evidence for exactly ten affected consumers, never write-locks."
    notCompatibleWithConfigurationCache("Inspects exactly the proposed consumer configurations")
    doLast {
        check(!gradle.startParameter.isWriteDependencyLocks) { "Consumer inspection must never run with --write-locks" }
        val modules = listOf(":core:ui", ":core:maps", ":feature:onboarding", ":feature:chats", ":feature:nodes",
            ":feature:remotenodes", ":feature:map", ":feature:tools", ":feature:settings", ":platform:widgets")
        val names = listOf("debugRuntimeClasspath", "releaseRuntimeClasspath", "debugUnitTestRuntimeClasspath",
            "debugAndroidTestRuntimeClasspath", "debugLintChecksClasspath", "releaseLintChecksClasspath",
            "debugUnitTestLintChecksClasspath", "debugAndroidTestLintChecksClasspath")
        val output = layout.buildDirectory.file("reports/wp301/consumer-dependency-graphs.tsv").get().asFile
        output.parentFile.mkdirs()
        var failed = false
        output.printWriter().use { writer ->
            writer.println("module\tconfiguration\tkind\tcomponent")
            for (module in modules) for (name in names) {
                val configuration = rootProject.project(module).configurations.getByName(name)
                val resolution = configuration.incoming.resolutionResult
                for (component in resolution.allComponents.sortedBy { it.id.displayName }) {
                    writer.println("$module\t$name\tselected\t${component.id.displayName}")
                }
                for (problem in resolution.allDependencies.filterIsInstance<UnresolvedDependencyResult>()) {
                    failed = true
                    writer.println("$module\t$name\tunresolved\t${problem.attempted.displayName}")
                    writer.println("$module\t$name\tfailure\t${problem.failure.message?.replace('\n', ' ')?.replace('\t', ' ')}")
                }
            }
        }
        if (failed) throw GradleException("Strict consumer graphs are BLOCKED; actual missing-lock evidence retained in ${output.name}")
    }
}

val resolveAdmittedThemeConsumerGraphs by tasks.registering {
    group = "verification"
    description = "Regenerate only the ten coordinator-admitted runtime/lint configurations, preserving every old version."
    notCompatibleWithConfigurationCache("Resolves precisely the admitted consumer configuration model")
    doLast {
        check(gradle.startParameter.isWriteDependencyLocks) { "Admitted regeneration requires explicit --write-locks" }
        val modules = listOf(":core:ui", ":core:maps", ":feature:onboarding", ":feature:chats", ":feature:nodes",
            ":feature:remotenodes", ":feature:map", ":feature:tools", ":feature:settings", ":platform:widgets")
        val names = listOf("debugRuntimeClasspath", "releaseRuntimeClasspath", "debugUnitTestRuntimeClasspath",
            "debugAndroidTestRuntimeClasspath", "debugLintChecksClasspath", "releaseLintChecksClasspath",
            "debugUnitTestLintChecksClasspath", "debugAndroidTestLintChecksClasspath")
        val output = layout.buildDirectory.file("reports/wp301/admitted-consumer-graphs.tsv").get().asFile
        output.parentFile.mkdirs()
        output.printWriter().use { writer ->
            writer.println("module\tconfiguration\tcomponent")
            for (module in modules) {
                val lock = rootProject.projectDir.resolve("gradle").resolve("dependency-locks")
                    .resolve(module.removePrefix(":").replace(":", "-") + ".lockfile")
                val old = lock.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map {
                    val parts = it.split("=", limit = 2)
                    check(parts.size == 2) { "Malformed admitted existing lock" }
                    parts[0] to parts[1].split(",").toSet()
                }
                for (name in names) {
                    val configuration = rootProject.project(module).configurations.getByName(name)
                    old.filter { it.first != "empty" && name in it.second }.forEach {
                        configuration.resolutionStrategy.force(it.first)
                    }
                    val result = configuration.incoming.resolutionResult
                    val unresolved = result.allDependencies.filterIsInstance<UnresolvedDependencyResult>()
                    if (unresolved.isNotEmpty()) throw GradleException(
                        "Admitted consumer unresolved: $module:$name", unresolved.first().failure,
                    )
                    result.allComponents.sortedBy { it.id.displayName }.forEach {
                        writer.println("$module\t$name\t${it.id.displayName}")
                    }
                }
            }
        }
    }
}
