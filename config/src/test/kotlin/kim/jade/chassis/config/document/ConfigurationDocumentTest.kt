package kim.jade.chassis.config.document

import kim.jade.chassis.config.ConfigurationException
import kim.jade.chassis.config.json.JsonConfigurationFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class ConfigurationDocumentTest {
    @Serializable data class Db(val host: String = "localhost", val port: Int = 5432)
    @Serializable data class Catalog(val name: String = "default", val db: Db = Db(), val tags: List<String> = emptyList())

    private val document = JsonConfigurationFormat.parse(
        """{"catalog":{"name":"books","db":{"port":1},"tags":["a","b"],"unset":null},"empty":null,"scalar":3}""", "app",
    )

    @Test fun `decode reads classes scalars lists and maps at a path`() {
        assertEquals("books", document.decode<String>("catalog", "name"))
        assertEquals(Db("localhost", 1), document.decode<Db>("catalog", "db"))
        assertEquals(listOf("a", "b"), document.decode<List<String>>(listOf("catalog", "tags")))
        assertEquals(mapOf("port" to 1), document.decode<Map<String, Int>>("catalog", "db"))
    }

    @Test fun `a missing or null path gives a class its defaults and fails a scalar`() {
        for (path in listOf(listOf("missing"), listOf("empty"), listOf("empty", "below"), listOf("catalog", "unset"))) {
            assertEquals(Db(), document.decode<Db>(path), path.toString())
            val failure = assertFailsWith<ConfigurationException.DecodeFailed>(path.toString()) { document.decode<Int>(path) }
            assertEquals("app", failure.source)
            assertEquals(path, failure.path)
        }
    }

    @Test fun `a path through a value that is not a table fails at that value`() {
        val failure = assertFailsWith<ConfigurationException.DecodeFailed> { document.decode<Db>("scalar", "db") }
        assertEquals("app", failure.source)
        assertEquals(listOf("scalar"), failure.path)
    }

    @Test fun `a wrong value is reported at its own path`() {
        val failure = assertFailsWith<ConfigurationException.DecodeFailed> {
            JsonConfigurationFormat.parse("""{"catalog":{"db":{"port":"x"}}}""", "app").decode<Catalog>("catalog")
        }
        assertEquals("app", failure.source)
        assertEquals(listOf("catalog", "db", "port"), failure.path)
    }

    @Test fun `decode reads exactly what a resolver holding only this document reads`() {
        val alone = JsonConfigurationFormat.parse("""{"catalog":{"name":null,"db":{"host":null,"port":7}}}""", "app")
        assertEquals(Catalog("default", Db("localhost", 7)), alone.decode<Catalog>("catalog"))
    }

    @Test fun `a document keeps its own copy of the tree`() {
        val inner = mutableMapOf<String, JsonElement>("port" to JsonPrimitive(1))
        val outer = mutableMapOf<String, JsonElement>("db" to JsonObject(inner))
        val document = ConfigurationDocument(JsonObject(outer), "app")
        inner["port"] = JsonPrimitive(2)
        outer["extra"] = JsonPrimitive(true)
        assertEquals(Json.parseToJsonElement("""{"db":{"port":1}}"""), document.root)
    }

    @Test fun `the tree cannot be changed through a cast`() {
        val catalog = document.root["catalog"] as JsonObject
        val tags = catalog["tags"] as JsonArray
        assertFailsWith<UnsupportedOperationException> { (document.root.keys as MutableSet<String>).remove("scalar") }
        assertFailsWith<UnsupportedOperationException> { (catalog.entries as MutableSet<*>).clear() }
        assertFailsWith<UnsupportedOperationException> {
            (catalog.entries.first() as MutableMap.MutableEntry<String, JsonElement>).setValue(JsonPrimitive(1))
        }
        assertFailsWith<UnsupportedOperationException> { (catalog.values as MutableCollection<*>).clear() }
        assertFailsWith<UnsupportedOperationException> {
            val iterator = tags.iterator() as MutableIterator<JsonElement>
            iterator.next()
            iterator.remove()
        }
        assertFailsWith<UnsupportedOperationException> { (tags.subList(0, 1) as MutableList<JsonElement>).clear() }
        assertEquals(Json.parseToJsonElement("""["a","b"]"""), tags)
    }
}
