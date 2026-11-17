package kim.jade.chassis.config.json

import kim.jade.chassis.config.ConfigurationException
import kotlin.test.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

class JsonDocumentContractTest {
    @Serializable data class Db(val host: String = "localhost", val port: Int = 5432)

    private val document = JsonConfigurationFormat.parse(
        """{"catalog":{"name":"books","limit":3,"tags":["a","b"],"db":{"host":"h"},"unset":null,"servers":[{"host":"x","bogus":1}]}}""",
        "app.json",
    )

    @Test fun `source is the label the document was read with`() {
        assertEquals("app.json", document.source)
    }

    @Test fun `root is the whole document as a JSON tree`() {
        assertEquals(
            Json.parseToJsonElement(
                """{"catalog":{"name":"books","limit":3,"tags":["a","b"],"db":{"host":"h"},"unset":null,"servers":[{"host":"x","bogus":1}]}}""",
            ),
            document.root,
        )
    }

    @Test fun `decode reads the value at a path with the strict checks`() {
        assertEquals(3, document.decode(Int.serializer(), listOf("catalog", "limit")))
        assertEquals(listOf("a", "b"), document.decode(ListSerializer(String.serializer()), listOf("catalog", "tags")))
        assertEquals(Db("h", 5432), document.decode(Db.serializer(), listOf("catalog", "db")))
        val wrongType = assertFailsWith<ConfigurationException.DecodeFailed> {
            document.decode(Int.serializer(), listOf("catalog", "name"))
        }
        assertEquals("app.json", wrongType.source)
        assertEquals(listOf("catalog", "name"), wrongType.path)
        assertFailsWith<ConfigurationException.DecodeFailed> {
            document.decode(ListSerializer(Db.serializer()), listOf("catalog", "servers"))
        }
    }

    @Test fun `decode reads a missing path as an empty table`() {
        assertEquals(Db(), document.decode(Db.serializer(), listOf("catalog", "nowhere")))
        assertFailsWith<ConfigurationException.DecodeFailed> {
            document.decode(Int.serializer(), listOf("catalog", "nowhere"))
        }
    }

    @Test fun `decode reads an explicit null and a path below it as an empty table`() {
        for (path in listOf(listOf("catalog", "unset"), listOf("catalog", "unset", "deeper"))) {
            assertEquals(Db(), document.decode(Db.serializer(), path))
            val scalar = assertFailsWith<ConfigurationException.DecodeFailed> { document.decode(Int.serializer(), path) }
            assertEquals("app.json", scalar.source)
            assertEquals(path, scalar.path)
        }
    }
}
