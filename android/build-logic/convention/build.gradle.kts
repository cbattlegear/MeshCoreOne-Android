// AndroidOnly: WP-002 Tested Gradle conventions; explicit KGP overrides AGP's baseline.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
}

kotlin {
    jvmToolchain(21)
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

dependencyLocking {
    lockAllConfigurations()
    lockMode.set(LockMode.STRICT)
}

dependencies {
    implementation(libs.android.gradle.plugin)
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.compose.gradle.plugin)
    implementation(libs.room.gradle.plugin)
    implementation(libs.ksp.gradle.plugin)
    testImplementation(gradleTestKit())
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(platform(libs.junit5.bom))
    testRuntimeOnly(libs.junit5.engine)
    testRuntimeOnly(libs.junit5.launcher)
}

tasks.test {
    useJUnitPlatform()
    failOnNoDiscoveredTests.set(true)
    testLogging.showStandardStreams = true
    maxParallelForks = 1
    maxHeapSize = providers.gradleProperty("scaffoldTestHeap").getOrElse("256m")
    providers.gradleProperty("scaffoldTestJvmArgs").orNull?.let {
        jvmArgs(it.split(" ").filter(String::isNotBlank))
    }
}

gradlePlugin {
    plugins {
        register("jvmLibrary") {
            id = "mesh.jvm.library"
            implementationClass = "com.meshcoreone.buildlogic.JvmLibraryConventionPlugin"
        }
        register("androidLibrary") {
            id = "mesh.android.library"
            implementationClass = "com.meshcoreone.buildlogic.AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "mesh.android.compose"
            implementationClass = "com.meshcoreone.buildlogic.AndroidComposeConventionPlugin"
        }
        register("androidFeature") {
            id = "mesh.android.feature"
            implementationClass = "com.meshcoreone.buildlogic.AndroidFeatureConventionPlugin"
        }
        register("androidRoom") {
            id = "mesh.android.room"
            implementationClass = "com.meshcoreone.buildlogic.AndroidRoomConventionPlugin"
        }
        register("androidRobolectric") {
            id = "mesh.android.robolectric"
            implementationClass = "com.meshcoreone.buildlogic.AndroidRobolectricConventionPlugin"
        }
        register("androidApplication") {
            id = "mesh.android.application"
            implementationClass = "com.meshcoreone.buildlogic.AndroidApplicationConventionPlugin"
        }
        register("scaffoldRoot") {
            id = "mesh.scaffold.root"
            implementationClass = "com.meshcoreone.buildlogic.ScaffoldRootConventionPlugin"
        }
    }
}
