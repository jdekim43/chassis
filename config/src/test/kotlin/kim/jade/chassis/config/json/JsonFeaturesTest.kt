package kim.jade.chassis.config.json

import kim.jade.chassis.config.ConfigurationException
import kim.jade.chassis.config.document.decode
import kotlin.test.*
import kotlinx.serialization.Serializable

class JsonFeaturesTest {
    @Test fun `literal dotted keys differ from nested paths`() {
        val document = JsonConfigurationFormat.parse("""{"company.worker":{"count":7},"company":{"worker":{"count":2}}}""")
        assertEquals(7, document.decode<Worker>("company.worker").count)
        assertEquals(2, document.decode<Worker>("company", "worker").count)
    }

    @Test fun `explicit null reads as a missing section and invalid ancestors fail`() {
        for (text in listOf("""{"company":null}""", """{"company":{"worker":null}}""")) {
            assertEquals(Worker(), JsonConfigurationFormat.parse(text).decode<Worker>("company", "worker"), text)
        }
        assertFailsWith<ConfigurationException.DecodeFailed> {
            JsonConfigurationFormat.parse("""{"company":3}""").decode<Worker>("company", "worker")
        }
    }

    @Test fun `scalar coercions unknown fields and invalid JSON are rejected`() {
        for (text in listOf("""{"count":"2"}""", """{"count":2.5}""", """{"label":2}""", """{"extra":1}""")) {
            assertFailsWith<ConfigurationException.DecodeFailed>(text) { JsonConfigurationFormat.parse(text).decode<Worker>() }
        }
        assertEquals(Worker(), JsonConfigurationFormat.parse("""{"count":null}""").decode<Worker>())
        for (text in listOf("", "null", "[]", "{broken", """{"count":1,}""")) {
            assertFailsWith<ConfigurationException.ParseFailed>(text) { JsonConfigurationFormat.parse(text) }
        }
    }

    @Serializable data class Worker(val count: Int = 4, val label: String = "default")
}
