// AndroidOnly: WP-207 Pure-JVM runtime, owned dependency lock and complete source-family assertion hook.
import org.gradle.api.tasks.PathSensitivity

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
    inputs.files(fileTree("src/main") { include("**/*.kt") })
        .withPropertyName("runtimeSourceScan").withPathSensitivity(PathSensitivity.RELATIVE)
}

rootProject.tasks.named("verifyScaffoldTests") {
    dependsOn(":core:runtime:test", ":core:data:testDebugUnitTest", ":core:datastore:testDebugUnitTest")
}
tasks.named("check") {
    dependsOn("test", ":core:data:testDebugUnitTest", ":core:datastore:testDebugUnitTest")
}
