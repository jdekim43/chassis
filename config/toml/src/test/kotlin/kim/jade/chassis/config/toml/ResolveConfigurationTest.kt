package kim.jade.chassis.config.toml

import kim.jade.chassis.config.*
import kim.jade.chassis.config.document.decode
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class ResolveConfigurationTest {
    @Test fun `document decodes annotated nested settings and uses defaults for absent sections`() {
        val document = TomlConfiguration.parse("[company.worker]\nconcurrency = 8")
        assertEquals(8, ConfigurationResolver().apply { add(document) }.resolve<Settings>().concurrency)
        assertEquals(4, ConfigurationResolver().apply { add(TomlConfiguration.parse("")) }.resolve<Settings>().concurrency)
    }

    @Test fun `missing required values and incorrect types remain decode failures`() {
        val empty = TomlConfiguration.parse("")
        assertFailsWith<ConfigurationException.DecodeFailed> { ConfigurationResolver().apply { add(empty) }.resolve<RequiredSettings>() }
        for (text in listOf("[company.worker]\nconcurrency = 'eight'", "company = 3")) {
            val document = TomlConfiguration.parse(text)
            assertFailsWith<ConfigurationException.DecodeFailed> { ConfigurationResolver().apply { add(document) }.resolve<Settings>() }
        }
    }

    @Test fun `decoded values pass through object validation`() {
        val document = TomlConfiguration.parse("[company.worker]\nconcurrency = 0")
        val failure = assertFailsWith<ConfigurationException.ValidationFailed> { ConfigurationResolver().apply { add(document) }.resolve<Settings>() }
        assertEquals(listOf("company", "worker"), failure.path)
        assertEquals(listOf(ConfigurationIssue("company.worker.concurrency", "MUST_BE_POSITIVE")), failure.issues)
    }

    @Test fun `a duration is read from its readable notation`() {
        val document = TomlConfiguration.parse("[timing]\ninterval = \"30s\"")
        assertEquals(30.seconds, ConfigurationResolver().apply { add(document) }.resolve<Timing>().interval)
    }

    @Configuration("timing")
    data class Timing(val interval: Duration)

    @Configuration("company", "worker")
    data class Settings(val concurrency: Int = 4) : ValidatableConfiguration {
        override fun validate() = if (concurrency > 0) emptyList()
            else listOf(ConfigurationIssue("concurrency", "MUST_BE_POSITIVE"))
    }

    @Configuration("required")
    data class RequiredSettings(val token: String)
}
