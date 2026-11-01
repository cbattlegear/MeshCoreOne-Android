// AndroidOnly: WP-304 Shared native UI, real preference claims and owned fail-closed verification.
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
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
    implementation(project(":core:l10n"))
    implementation(project(":core:datastore"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(project(":core:runtime"))
    testImplementation(project(":core:ble"))
    testImplementation(project(":core:services"))
    testImplementation(project(":core:connectivity"))
    // The existing UI seed is 1.8.2; do not silently replace it with the catalog's 1.13.0.
    testImplementation("androidx.activity:activity-compose:1.8.2")
}

dependencyLocking {
    lockFile.set(layout.projectDirectory.file("gradle.lockfile"))
}

val repository = rootProject.projectDir.parentFile
val frozenRootUiLock = rootProject.layout.projectDirectory.file("gradle/dependency-locks/core-ui.lockfile")
val seedText = frozenRootUiLock.asFile.readText()
val seedByConfiguration = linkedMapOf<String, MutableList<String>>()
seedText.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.forEach { line ->
    val parts = line.split("=", limit = 2)
    check(parts.size == 2) { "Malformed frozen UI lock input" }
    if (parts[0] != "empty") for (name in parts[1].split(",")) {
        seedByConfiguration.getOrPut(name) { mutableListOf() }.add(parts[0])
    }
}
configurations.configureEach {
    seedByConfiguration[name]?.let { incumbent -> resolutionStrategy.force(*incumbent.toTypedArray()) }
}
val admittedUnitCompileAlignment = linkedMapOf(
    "androidx.core:core" to "1.16.0",
    "androidx.core:core-ktx" to "1.16.0",
    "androidx.lifecycle:lifecycle-livedata-core" to "2.9.4",
    "androidx.lifecycle:lifecycle-viewmodel" to "2.9.4",
    "androidx.lifecycle:lifecycle-viewmodel-android" to "2.9.4",
    "androidx.lifecycle:lifecycle-viewmodel-ktx" to "2.9.4",
    "androidx.lifecycle:lifecycle-viewmodel-savedstate" to "2.9.4",
).map { (artifact, version) ->
    val source = seedByConfiguration["debugUnitTestRuntimeClasspath"].orEmpty()
        .filter { it.substringBeforeLast(":") == artifact }
    check(source.size == 1 && source.single() == "$artifact:$version") {
        "Admitted unit-compile alignment lacks its exact unique incumbent runtime seed: $artifact"
    }
    source.single()
}
configurations.configureEach {
    if (name == "debugUnitTestCompileClasspath") {
        resolutionStrategy.force(*admittedUnitCompileAlignment.toTypedArray())
    }
}

val sharedUiPlatformSdk37 = configurations.create("sharedUiPlatformSdk37") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}
dependencies.add(sharedUiPlatformSdk37.name, "org.robolectric:android-all-instrumented:17-robolectric-15733970-i7")
val prepareSharedUiPlatformSdks by tasks.registering(Sync::class) {
    from(configurations.named("testRobolectricSdk"))
    from(sharedUiPlatformSdk37)
    into(layout.buildDirectory.dir("shared-ui-platform-sdks"))
}
class SharedUiPlatformArguments(
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    val directory: Provider<Directory>,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> =
        listOf("-Drobolectric.dependency.dir=${directory.get().asFile.absolutePath}")
}

