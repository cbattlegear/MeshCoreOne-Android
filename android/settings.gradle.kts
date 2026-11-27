// AndroidOnly: WP-002 Reproducible, supervised-draft module registration.
pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        if (System.getenv("CI") == "true") {
            maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2/") }
            gradlePluginPortal()
            mavenCentral()
        } else {
            mavenCentral()
            gradlePluginPortal()
        }
    }
}

buildscript {
    dependencyLocking {
        lockAllConfigurations()
        lockMode.set(LockMode.STRICT)
        lockFile.set(layout.settingsDirectory.file("gradle/dependency-locks/settings.lockfile"))
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        if (System.getenv("CI") == "true") {
            maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2/") }
        }
        mavenCentral()
    }
}

rootProject.name = "MeshCoreOneAndroid"

include(
    ":app",
    ":core:protocol",
    ":core:model",
    ":core:contracts",
    ":core:database",
    ":core:datastore",
    ":core:data",
    ":core:ble",
    ":core:connectivity",
    ":core:runtime",
    ":core:services",
    ":core:observer",
    ":core:designsystem",
    ":core:ui",
    ":core:maps",
    ":core:l10n",
    ":core:testing",
    ":feature:onboarding",
    ":feature:chats",
    ":feature:nodes",
    ":feature:remotenodes",
    ":feature:map",
    ":feature:tools",
    ":feature:settings",
    ":platform:notifications",
    ":platform:widgets",
    ":platform:shortcuts",
    ":platform:translation",
    ":tools:meshcli",
    ":benchmark",
    ":scaffold:room-verification",
)
