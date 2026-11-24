// AndroidOnly: WP-201 Initial production Room schema and real SDK31 SQLite assertions.
plugins {
    id("mesh.android.room")
    id("mesh.android.robolectric")
}
dependencies {
    implementation(project(":core:model"))
    testImplementation(project(":core:contracts"))
    testImplementation(libs.kotlinx.coroutines.test)
}
dependencyLocking {
    lockFile.set(layout.projectDirectory.file("gradle.lockfile"))
}
tasks.withType<Test>().configureEach {
    inputs.dir(rootProject.layout.projectDirectory.dir("core/testing/fixtures/reference-codec"))
    inputs.dir(layout.projectDirectory.dir("schemas"))
        .withPropertyName("roomSchemas").withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    systemProperty("referenceCodecFixtures", rootProject.layout.projectDirectory.dir("core/testing/fixtures/reference-codec").asFile.absolutePath)
    systemProperty("roomSchemaDirectory", layout.projectDirectory.dir("schemas").asFile.absolutePath)
}

val repository = rootProject.projectDir.parentFile
val verifyDomainRoomTests by tasks.registering {
    group = "verification"
    description = "Run the actual Room v1 schema and domain test suites."
    dependsOn("testDebugUnitTest", ":core:model:test", ":core:contracts:test")
}

rootProject.tasks.named("verifyScaffoldTests") { dependsOn(verifyDomainRoomTests) }
tasks.named("check") { dependsOn(verifyDomainRoomTests) }
