// AndroidOnly: WP-005 Pinned conversion, compiled-resource assertions and module-owned dependency locks.
plugins {
    id("mesh.android.library")
    id("mesh.android.robolectric")
}

dependencies { implementation(project(":core:model")) }

dependencyLocking {
    lockFile.set(layout.projectDirectory.file("gradle/dependency-locks/core-l10n.lockfile"))
}

val repository = rootProject.projectDir.parentFile
val converter = repository.resolve("tools").resolve("android-port").resolve("l10n_convert.py")

val verifyL10nConversion by tasks.registering(Exec::class) {
    group = "verification"
    description = "Fail on source/resource/key-map drift and require nonzero converter assertions."
    workingDir(repository)
    commandLine("python", converter.absolutePath, "--check", "--self-test")
}

val verifyL10nTests by tasks.registering(Exec::class) {
    group = "verification"
    description = "Require the original localization families and compiled-resource JUnit evidence."
    dependsOn("testDebugUnitTest")
    workingDir(repository)
    commandLine(
        "python", converter.absolutePath, "--android-tests-only", "--verify-android-tests",
        layout.buildDirectory.dir("test-results/testDebugUnitTest").get().asFile.absolutePath,
    )
}

rootProject.tasks.named("verifyScaffoldTests") { dependsOn(verifyL10nTests) }
tasks.named("check") { dependsOn(verifyL10nTests) }
