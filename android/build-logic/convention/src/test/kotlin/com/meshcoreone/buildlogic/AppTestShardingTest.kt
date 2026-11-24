// AndroidOnly: WP-003 Every compiled app class and nested parameter family belongs to exactly one shard.
package com.meshcoreone.buildlogic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppTestShardingTest {
    private val classes = listOf(
        "com.meshcoreone.android.NavigationTest",
        "com.meshcoreone.android.NavigationTestExtra",
        "com.meshcoreone.android.NavigationTest\$Nested",
        "com.meshcoreone.android.NavigationTest\$Nested\$Family",
        "com.meshcoreone.android.OriginalResponseCases",
        "com.meshcoreone.android.RoomProcessTest",
    )

    @Test
    fun `every class including nested families is assigned once for each supported shard count`() {
        for (count in 1..5) {
            val partitions = (0 until count).map { index ->
                appTestShardPatterns(classes, parseAppTestShard("$index/$count"))
            }
            for (name in classes) {
                val owners = partitions.count { patterns ->
                    name in patterns || "${name.substringBefore('$')}\$*" in patterns
                }
                assertEquals(1, owners, "$name with $count shards")
            }
            assertEquals(partitions, (0 until count).map { index ->
                appTestShardPatterns(classes.reversed() + classes, parseAppTestShard("$index/$count"))
            })
        }
    }

    @Test
    fun `prefix siblings cannot be included accidentally by a broad class wildcard`() {
        val patterns = appTestShardPatterns(classes, parseAppTestShard("0/1"))
        assertFalse("com.meshcoreone.android.NavigationTest*" in patterns)
        assertTrue("com.meshcoreone.android.NavigationTest" in patterns)
        assertTrue("com.meshcoreone.android.NavigationTest\$*" in patterns)
        assertTrue("com.meshcoreone.android.NavigationTestExtra" in patterns)
    }

    @Test
    fun `invalid shard selections fail instead of running or silently skipping tests`() {
        for (value in listOf("", "0", "0/0", "0/6", "-1/4", "4/4", "5/5", "1/x", "x/4", "0/4/extra")) {
            assertFailsWith<IllegalArgumentException>(value) { parseAppTestShard(value) }
        }
    }
}
