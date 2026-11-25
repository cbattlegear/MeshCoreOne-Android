// AndroidOnly: WP-003 Partition app test classes without dropping parameter families or nested runners.
package com.meshcoreone.buildlogic

import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.withType

internal data class AppTestShard(val index: Int, val count: Int)

internal fun parseAppTestShard(value: String): AppTestShard {
    val parts = value.split("/")
    val index = parts.getOrNull(0)?.toIntOrNull()
    val count = parts.getOrNull(1)?.toIntOrNull()
    require(parts.size == 2 && index != null && count != null && count in 1..5 && index in 0 until count) {
        "scaffoldAppTestShard must be index/count with 1..5 shards and a zero-based index"
    }
    return AppTestShard(index, count)
}

internal fun appTestShardPatterns(classNames: Iterable<String>, shard: AppTestShard): List<String> =
    classNames.map { it.substringBefore('$') }.toSortedSet()
        .filter { Math.floorMod(it.hashCode(), shard.count) == shard.index }
        .flatMap { listOf(it, "$it\$*") }

internal fun Project.configureAppTestSharding() {
    val selected = providers.gradleProperty("scaffoldAppTestShard").orNull ?: return
    val shard = parseAppTestShard(selected)
    tasks.withType<Test>().matching { it.name == "testDebugUnitTest" }.configureEach {
        inputs.property("appTestShard", selected)
        doFirst {
            val names = testClassesDirs.files.flatMap { root ->
                root.walkTopDown().filter { it.isFile && it.extension == "class" }.map {
                    it.relativeTo(root).invariantSeparatorsPath.removeSuffix(".class").replace('/', '.')
                }.toList()
            }
            val patterns = appTestShardPatterns(names, shard)
            check(patterns.isNotEmpty()) { "App test shard $selected selected no compiled classes" }
            filter.setIncludePatterns(*patterns.toTypedArray())
        }
    }
}
