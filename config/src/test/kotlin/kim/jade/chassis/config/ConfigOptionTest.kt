package kim.jade.chassis.config

import kim.jade.chassis.Application
import kim.jade.chassis.cli.ApplicationCommand
import kim.jade.chassis.cli.runCli
import kim.jade.chassis.cli.testing.captureCli
import kim.jade.chassis.config.cli.ConfigOption
import kim.jade.chassis.config.json.JsonConfigurationFormat
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.koin.dsl.module

@OptIn(ExperimentalCoroutinesApi::class)
class ConfigOptionTest {
    @Configuration("catalog")
    data class CatalogConfig(val name: String = "default", val limit: Int = 10) : ValidatableConfiguration {
        override fun validate() = if (limit < 0) listOf(ConfigurationIssue("limit", "NEGATIVE")) else emptyList()
    }
    private class Show(val seen: MutableList<CatalogConfig>) : ApplicationCommand("show") {
        override suspend fun run(application: Application) { seen += application.koin.get<CatalogConfig>() }
    }

    private val seen = mutableListOf<CatalogConfig>()
    private val dir = createTempDirectory("config-option")
    @AfterTest fun cleanup() { dir.toFile().deleteRecursively() }

    private fun file(name: String, text: String) = dir.resolve(name).toFile().apply { writeText(text) }.path
    private fun TestScope.app() =
        Application(listOf(module { configuration<CatalogConfig>() }), name = "tool", context = StandardTestDispatcher(testScheduler))
    private suspend fun TestScope.cli(vararg arguments: String, application: () -> Application = { app() }) =
        captureCli { runCli("tool", arrayOf(*arguments), listOf(Show(seen)), listOf(ConfigOption()), application) }

    @Test fun `the file given with --config reaches the object a command resolves`() = runTest {
        val result = cli("--config", file("app.json", """{"catalog":{"name":"books"}}"""), "show")
        assertEquals(0, result.code)
        assertEquals(listOf(CatalogConfig("books", 10)), seen)
    }

    @Test fun `later files win and earlier values they do not mention remain`() = runTest {
        val base = file("base.json", """{"catalog":{"name":"base","limit":1}}""")
        val local = file("local.json", """{"catalog":{"name":"local"}}""")
        cli("--config", base, "--config", local, "show")
        assertEquals(listOf(CatalogConfig("local", 1)), seen)
    }

    @Test fun `without --config the defaults are used`() = runTest {
        val result = cli("show")
        assertEquals(0, result.code)
        assertEquals(listOf(CatalogConfig()), seen)
    }

    @Test fun `a reused option without --config does not keep the previous run's document`() = runTest {
        val option = ConfigOption()
        val books = file("app.json", """{"catalog":{"name":"books"}}""")
        captureCli { runCli("tool", arrayOf("--config", books, "show"), listOf(Show(seen)), listOf(option)) { app() } }
        captureCli { runCli("tool", arrayOf("show"), listOf(Show(seen)), listOf(option)) { app() } }
        assertEquals(listOf(CatalogConfig("books", 10), CatalogConfig()), seen)
    }

    @Test fun `a document put in by the application function is layered under the files`() = runTest {
        val preset = JsonConfigurationFormat.parse("""{"catalog":{"name":"preset","limit":3}}""")
        val withPreset = { app().also { it.configurations.add(preset) } }
        cli("show", application = withPreset)
        cli("--config", file("app.json", """{"catalog":{"name":"file"}}"""), "show", application = withPreset)
        assertEquals(listOf(CatalogConfig("preset", 3), CatalogConfig("file", 3)), seen)
    }

    @Test fun `a missing file is a usage error and creates no application`() = runTest {
        var created = false
        val result = cli("--config", dir.resolve("missing.json").toString(), "show") { created = true; app() }
        assertEquals(2, result.code)
        assertFalse(created)
    }

    @Test fun `an unreadable document and an unknown extension return 1 and create no application`() = runTest {
        var created = false
        val broken = cli("--config", file("broken.json", "{ not json"), "show") { created = true; app() }
        assertEquals(1, broken.code)
        assertContains(broken.err, "Caused by:")
        val unknown = cli("--config", file("app.unknown", "{}"), "show") { created = true; app() }
        assertEquals(1, unknown.code)
        assertContains(unknown.err.lines().last { it.isNotBlank() }, "Error: No configuration reader is available for 'unknown'")
        assertFalse(created)
    }

    @Test fun `an unknown key returns 1 and stderr names the key`() = runTest {
        val path = file("app.json", """{"catalog":{"nmae":"typo"}}""")
        val result = cli("--config", path, "show")
        assertEquals(1, result.code)
        assertEquals(
            "Error: Configuration decoding failed (source=$path, section=catalog.nmae): Unknown key",
            result.err.lines().last { it.isNotBlank() },
        )
        assertEquals(
            1,
            result.err.lines().count { "Configuration decoding failed: Unknown key (source=$path, section=catalog.nmae)" in it },
            result.err,
        )
        assertEquals(emptyList(), seen)
    }

    @Test fun `an unknown key in a file that another file shadows still fails and names that file`() = runTest {
        val typo = file("typo.json", """{"catalog":{"nmae":"x"}}""")
        val result = cli("--config", typo, "--config", file("ok.json", """{"catalog":{"name":"ok"}}"""), "show")
        assertEquals(1, result.code)
        assertContains(result.err, "nmae")
        assertContains(result.err.lines().last { it.isNotBlank() }, "typo.json")
        assertEquals(emptyList(), seen)
    }

    @Test fun `a validation failure returns 1 and names the issue`() = runTest {
        val result = cli("--config", file("app.json", """{"catalog":{"limit":-1}}"""), "show")
        assertEquals(1, result.code)
        assertContains(result.err.lines().last { it.isNotBlank() }, "catalog.limit=NEGATIVE")
    }

    @Test fun `--config is in help only when the option is given`() = runTest {
        val with = cli("--help")
        assertContains(with.out, "--config")
        val without = captureCli { runCli("tool", arrayOf("--help"), listOf(Show(seen))) { app() } }
        assertFalse("--config" in without.out)
    }
}
