package kim.jade.chassis.config.document

import kim.jade.chassis.config.ConfigurationException
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConfigurationDocumentLoadTest {
    private val dir = createTempDirectory("load-configuration")
    @AfterTest fun cleanup() { dir.toFile().deleteRecursively() }

    private fun file(name: String, text: String) = dir.resolve(name).toFile().apply { writeText(text) }

    @Test fun `a json file is read with chassis-config alone, ignoring extension case`() {
        for (name in listOf("app.json", "app.JSON")) {
            val file = file(name, """{"catalog":{"name":"books"}}""")
            val document = ConfigurationDocument.load(file)
            assertEquals("books", document.decode<String>("catalog", "name"))
            assertEquals(file.path, document.source)
        }
    }

    @Test fun `an extension without a reader is rejected`() {
        val failure = assertFailsWith<ConfigurationException.UnsupportedFormat> { ConfigurationDocument.load(file("app.unknown", "{}")) }
        assertEquals("unknown", failure.extension)
    }
}
