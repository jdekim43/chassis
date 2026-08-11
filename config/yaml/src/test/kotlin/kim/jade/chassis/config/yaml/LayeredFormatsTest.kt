package kim.jade.chassis.config.yaml

import kim.jade.chassis.Application
import kim.jade.chassis.cli.ApplicationCommand
import kim.jade.chassis.cli.runCli
import kim.jade.chassis.config.Configuration
import kim.jade.chassis.config.ConfigurationException
import kim.jade.chassis.config.ConfigurationResolver
import kim.jade.chassis.config.cli.ConfigOption
import kim.jade.chassis.config.configuration
import kim.jade.chassis.config.json.JsonConfigurationFormat
import kim.jade.chassis.config.resolve
import kim.jade.chassis.config.toml.TomlConfiguration
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import org.koin.dsl.module

@OptIn(ExperimentalCoroutinesApi::class)
class LayeredFormatsTest {
    @Serializable data class Db(val host: String = "localhost", val port: Int = 5432)
    @Configuration("catalog")
    data class Catalog(
        val name: String = "default",
        val limit: Int = 10,
        val db: Db = Db(),
        val labels: Map<String, String> = emptyMap(),
    )
    @Serializable data class Node(val host: String = "", val port: Int = 0, val user: String = "")
    @Configuration("keyed") data class Keyed(val byNumber: Map<Int, Node> = emptyMap())
    private class Show(val seen: MutableList<Catalog>) : ApplicationCommand("show") {
        override suspend fun run(application: Application) { seen += application.koin.get<Catalog>() }
    }

    private val toml = "[catalog]\nname = \"toml\"\nlimit = 1\n[catalog.db]\nhost = \"t\"\nport = 1\n[catalog.labels]\na = \"toml\"\n"
    private val json = """{"catalog":{"name":"json","db":{"port":2},"labels":{"b":"json"}}}"""
    private val yaml = "catalog:\n  name: yaml\n  labels:\n    a: yaml\n"
    private val expected = Catalog("yaml", 1, Db("t", 2), mapOf("a" to "yaml", "b" to "json"))

    @Test fun `documents of three formats are read key by key`() {
        val resolver = ConfigurationResolver().apply {
            add(TomlConfiguration.parse(toml, "base.toml"))
            add(JsonConfigurationFormat.parse(json, "mid.json"))
            add(YamlConfiguration.parse(yaml, "top.yaml"))
        }
        assertEquals(expected, resolver.resolve<Catalog>())
    }

    @Test fun `an integer-keyed entry is layered across three formats`() {
        val resolver = ConfigurationResolver().apply {
            add(TomlConfiguration.parse("[keyed.byNumber.1]\nhost = \"t\"\n", "base.toml"))
            add(JsonConfigurationFormat.parse("""{"keyed":{"byNumber":{"1":{"port":2}}}}""", "mid.json"))
            add(YamlConfiguration.parse("keyed:\n  byNumber:\n    1:\n      user: y\n", "top.yaml"))
        }
        assertEquals(mapOf(1 to Node("t", 2, "y")), resolver.resolve<Keyed>().byNumber)
    }

    @Test fun `--config files of three formats are layered in the order given`() = runTest {
        val dir = createTempDirectory("layered-formats")
        try {
            fun file(name: String, text: String) = dir.resolve(name).toFile().apply { writeText(text) }.path
            val seen = mutableListOf<Catalog>()
            val code = runCli(
                "tool",
                arrayOf("--config", file("base.toml", toml), "--config", file("mid.json", json), "--config", file("top.yaml", yaml), "show"),
                listOf(Show(seen)),
                listOf(ConfigOption()),
            ) {
                Application(
                    listOf(module { configuration<Catalog>() }),
                    name = "tool",
                    context = StandardTestDispatcher(testScheduler),
                )
            }
            assertEquals(0, code)
            assertEquals(listOf(expected), seen)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun `a wrong value names the file it came from`() {
        val resolver = ConfigurationResolver().apply {
            add(TomlConfiguration.parse(toml, "base.toml"))
            add(JsonConfigurationFormat.parse("""{"catalog":{"limit":"many"}}""", "bad.json"))
        }
        val failure = assertFailsWith<ConfigurationException.DecodeFailed> { resolver.resolve<Catalog>() }
        assertEquals("bad.json", failure.source)
        assertEquals(listOf("catalog", "limit"), failure.path)
    }

    @Test fun `a wrong value in a --config file fails the run and stderr names that file and the key`() = runTest {
        val dir = createTempDirectory("layered-formats")
        val originalErr = System.err
        val err = ByteArrayOutputStream()
        try {
            val base = dir.resolve("base.toml").toFile().apply { writeText(toml) }
            val bad = dir.resolve("bad.json").toFile().apply { writeText("""{"catalog":{"limit":"many"}}""") }
            val seen = mutableListOf<Catalog>()
            System.setErr(PrintStream(err, true))
            val code = runCli(
                "tool",
                arrayOf("--config", base.path, "--config", bad.path, "show"),
                listOf(Show(seen)),
                listOf(ConfigOption()),
            ) {
                Application(
                    listOf(module { configuration<Catalog>() }),
                    name = "tool",
                    context = StandardTestDispatcher(testScheduler),
                )
            }
            assertNotEquals(0, code)
            assertTrue(seen.isEmpty())
            assertContains(err.toString(), "source=${bad.path}")
            assertContains(err.toString(), "catalog.limit")
        } finally {
            System.setErr(originalErr)
            dir.toFile().deleteRecursively()
        }
    }
}
