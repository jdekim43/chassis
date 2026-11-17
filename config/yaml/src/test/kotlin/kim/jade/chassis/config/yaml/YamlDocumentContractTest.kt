package kim.jade.chassis.config.yaml

import kotlin.test.*
import kotlinx.serialization.json.Json

class YamlDocumentContractTest {
    private val document = YamlConfiguration.parse(
        """
        catalog:
          name: books
          limit: 3
          tags: [a, b]
          db:
            host: h
          unset: null
          servers:
            - host: x
              bogus: 1
        """.trimIndent(),
        "app.yaml",
    )

    @Test fun `source is the label the document was read with`() {
        assertEquals("app.yaml", document.source)
    }

    @Test fun `root is the whole document as a JSON tree`() {
        assertEquals(
            Json.parseToJsonElement(
                """{"catalog":{"name":"books","limit":3,"tags":["a","b"],"db":{"host":"h"},"unset":null,"servers":[{"host":"x","bogus":1}]}}""",
            ),
            document.root,
        )
    }
}
