// AndroidOnly: WP-002 Isolated convention build with the same exact catalog.
pluginManagement {
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

dependencyResolutionManagement {
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
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
include(":convention")
