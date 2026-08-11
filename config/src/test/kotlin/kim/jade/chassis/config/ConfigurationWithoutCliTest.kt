package kim.jade.chassis.config

import kim.jade.chassis.Application
import kim.jade.chassis.config.json.JsonConfigurationFormat
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import org.koin.dsl.module

class ConfigurationWithoutCliTest {
    @TempDir lateinit var directory: File

    @Test fun `configuration works when chassis-cli and clikt are absent`() {
        val entries = System.getProperty("chassis.test.classpath").split(File.pathSeparator)
        val (removed, kept) = entries.partition { isCliEntry(File(it), repositoryRoot()) }
        assertTrue(removed.size >= 2, "Expected chassis-cli and clikt on the test classpath: $removed")
        val output = File(directory, "out")
        val error = File(directory, "err")
        val process = ProcessBuilder(
            File(System.getProperty("java.home"), "bin/java").path,
            "-cp", kept.joinToString(File.pathSeparator),
            WithoutCliFixture::class.java.name,
        ).redirectOutput(output).redirectError(error).start()
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Child process timed out")
        } finally {
            if (process.isAlive) process.destroyForcibly().waitFor()
        }
        assertEquals(0, process.exitValue(), error.readText())
        assertContains(output.readText(), "name=books")
    }

    @Test fun `classpath entries are judged by module directory or file name, not by the checkout path`() {
        val root = File("/work/chassis-cli-clikt")
        assertTrue(isCliEntry(File(root, "cli/build/libs/chassis-cli-1.0-SNAPSHOT.jar"), root))
        assertTrue(isCliEntry(File(root, "cli/build/classes/kotlin/main"), root))
        assertTrue(isCliEntry(File("/home/.gradle/caches/clikt-core-jvm-5.1.0.jar"), root))
        assertFalse(isCliEntry(File(root, "core/build/classes/kotlin/main"), root))
        assertFalse(isCliEntry(File(root, "config/build/libs/chassis-config-1.0-SNAPSHOT.jar"), root))
        assertFalse(isCliEntry(File("/cli/.gradle/caches/kotlin-stdlib-2.4.20.jar"), root))
    }

    private fun isCliEntry(entry: File, root: File): Boolean {
        val absolute = entry.absoluteFile
        if (absolute.startsWith(root.absoluteFile)) {
            return absolute.relativeTo(root.absoluteFile).invariantSeparatorsPath.substringBefore('/') == "cli"
        }
        return absolute.name.startsWith("chassis-cli") || absolute.name.startsWith("clikt")
    }

    private fun repositoryRoot(): File =
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
}

object WithoutCliFixture {
    @Configuration("catalog")
    data class CatalogConfig(val name: String = "default")

    @JvmStatic fun main(args: Array<String>): Unit = runBlocking {
        val application = Application(listOf(module { configuration<CatalogConfig>() }))
        application.configurations.add(JsonConfigurationFormat.parse("""{"catalog":{"name":"books"}}"""))
        application.start()
        println("name=" + application.koin.get<CatalogConfig>().name)
        application.stop()
        exitProcess(0)
    }
}