tasks.withType<Test>().configureEach {
    if (name == "testDebugUnitTest") dependsOn(":core:runtime:test", ":core:ble:testDebugUnitTest",
        ":core:services:test", ":core:connectivity:testDebugUnitTest")
    dependsOn(prepareSharedUiPlatformSdks)
    jvmArgumentProviders.add(SharedUiPlatformArguments(layout.buildDirectory.dir("shared-ui-platform-sdks")))
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
    systemProperty("repositoryDirectory", repository.absolutePath)
    systemProperty("sharedUiArtifactDirectory", layout.buildDirectory.dir("reports/wp304/ui").get().asFile.absolutePath)
    forkEvery = 1
    testLogging.quiet {
        events(TestLogEvent.FAILED)
        exceptionFormat = TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
}

val verifySharedUiTests by tasks.registering {
    group = "verification"
    description = "Run the actual shared UI test suite."
    dependsOn("testDebugUnitTest")
}

// Resolve the existing locked API variant, then give the compiler only the contracts
// dependency's reachable artifacts, not the UI's direct dependencies or test classpath.
val protocolConsumerClasspath = providers.provider {
    val configuration = configurations.getByName("debugCompileClasspath")
    val graph = configuration.incoming.resolutionResult
    val contracts = graph.rootComponent.get().dependencies.filterIsInstance<ResolvedDependencyResult>()
        .single { (it.selected.id as? ProjectComponentIdentifier)?.projectPath == ":core:contracts" }
    val reachable = linkedMapOf<org.gradle.api.artifacts.component.ComponentIdentifier, ResolvedComponentResult>()
    fun visit(component: ResolvedComponentResult) {
        if (reachable.putIfAbsent(component.id, component) != null) return
        check(component.dependencies.none { it is UnresolvedDependencyResult }) {
            "The isolated contracts API dependency graph is unresolved"
        }
        component.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { visit(it.selected) }
    }
    visit(contracts.selected)
    val projects = reachable.keys.filterIsInstance<ProjectComponentIdentifier>().map { it.projectPath }.toSet()
    check(projects == setOf(":core:contracts", ":core:model", ":core:protocol")) {
        "Unexpected projects in the isolated contracts API: $projects"
    }
    configuration.incoming.artifactView {
        componentFilter { it in reachable }
        attributes.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, ArtifactTypeDefinition.JAR_TYPE)
    }.files
}
val compileProtocolApiConsumer by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Compile a Kotlin consumer using only the contracts API's locked transitive artifacts."
    notCompatibleWithConfigurationCache("Selects the contracts API subgraph from the existing locked compile graph")
    dependsOn(protocolConsumerClasspath)
    classpath = configurations.getByName("kotlinCompilerClasspath")
    mainClass.set("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler")
    val source = layout.projectDirectory.file("verification/protocol-api-consumer/ProtocolApiConsumer.kt")
    val destination = layout.buildDirectory.dir("protocol-api-consumer")
    inputs.file(source)
    inputs.files(protocolConsumerClasspath)
    outputs.dir(destination)
    doFirst {
        args("-no-stdlib", "-no-reflect", "-Xjdk-release=17", "-jvm-target", "17",
            "-classpath", protocolConsumerClasspath.get().asPath,
            "-d", destination.get().asFile.absolutePath, source.asFile.absolutePath)
    }
}
tasks.withType<Test>().configureEach {
    dependsOn(compileProtocolApiConsumer)
    inputs.dir(layout.buildDirectory.dir("protocol-api-consumer"))
        .withPropertyName("protocolApiConsumerClasses").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("protocolApiConsumerDirectory", layout.buildDirectory.dir("protocol-api-consumer").get().asFile.absolutePath)
}
rootProject.tasks.named("verifyScaffoldTests") { dependsOn(verifySharedUiTests) }
tasks.named("check") { dependsOn(verifySharedUiTests) }

val resolveSharedUiDependencies by tasks.registering {
    group = "verification"
    description = "Resolve only owned UI configurations, preserving every frozen incumbent version; no ROOT or consumer lock writes."
    notCompatibleWithConfigurationCache("Inspects exactly the current owned module configurations")
    doLast {
        val output = layout.buildDirectory.file("reports/wp304/dependency-graphs.tsv").get().asFile
        output.parentFile.mkdirs()
        val ownedConfigurations = configurations.filter { it.isCanBeResolved }.sortedBy { it.name }
        layout.buildDirectory.file("reports/wp304/resolution-configurations.txt").get().asFile
            .writeText(ownedConfigurations.joinToString("\n", postfix = "\n") { it.name })
        var failed = false
        output.printWriter().use { writer ->
            writer.println("module\tconfiguration\tkind\tcomponent")
            ownedConfigurations.forEach { configuration ->
                val graph = configuration.incoming.resolutionResult
                graph.allComponents.sortedBy { it.id.displayName }.forEach {
                    writer.println("${project.path}\t${configuration.name}\tselected\t${it.id.displayName}")
                }
                graph.allDependencies.filterIsInstance<UnresolvedDependencyResult>().forEach {
                    failed = true
                    writer.println("${project.path}\t${configuration.name}\tunresolved\t${it.attempted.displayName}")
                    writer.println("${project.path}\t${configuration.name}\tfailure\t${it.failure.message?.replace('\n', ' ')?.replace('\t', ' ')}")
                }
            }
        }
        val alignment = layout.buildDirectory.file("reports/wp304/unit-compile-alignment.tsv").get().asFile
        val compileGraph = configurations.getByName("debugUnitTestCompileClasspath").incoming.resolutionResult
        alignment.printWriter().use { writer ->
            writer.println("artifact\tsourceConfiguration\tsourceCoordinate\tconfiguration\trequested\tselected")
            for (dependency in compileGraph.allDependencies.filterIsInstance<ResolvedDependencyResult>()) {
                val requested = dependency.requested as? ModuleComponentSelector ?: continue
                val artifact = "${requested.group}:${requested.module}"
                val source = admittedUnitCompileAlignment.singleOrNull { it.substringBeforeLast(":") == artifact } ?: continue
                val selected = dependency.selected.id as? ModuleComponentIdentifier
                    ?: throw GradleException("Admitted compile alignment selected a non-module component")
                writer.println("$artifact\tdebugUnitTestRuntimeClasspath\t$source\tdebugUnitTestCompileClasspath\t${requested.displayName}\t${selected.displayName}")
            }
        }
        if (failed) throw GradleException("Owned shared UI resolution failed; complete graph retained at ${output.name}")
    }
}
