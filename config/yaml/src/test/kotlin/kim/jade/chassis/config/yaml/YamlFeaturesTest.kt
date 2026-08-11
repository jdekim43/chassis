package kim.jade.chassis.config.yaml

import kim.jade.chassis.config.Configuration
import kim.jade.chassis.config.ConfigurationException
import kim.jade.chassis.config.ConfigurationResolver
import kim.jade.chassis.config.document.decode
import kim.jade.chassis.config.resolve
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.Serializable

class YamlFeaturesTest {
    @Test fun `anchors merges block strings and timestamps are preserved`() {
        val document = YamlConfiguration.parse("""
            defaults: &defaults
              count: 2
              label: base
            worker:
              <<: *defaults
              label: |
                first
                second
              date: 2026-11-11
        """.trimIndent())
        val value = document.decode<Worker>("worker")
        assertEquals(2, value.count)
        assertEquals("first\nsecond\n", value.label)
        assertEquals("2026-11-11", value.date)
    }

    @Test fun `a duration is read from its readable notation`() {
        val document = YamlConfiguration.parse("timing:\n  interval: 30s")
        assertEquals(30.seconds, ConfigurationResolver().apply { add(document) }.resolve<Timing>().interval)
    }

    @Test fun `blank documents and explicit null use defaults and a scalar ancestor fails`() {
        assertEquals(Worker(), YamlConfiguration.parse("# comment only").decode<Worker>("worker"))
        assertEquals(Worker(), YamlConfiguration.parse("worker: null").decode<Worker>("worker"))
        assertFailsWith<ConfigurationException.DecodeFailed> {
            YamlConfiguration.parse("company: 1").decode<Worker>("company", "worker")
        }
    }

    @Test fun `duplicate keys recursive aliases non-string keys custom tags and multiple documents are rejected`() {
        for (text in listOf(
            "worker: 1\nworker: 2",
            "worker: &cycle [*cycle]",
            "1.5: value",
            "worker: !!java.lang.String [value]",
            "worker: 1\n---\nworker: 2",
            "worker: .inf",
            "[]",
            "null",
        )) {
            assertFailsWith<ConfigurationException.ParseFailed>(text) { YamlConfiguration.parse(text) }
        }
    }

    @Test fun `quoted strings and YAML booleans are not coerced to numeric or string fields`() {
        for (text in listOf("count: '2'", "label: 2", "count: true", "unknown: 1")) {
            assertFailsWith<ConfigurationException.DecodeFailed>(text) { YamlConfiguration.parse(text).decode<Worker>() }
        }
    }

    @Test fun `integer keys become decimal names for integer-keyed maps`() {
        val document = YamlConfiguration.parse("numbers:\n  byNumber:\n    1: one\n    0x10: sixteen\n    -2: minus")
        assertEquals(
            mapOf(1 to "one", 16 to "sixteen", -2 to "minus"),
            ConfigurationResolver().apply { add(document) }.resolve<Numbers>().byNumber,
        )
    }

    @Test fun `a conversion failure names its place without the value`() {
        for ((text, place, literal) in listOf(
            Triple("a:\n  1: secret-a\n  \"1\": secret-b", "a.1", "secret"),
            Triple("true: secret", "<root>", "secret"),
            Triple("a:\n  true: secret", "a", "secret"),
            Triple("a:\n  [k]: secret", "a", "secret"),
            Triple("a:\n  b: [1.0, .inf]", "a.b[1]", "inf"),
            Triple("a:\n  b: &cycle [secret, *cycle]", "a.b[1]", "secret"),
            Triple("a:\n  b: !!set {secret}", "a.b", "secret"),
        )) {
            val failure = assertFailsWith<ConfigurationException.ParseFailed>(text) { YamlConfiguration.parse(text, "app.yaml") }
            val message = failure.cause?.message.orEmpty()
            assertContains(message, "'$place'", message = text)
            assertFalse(literal in message, message)
        }
    }

    @Configuration("numbers") data class Numbers(val byNumber: Map<Int, String> = emptyMap())
    @Serializable data class Worker(val count: Int = 4, val label: String = "default", val date: String = "")

    @Configuration("timing")
    data class Timing(val interval: Duration)
}
