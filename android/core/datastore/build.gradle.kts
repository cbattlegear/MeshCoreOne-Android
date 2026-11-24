// AndroidOnly: WP-204 Real DataStore/Keystore persistence and source-case verification.
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.CommandLineArgumentProvider

plugins {
    id("mesh.android.library")
    id("mesh.android.robolectric")
}
dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:contracts"))
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":core:runtime"))
}

dependencyLocking {
    lockFile.set(layout.projectDirectory.file("gradle.lockfile"))
}

val reviewedPlatformSdks = listOf(
    "32" to "12.1-robolectric-8229987-i7",
    "33" to "13-robolectric-9030017-i7",
    "37" to "17-robolectric-15733970-i7",
).map { (api, version) ->
    configurations.create("reviewedPlatformSdk$api") {
        isCanBeConsumed = false
        isCanBeResolved = true
        isTransitive = false
    }.also { dependencies.add(it.name, "org.robolectric:android-all-instrumented:$version") }
}
val prepareReviewedPlatformSdks by tasks.registering(Sync::class) {
    from(configurations.named("testRobolectricSdk"))
    reviewedPlatformSdks.forEach { from(it) }
    into(layout.buildDirectory.dir("reviewed-platform-sdks"))
}

class DatastorePlatformSdkArguments(
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val directory: Provider<Directory>,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> =
        listOf("-Drobolectric.dependency.dir=${directory.get().asFile.absolutePath}")
}

val repository = rootProject.projectDir.parentFile
val admittedConsumerConfigurations = listOf(
    "debugRuntimeClasspath", "releaseRuntimeClasspath",
    "debugUnitTestRuntimeClasspath", "debugAndroidTestRuntimeClasspath",
    "debugLintChecksClasspath", "releaseLintChecksClasspath",
    "debugUnitTestLintChecksClasspath", "debugAndroidTestLintChecksClasspath",
)

val resolvePreferenceDependencies by tasks.registering {
    group = "verification"
    description = "Resolve only owned DataStore graphs and the eight admitted app/data runtime/lint lock configurations."
    notCompatibleWithConfigurationCache("Inspects exactly the coordinator-admitted consumer configuration model")
    doLast {
        val output = layout.buildDirectory.file("reports/wp204/dependency-graphs.tsv").get().asFile
        output.parentFile.mkdirs()
        output.writeText(buildString {
            appendLine("module\tconfiguration\tcomponent")
            for (module in listOf(project, rootProject.project(":app"), rootProject.project(":core:data"))) {
                val configurations = if (module == project) {
                    module.configurations.filter { it.isCanBeResolved }.sortedBy { it.name }
                } else admittedConsumerConfigurations.map { name ->
                    module.configurations.getByName(name).also {
                        check(it.isCanBeResolved) { "Admitted configuration is not resolvable: ${module.path}:$name" }
                    }
                }
                for (configuration in configurations) {
                    val result = configuration.incoming.resolutionResult
                    val unresolved = result.allDependencies.filterIsInstance<UnresolvedDependencyResult>()
                    if (unresolved.isNotEmpty()) {
                        throw GradleException(
                            "Unresolved admitted graph ${module.path}:${configuration.name}: " +
                                unresolved.joinToString { it.attempted.displayName },
                            unresolved.first().failure,
                        )
                    }
                    for (component in result.allComponents.sortedBy { it.id.displayName }) {
                        appendLine("${module.path}\t${configuration.name}\t${component.id.displayName}")
                    }
                }
            }
        })
    }
}

tasks.withType<Test>().configureEach {
    dependsOn(prepareReviewedPlatformSdks)
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
    jvmArgumentProviders.add(DatastorePlatformSdkArguments(layout.buildDirectory.dir("reviewed-platform-sdks")))
    systemProperty("repositoryDirectory", repository.absolutePath)
}

val verifyPreferenceNotices by tasks.registering(Exec::class) {
    group = "verification"
    description = "Require independently verified notice bytes and normalize only the admitted Windows app notice."
    workingDir(repository)
    commandLine(
        "python", repository.resolve("docs").resolve("android").resolve("evidence")
            .resolve("WP-204").resolve("verify_notices.py").absolutePath, "--normalize",
    )
}

tasks.named("preBuild") { dependsOn(verifyPreferenceNotices) }
gradle.projectsEvaluated {
    rootProject.project(":app").tasks.named("mergeDebugAssets") { dependsOn(verifyPreferenceNotices) }
}

val verifyPreferenceTests by tasks.registering {
    group = "verification"
    description = "Run the actual persistence preference test suite."
    dependsOn("testDebugUnitTest")
}

val verifyPreferencePackaging by tasks.registering(Exec::class) {
    group = "verification"
    description = "Inspect actual APK backup exclusions, exact dependency notices and test/secret boundaries."
    dependsOn(":app:assembleDebug")
    workingDir(repository)
    commandLine(
        "python", repository.resolve("docs").resolve("android").resolve("evidence")
            .resolve("WP-204").resolve("inspect_packaging.py").absolutePath, "--self-test",
    )
}

rootProject.tasks.named("verifyScaffoldTests") { dependsOn(verifyPreferenceTests) }
rootProject.tasks.named("verifyScaffoldTests") { dependsOn(verifyPreferencePackaging) }
tasks.named("check") { dependsOn(verifyPreferenceTests, verifyPreferencePackaging) }
