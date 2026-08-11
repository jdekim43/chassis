package kim.jade.chassis.config

import kim.jade.chassis.config.json.JsonConfigurationFormat
import kim.jade.kotlinx.logger.LogLevel
import kim.jade.kotlinx.logger.LogRecord
import kim.jade.kotlinx.logger.Logger
import kim.jade.kotlinx.logger.pipeline.SinkPipe
import kotlinx.serialization.Serializable
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigurationLoggingTest {
    @Serializable enum class Mode { A, B }
    @Configuration("required") data class Required(val name: String)
    @Configuration("catalog")
    data class Catalog(val limit: Int = 1, val mode: Mode = Mode.A) : ValidatableConfiguration {
        override fun validate() = if (limit < 0) listOf(ConfigurationIssue("limit", "NEGATIVE")) else emptyList()
    }

    private fun recording(block: () -> Unit): List<LogRecord> {
        val records = CopyOnWriteArrayList<LogRecord>()
        Logger.defaultPipeline.install(SinkPipe { records += it }, 0)
        try {
            block()
        } finally {
            Logger.defaultPipeline.uninstall(SinkPipe)
        }
        return records
    }

    private fun resolver(json: String) = ConfigurationResolver().apply { add(JsonConfigurationFormat.parse(json, "app.json")) }

    @Test fun `adding a document logs its source`() {
        val records = recording {
            ConfigurationResolver().apply {
                add(JsonConfigurationFormat.parse("{}", "base.json"))
                add(JsonConfigurationFormat.parse("{}", "local.json"))
            }
        }

        assertEquals(
            listOf("Configuration added: base.json", "Configuration added: local.json"),
            records.filter { it.level == LogLevel.INFO }.map { it.body },
        )
        assertEquals(setOf("kim.jade.chassis.config"), records.map { it.loggerName }.toSet())
    }

    @Test fun `a failed resolve logs once with the source and section as meta`() {
        val records = recording {
            assertFailsWith<ConfigurationException.DecodeFailed> { ConfigurationResolver().resolve<Required>() }
        }
        val error = records.single { it.level == LogLevel.ERROR }
        assertEquals("Configuration decoding failed: Required value is missing", error.body)
        assertEquals(mapOf("source" to "defaults", "section" to "required.name"), error.meta)
        assertNull(error.exception)
        assertEquals("kim.jade.chassis.config", error.loggerName)
    }

    @Test fun `a validation failure logs its issues as meta`() {
        val records = recording {
            assertFailsWith<ConfigurationException.ValidationFailed> {
                resolver("""{"catalog":{"limit":-1}}""").resolve<Catalog>()
            }
        }
        val error = records.single { it.level == LogLevel.ERROR }
        assertEquals("Configuration validation failed", error.body)
        assertEquals("catalog", error.meta["section"])
        assertEquals("catalog.limit=NEGATIVE", error.meta["issues"])
    }

    @Test fun `a failure the deserializer finds logs without a reason`() {
        val records = recording {
            assertFailsWith<ConfigurationException.DecodeFailed> {
                resolver("""{"catalog":{"mode":"C"}}""").resolve<Catalog>()
            }
        }
        val error = records.single { it.level == LogLevel.ERROR }
        assertEquals("Configuration decoding failed", error.body)
        assertEquals(mapOf("source" to "app.json", "section" to "catalog"), error.meta)
    }

    @Test fun `a load block that throws something else is not logged by config`() {
        val records = recording {
            assertFailsWith<IllegalStateException> { ConfigurationResolver().resolve<Required> { error("boom") } }
        }
        assertTrue(records.none { it.level == LogLevel.ERROR })
    }
}
