// AndroidOnly: WP-101 Pure JVM protocol and coroutine transport contracts.
import org.gradle.api.tasks.PathSensitivity

plugins { id("mesh.jvm.library") }

dependencies {
    api(libs.kotlinx.coroutines.core)
    implementation(libs.bouncycastle.bcprov)
    testImplementation(libs.kotlinx.coroutines.test)
}

val repository = rootProject.projectDir.parentFile
tasks.withType<Test>().configureEach {
    inputs.files(
        fileTree(repository.resolve("docs/android/evidence/WP-102")) {
            include("message-vectors.tsv", "key-vectors.tsv", "signature-vectors.tsv", "vector-generation.json")
        },
        repository.resolve("docs/android/evidence/WP-103/python-reference-vectors.tsv"),
        rootProject.file("core/testing/fixtures/protocol-vectors.tsv"),
        rootProject.file("core/testing/fixtures/reference-codec/channel-crypto-oracle.json"),
        layout.projectDirectory.file("LICENSE"),
    ).withPropertyName("protocolOracleInputs").withPathSensitivity(PathSensitivity.RELATIVE)
}
