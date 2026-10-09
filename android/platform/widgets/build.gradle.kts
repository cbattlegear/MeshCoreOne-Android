// AndroidOnly: WP-403 Glance status widget and Quick Settings connection tile.
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
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
}

rootProject.tasks.named("verifyScaffoldTests") { dependsOn(":platform:widgets:testDebugUnitTest") }
tasks.named("check") { dependsOn("testDebugUnitTest") }
