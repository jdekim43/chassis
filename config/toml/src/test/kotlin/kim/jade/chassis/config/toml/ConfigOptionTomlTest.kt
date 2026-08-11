package kim.jade.chassis.config.toml

import kim.jade.chassis.Application
import kim.jade.chassis.cli.ApplicationCommand
import kim.jade.chassis.cli.runCli
import kim.jade.chassis.config.cli.ConfigOption
import kim.jade.chassis.config.Configuration
import kim.jade.chassis.config.configuration
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import org.koin.dsl.module

@OptIn(ExperimentalCoroutinesApi::class)
class ConfigOptionTomlTest {
    @TempDir lateinit var directory: File

    @Configuration("catalog")
    data class CatalogConfig(val name: String = "default", val limit: Int = 10, val owner: String = "nobody")

    private class Show(val seen: MutableList<CatalogConfig>) : ApplicationCommand("show") {
        override suspend fun run(application: Application) { seen += application.koin.get<CatalogConfig>() }
    }

    @Test fun `--config layers real toml files in order and the command gets the layered object`() = runTest {
        val base = File(directory, "base.toml").apply { writeText("[catalog]\nname = \"base\"\nlimit = 1\n") }
        val local = File(directory, "local.toml").apply { writeText("[catalog]\nname = \"local\"\nowner = \"me\"\n") }
        val seen = mutableListOf<CatalogConfig>()
        val code = runCli("tool", arrayOf("--config", base.path, "--config", local.path, "show"), listOf(Show(seen)), listOf(ConfigOption())) {
            Application(listOf(module { configuration<CatalogConfig>() }), name = "tool", context = StandardTestDispatcher(testScheduler))
        }
        assertEquals(0, code)
        assertEquals(listOf(CatalogConfig("local", 1, "me")), seen)
    }
}
