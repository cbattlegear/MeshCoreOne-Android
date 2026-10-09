// AndroidOnly: WP-002 License-input parsing is explicit and cannot manufacture legal approval.
package com.meshcoreone.buildlogic

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.io.TempDir

class DependencyInventoryTest {
    @TempDir
    lateinit var directory: File

    private fun pom(text: String) = File(directory, "fixture.pom").apply { writeText(text) }

    @Test
    fun `all actual declared licenses are retained without picking a convenient one`() {
        val input = pom(
            """<project><licenses><license><name>Apache-2.0</name><url>https://apache.org/licenses/LICENSE-2.0</url></license><license><name>MIT</name><url>https://opensource.org/license/mit</url></license></licenses></project>""",
        )
        assertEquals(
            listOf(
                DeclaredLicense("Apache-2.0", "https://apache.org/licenses/LICENSE-2.0"),
                DeclaredLicense("MIT", "https://opensource.org/license/mit"),
            ),
            declaredLicenses(input),
        )
    }

    @Test
    fun `absence is not relabeled as a permissive license`() {
        assertEquals(emptyList(), declaredLicenses(pom("<project/>")))
    }

    @Test
    fun `identical upstream declarations produce one provenance record`() {
        val declaration =
            "<license><name>Apache-2.0</name><url>https://apache.org/licenses/LICENSE-2.0</url></license>"
        assertEquals(
            listOf(DeclaredLicense("Apache-2.0", "https://apache.org/licenses/LICENSE-2.0")),
            declaredLicenses(pom("<project><licenses>$declaration$declaration</licenses></project>")),
        )
    }

    @Test
    fun `incomplete declarations fail explicitly`() {
        assertFailsWith<IllegalArgumentException> {
            declaredLicenses(pom("<project><licenses><license><name>Unknown</name></license></licenses></project>"))
        }
    }

    @Test
    fun `duplicate declarations cannot silently select parent terms`() {
        assertFailsWith<IllegalArgumentException> {
            declaredLicenses(pom("<project><licenses/><licenses/></project>"))
        }
    }
}
