// AndroidOnly: WP-002 Verification tasks must exist independently of running any resolver/action first.
package com.meshcoreone.buildlogic

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.io.TempDir

class RootTaskRegistrationTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `all declared root verification tasks are eagerly registered`() {
        val root = ProjectBuilder.builder().withProjectDir(directory).build()
        root.pluginManager.apply(ScaffoldRootConventionPlugin::class.java)
        val expected = setOf(
            "validateModuleGraph", "resolveScaffoldDependencies", "runtimeDependencyInventory",
            "verifyScaffoldTests", "verifyAppTestResults", "verifyRoomSchema", "lintScaffold", "checkScaffold",
        )
        assertTrue(root.tasks.names.containsAll(expected), "Missing independently selectable verification task")
    }
}
