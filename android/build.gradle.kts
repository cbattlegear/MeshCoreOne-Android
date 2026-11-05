// AndroidOnly: WP-002 Actual graph, test-discovery and schema verification entry points.
buildscript {
    dependencyLocking {
        lockAllConfigurations()
        lockMode.set(LockMode.STRICT)
        lockFile.set(layout.projectDirectory.file("gradle/dependency-locks/root-buildscript.lockfile"))
    }
}

plugins {
    id("mesh.scaffold.root")
}

// AndroidOnly: WP-002 Disposable effective-quiet probe; never merge this diagnostic ref.
check(gradle.startParameter.logLevel == org.gradle.api.logging.LogLevel.QUIET) {
    "WP002_QUIET_CONFIGURATION_MISMATCH|${gradle.startParameter.logLevel}"
}
logger.quiet("WP002_QUIET_CONFIGURATION_e9287928|effective=${gradle.startParameter.logLevel}")

allprojects {
    val lockName = path.removePrefix(":").replace(":", "-").ifEmpty { "root" }
    dependencyLocking {
        lockAllConfigurations()
        lockMode.set(LockMode.STRICT)
        lockFile.set(rootProject.layout.projectDirectory.file("gradle/dependency-locks/$lockName.lockfile"))
    }
    if (this != rootProject) {
        buildscript.dependencyLocking {
            lockAllConfigurations()
            lockMode.set(LockMode.STRICT)
            lockFile.set(rootProject.layout.projectDirectory.file("gradle/dependency-locks/$lockName-buildscript.lockfile"))
        }
    }
}
