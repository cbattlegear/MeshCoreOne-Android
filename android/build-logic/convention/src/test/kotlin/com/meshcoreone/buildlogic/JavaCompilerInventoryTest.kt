// AndroidOnly: WP-002 Probe the frozen WP-003 collector against real Android Java/Kotlin test compilation.
package com.meshcoreone.buildlogic

import groovy.json.JsonSlurper
import java.io.File
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir

class JavaCompilerInventoryTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `conventional Java input reaches the Android test runtime beside inventoried Kotlin`() {
        control(additional = false)
    }

    @Test
    fun `additional JavaCompile input reaches the Android test runtime without collector rejection`() {
        control(additional = true)
    }

    private fun control(additional: Boolean) {
        val repository = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "tools/android-port/controller/verify_candidate_task_graph.gradle").isFile }
        val collector = File(repository, "tools/android-port/controller/verify_candidate_task_graph.gradle")
        val collectorBlob = "88eb66536d20a34118686a498449d9b04b470399"
        assertEquals(collectorBlob, blob(collector), "Rebind this control explicitly if the frozen collector changes")
        val module = File(directory, "platform/notifications")
        val mode = if (additional) "additional" else "conventional"
        val javaRelative = if (additional) {
            "additional-java/fixture/JavaRuntimePayload.java"
        } else {
            "platform/notifications/src/test/java/fixture/JavaRuntimePayload.java"
        }
        val kotlinRelative = "platform/notifications/src/test/kotlin/fixture/SelectedRuntimeTest.kt"
        write("settings.gradle", """
            pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
            dependencyResolutionManagement {
                repositories { google(); mavenCentral() }
                versionCatalogs {
                    libs { from(files('${File(repository, "android/gradle/libs.versions.toml").toURI()}')) }
                }
            }
            rootProject.name = 'java-compiler-control'
            include ':platform:notifications'
        """)
        write("build.gradle", "")
        write("platform/notifications/src/main/AndroidManifest.xml", "<manifest />")
        write(javaRelative, """
            package fixture;
            public final class JavaRuntimePayload {
                public static String value() { return "$mode"; }
            }
        """)
        write(kotlinRelative, """
            package fixture
            class SelectedRuntimeTest {
                @org.junit.Test
                fun selectedTestLoadsJavaInput() {
                    val payload = Class.forName("fixture.JavaRuntimePayload")
                    org.junit.Assert.assertEquals("$mode", payload.getMethod("value").invoke(null))
                    println("WP002_RUNTIME|$mode|" + payload.protectionDomain.codeSource.location)
                }
            }
        """)
        write("platform/notifications/build.gradle", """
            import groovy.json.JsonOutput
            import org.gradle.api.tasks.compile.JavaCompile
            import org.gradle.api.tasks.testing.Test
            plugins { id 'mesh.android.library' }
            ${if (additional) """
                tasks.named('compileDebugUnitTestJavaWithJavac', JavaCompile) {
                    source(rootProject.file('$javaRelative'))
                }
            """.trimIndent() else ""}
            tasks.named('compileDebugUnitTestKotlin') {
                doLast {
                    def report = new File(project.buildDir, 'control/kotlin-compiler.json')
                    report.parentFile.mkdirs()
                    report.text = JsonOutput.toJson([
                        task: path,
                        sources: sources.files.collect { it.canonicalPath }.sort(),
                        outputs: outputs.files.files.collect { it.canonicalPath }.sort(),
                    ])
                }
            }
            tasks.withType(JavaCompile).configureEach {
                doLast {
                    def report = new File(project.buildDir, "control/" + name + ".json")
                    report.parentFile.mkdirs()
                    report.text = JsonOutput.toJson([
                        task: path,
                        sources: source.files.collect { it.canonicalPath }.sort(),
                        javaInputs: inputs.files.files.findAll { it.name.endsWith('.java') }
                            .collect { it.canonicalPath }.sort(),
                        destination: destinationDirectory.get().asFile.canonicalPath,
                        payloadExists: new File(destinationDirectory.get().asFile,
                            'fixture/JavaRuntimePayload.class').isFile(),
                    ])
                }
            }
            tasks.withType(Test).configureEach {
                filter { includeTestsMatching 'fixture.SelectedRuntimeTest.selectedTestLoadsJavaInput' }
                doFirst {
                    def report = new File(project.buildDir, 'control/test-runtime.json')
                    report.parentFile.mkdirs()
                    report.text = JsonOutput.toJson([
                        task: path,
                        classDirs: testClassesDirs.files.collect { it.canonicalPath }.sort(),
                        classpath: classpath.files.collect { it.canonicalPath }.sort(),
                    ])
                }
            }
        """)
        val sources = File(directory, "compiled-sources.jsonl").apply { writeText("") }
        val graph = File(directory, "task-graph.json")
        val environment = System.getenv().toMutableMap().apply {
            put("ANDROID_CI_TEST_SOURCES", sources.absolutePath)
            put("ANDROID_CI_TASK_GRAPH", graph.absolutePath)
            remove("ANDROID_CI_TEST_LINEAGE")
        }
        val gradleHome = assertNotNull(environment["GRADLE_USER_HOME"], "Use the supported hosted scaffold route")
        val result = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
            .withEnvironment(environment)
            .withArguments(
                ":platform:notifications:testDebugUnitTest", "--init-script", collector.absolutePath,
                "--gradle-user-home", gradleHome, "--offline", "--no-build-cache",
                "--no-configuration-cache", "--max-workers=1", "--stacktrace",
            ).build()
        val testTask = ":platform:notifications:testDebugUnitTest"
        val javaTask = ":platform:notifications:compileDebugUnitTestJavaWithJavac"
        val kotlinTask = ":platform:notifications:compileDebugUnitTestKotlin"
        listOf(javaTask, kotlinTask, testTask).forEach {
            assertEquals(TaskOutcome.SUCCESS, assertNotNull(result.task(it), result.output).outcome)
        }
        val compiler = json(File(module, "build/control/compileDebugUnitTestJavaWithJavac.json"))
        val javaFile = File(directory, javaRelative).canonicalPath
        assertEquals(listOf(javaFile), compiler["sources"])
        assertTrue(javaFile in strings(compiler["javaInputs"]))
        assertEquals(true, compiler["payloadExists"])
        val runtime = json(File(module, "build/control/test-runtime.json"))
        val destination = compiler["destination"] as String
        assertTrue(destination in strings(runtime["classDirs"]))
        assertTrue(destination in strings(runtime["classpath"]))
        val kotlinCompiler = json(File(module, "build/control/kotlin-compiler.json"))
        assertTrue(File(directory, kotlinRelative).canonicalPath in strings(kotlinCompiler["sources"]))
        assertTrue(strings(kotlinCompiler["outputs"]).any { it in strings(runtime["classDirs"]) })
        val graphTasks = json(graph)["tasks"] as List<*>
        val actualPaths = graphTasks.map { (it as Map<*, *>)["path"] }
        assertTrue(listOf(javaTask, kotlinTask, testTask).all { it in actualPaths })

        val records = sources.readLines().filter(String::isNotBlank).map { JsonSlurper().parseText(it) as Map<*, *> }
        val inventory = records.single { it["task"] == testTask }["sources"] as List<*>
        val inventoried = inventory.map { it as Map<*, *> }
        assertEquals(
            listOf(mapOf("path" to "android/$kotlinRelative", "blob" to blob(File(directory, kotlinRelative)))),
            inventoried,
        )
        assertFalse(inventoried.any { it["path"] == "android/$javaRelative" })

        val report = File(module, "build/test-results/testDebugUnitTest/TEST-fixture.SelectedRuntimeTest.xml")
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val xml = factory.newDocumentBuilder().parse(report)
        val suite = xml.documentElement
        assertEquals("1", suite.getAttribute("tests"))
        listOf("failures", "errors", "skipped").forEach { assertEquals("0", suite.getAttribute(it)) }
        val cases = suite.getElementsByTagName("testcase")
        assertEquals(1, cases.length)
        assertEquals("selectedTestLoadsJavaInput", cases.item(0).attributes.getNamedItem("name").nodeValue)
        val runtimeMarker = "WP002_RUNTIME|$mode|" + File(destination).toURI().toURL()
        assertTrue(suite.textContent.contains(runtimeMarker), suite.textContent)
        println(
            "WP002_JAVA_CONTROL|$mode|collector=$collectorBlob|javaBlob=${blob(File(directory, javaRelative))}" +
                "|kotlinBlob=${blob(File(directory, kotlinRelative))}|compiler=${File(module, "build/control/compileDebugUnitTestJavaWithJavac.json").readText()}" +
                "|kotlinCompiler=${File(module, "build/control/kotlin-compiler.json").readText()}" +
                "|runtime=${File(module, "build/control/test-runtime.json").readText()}" +
                "|inventory=${sources.readText().trim()}|case=fixture.SelectedRuntimeTest.selectedTestLoadsJavaInput" +
                "|passed=1|failed=0|errors=0|skipped=0|runtimeMarker=$runtimeMarker|javaInventoried=false",
        )
    }

    private fun write(path: String, text: String) {
        File(directory, path).apply { parentFile.mkdirs(); writeText(text.trimIndent() + "\n") }
    }

    private fun json(file: File): Map<*, *> = JsonSlurper().parse(file) as Map<*, *>

    private fun strings(value: Any?): List<String> = (value as List<*>).map { it as String }

    private fun blob(file: File): String {
        val bytes = file.readBytes()
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update("blob ${bytes.size}\u0000".toByteArray(Charsets.UTF_8))
        return digest.digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
