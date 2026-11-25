// AndroidOnly: WP-002 Consistent JVM/Android/Compose/Room scaffold configuration.
package com.meshcoreone.buildlogic

import androidx.room.gradle.RoomExtension
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.gradle.process.CommandLineArgumentProvider

internal fun Project.library(alias: String) =
    extensions.getByType<VersionCatalogsExtension>().named("libs").findLibrary(alias).orElseThrow {
        IllegalArgumentException("Missing pinned catalog library: $alias")
    }

private fun Project.configureTests(jvm: Boolean) {
    tasks.withType<Test>().configureEach {
        if (jvm) useJUnitPlatform() else useJUnit()
        failOnNoDiscoveredTests.set(true)
        maxParallelForks = 1
        maxHeapSize = providers.gradleProperty("scaffoldTestHeap").getOrElse("256m")
        providers.gradleProperty("scaffoldTestJvmArgs").orNull?.let {
            jvmArgs(it.split(" ").filter(String::isNotBlank))
        }
    }
}

private fun Project.configureAndroid(extension: CommonExtension) {
    extension.apply {
        namespace = "com.meshcoreone.android" + path.replace(":", ".").replace("-", "")
        compileSdk {
            version = release(37) {
                minorApiLevel = 2
            }
        }
        buildToolsVersion = "37.0.0"
        defaultConfig.minSdk = 31
        compileOptions.apply {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
        buildFeatures.apply {
            buildConfig = false
            aidl = false
            shaders = false
        }
        testOptions.unitTests.isIncludeAndroidResources = true
        lint.ignoreTestSources = true
    }
    extensions.configure<KotlinAndroidProjectExtension> {
        jvmToolchain(21)
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }
    dependencies.add("testImplementation", library("kotlin-test-junit4"))
    dependencies.add("testImplementation", library("junit4"))
    configureTests(jvm = false)
}

class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")
        pluginManager.apply("java-library")
        extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain(21)
            compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
        }
        // These modules also run on Android (minSdk 31), where no lint checks them. Compiling main sources
        // against the JDK 17 API makes Java 18+ calls a compile error and keeps members such as
        // List.removeFirst/getFirst (Java 21, absent before API 35) from shadowing Kotlin's extensions.
        tasks.withType<KotlinCompile>().named { it == "compileKotlin" }.configureEach {
            compilerOptions.freeCompilerArgs.add("-Xjdk-release=17")
        }
        extensions.configure<org.gradle.api.plugins.JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
        dependencies.add("testImplementation", library("kotlin-test-junit5"))
        dependencies.add("testImplementation", dependencies.platform(library("junit5-bom")))
        dependencies.add("testRuntimeOnly", library("junit5-engine"))
        dependencies.add("testRuntimeOnly", library("junit5-launcher"))
        configureTests(jvm = true)
    }
}

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("com.android.library")
        configureAndroid(extensions.getByType<LibraryExtension>())
    }
}

class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        check(plugins.hasPlugin("com.android.library") || plugins.hasPlugin("com.android.application")) {
            "Compose convention requires an Android library/application convention"
        }
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        extensions.getByType<CommonExtension>().buildFeatures.compose = true
        dependencies.add("implementation", dependencies.platform(library("androidx-compose-bom")))
        dependencies.add("implementation", library("androidx-compose-ui"))
        dependencies.add("implementation", library("androidx-compose-material3"))
    }
}

class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        check(path.startsWith(":feature:")) { "Feature convention applied outside feature modules: $path" }
        pluginManager.apply("mesh.android.library")
        pluginManager.apply("mesh.android.compose")
        dependencies.add("implementation", dependencies.project(mapOf("path" to ":core:contracts")))
        dependencies.add("implementation", dependencies.project(mapOf("path" to ":core:ui")))
        dependencies.add("implementation", dependencies.project(mapOf("path" to ":core:l10n")))
    }
}

class AndroidRoomConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("mesh.android.library")
        pluginManager.apply("com.google.devtools.ksp")
        pluginManager.apply("androidx.room")
        extensions.configure<RoomExtension> {
            schemaDirectory(layout.projectDirectory.dir("schemas").asFile.path)
        }
        dependencies.add("implementation", library("androidx-room-runtime"))
        dependencies.add("ksp", library("androidx-room-compiler"))
    }
}

internal class RobolectricSdkArguments(@get:Classpath val sdk: FileCollection) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = listOf(
        "-Drobolectric.offline=true",
        "-Drobolectric.usePreinstrumentedJars=true",
        "-Drobolectric.dependency.dir=${sdk.singleFile.parentFile.absolutePath}",
    )
}

class AndroidRobolectricConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        check(plugins.hasPlugin("com.android.library") || plugins.hasPlugin("com.android.application")) {
            "Robolectric convention requires an Android module"
        }
        val sdk = configurations.create("testRobolectricSdk") {
            isCanBeConsumed = false
            isCanBeResolved = true
            isTransitive = false
        }
        dependencies.add(sdk.name, library("robolectric-sdk31"))
        dependencies.add("testImplementation", library("androidx-test-core"))
        dependencies.add("testImplementation", library("robolectric"))
        tasks.withType<Test>().configureEach {
            jvmArgumentProviders.add(RobolectricSdkArguments(sdk))
        }
    }
}

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("com.android.application")
        val android = extensions.getByType<ApplicationExtension>()
        configureAndroid(android)
        configureAppTestSharding()
        android.apply {
            namespace = "com.meshcoreone.android"
            defaultConfig {
                applicationId = "com.meshcoreone.android"
                targetSdk = 37
                versionCode = 1
                versionName = "0.0.0-scaffold"
            }
            buildTypes.getByName("debug").applicationIdSuffix = ".debug"
        }
        pluginManager.apply("mesh.android.compose")
        dependencies.add("implementation", library("androidx-activity-compose"))
    }
}
