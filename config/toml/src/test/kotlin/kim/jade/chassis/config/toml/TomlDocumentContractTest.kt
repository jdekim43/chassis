package kim.jade.chassis.config.toml

import kotlin.test.*

class TomlDocumentContractTest {
    private val document = TomlConfiguration.parse("[catalog]\nname = \"books\"", "app.toml")

    @Test fun `source is the label the document was read with`() {
        assertEquals("app.toml", document.source)
    }
}
