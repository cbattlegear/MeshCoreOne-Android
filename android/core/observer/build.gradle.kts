// AndroidOnly: WP-101 Prizm Mobile Observer pure-JVM capture/hash/privacy-filter logic (docs/android/deviations/PRIZM-OBSERVER.md).
plugins { id("mesh.jvm.library") }

dependencies {
    implementation(project(":core:protocol"))
}

rootProject.tasks.named("verifyScaffoldTests") { dependsOn(":core:observer:test") }
