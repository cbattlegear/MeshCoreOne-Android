import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.CommandLineArgumentProvider

// AndroidOnly: WP-002 Delivery adapter boundary without notifications or permission requests.
plugins {
    id("mesh.android.library")
    id("mesh.android.robolectric")
}
dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:contracts"))
    implementation(project(":core:l10n"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.runner)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":core:services"))
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

extensions.configure<com.android.build.api.dsl.LibraryExtension> {
    sourceSets.getByName("test").kotlin.srcDir(
        "src/androidTest/kotlin/com/meshcoreone/android/platform/notifications/messaging",
    )
}

class NotificationPlatformSdkArguments(
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    val directory: Provider<Directory>,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> =
        listOf("-Drobolectric.dependency.dir=${directory.get().asFile.absolutePath}")
}

tasks.withType<Test>().configureEach {
    dependsOn(":core:designsystem:prepareThemePlatformSdks")
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
    jvmArgumentProviders.add(
        NotificationPlatformSdkArguments(
            rootProject.project(":core:designsystem").layout.buildDirectory.dir("theme-platform-sdks"),
        ),
    )
}

rootProject.tasks.named("verifyScaffoldTests") { dependsOn(":platform:notifications:testDebugUnitTest") }
tasks.named("check") { dependsOn("testDebugUnitTest") }
