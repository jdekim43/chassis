package kim.jade.chassis.config.internal

import kim.jade.chassis.config.json.JsonConfigurationFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

class MergedDocumentsTest {
    private fun merged(vararg lowestFirst: Pair<String, String>) =
        MergedDocuments(lowestFirst.map { (source, json) -> JsonConfigurationFormat.parse(json, source) }.reversed())
    private fun json(text: String) = Json.parseToJsonElement(text)

    @Test fun `tables layer key by key and anything else is taken whole`() {
        val merged = merged(
            "low" to """{"a":{"x":1,"y":1,"list":[1,2]},"b":"low"}""",
            "high" to """{"a":{"y":2,"z":2,"list":[3]},"c":"high"}""",
        )
        assertEquals(json("""{"a":{"x":1,"y":2,"list":[3],"z":2},"b":"low","c":"high"}"""), merged.root)
        assertEquals(listOf("a", "b", "c"), merged.root.keys.toList())
        assertEquals(listOf("x", "y", "list", "z"), (merged.root["a"] as JsonObject).keys.toList())
    }

    @Test fun `each path remembers the document its value came from`() {
        val merged = merged(
            "low" to """{"a":{"x":1,"y":1,"list":[1,2]},"b":"low"}""",
            "high" to """{"a":{"y":2},"c":"high"}""",
        )
        assertEquals("low", merged.source(listOf("a", "x")))
        assertEquals("high", merged.source(listOf("a", "y")))
        assertEquals("low", merged.source(listOf("a", "list")))
        assertEquals("high", merged.source(listOf("a")))
        assertEquals("low", merged.source(listOf("b")))
        assertNull(merged.source(listOf("a", "missing")))
        assertEquals("high + low", merged.sources)
    }

    @Test fun `a null entry is not written and does not hide or order anything`() {
        val merged = merged(
            "low" to """{"first":null,"kept":"low","second":1}""",
            "high" to """{"kept":null,"first":"high","gone":null}""",
        )
        assertEquals(json("""{"kept":"low","second":1,"first":"high"}"""), merged.root)
        assertEquals(listOf("kept", "second", "first"), merged.root.keys.toList())
        assertEquals("low", merged.source(listOf("kept")))
    }

    @Test fun `nulls inside a list are left alone`() {
        val merged = merged("a" to """{"list":[null,{"x":null,"y":1}]}""")
        assertEquals(json("""{"list":[null,{"x":null,"y":1}]}"""), merged.root)
    }

    @Test fun `a scalar hides a table below it and a table hides a scalar below it`() {
        val merged = merged(
            "low" to """{"k":{"fromLow":1},"m":"scalar"}""",
            "mid" to """{"k":"scalar"}""",
            "high" to """{"k":{"fromHigh":1},"m":{"t":1}}""",
        )
        assertEquals(json("""{"k":{"fromHigh":1},"m":{"t":1}}"""), merged.root)
        assertEquals(listOf("k", "m"), merged.root.keys.toList())
        assertEquals("high", merged.source(listOf("k")))
    }

    @Test fun `a table that hides a scalar keeps the scalar's place in the key order`() {
        val merged = merged("low" to """{"m":"s","z":1}""", "high" to """{"m":{"t":1}}""")
        assertEquals(listOf("m", "z"), merged.root.keys.toList())
        assertEquals("high", merged.source(listOf("m")))
    }

    @Test fun `an empty table above does not wipe the table below`() {
        val merged = merged("low" to """{"db":{"host":"a"}}""", "high" to """{"db":{},"empty":{}}""")
        assertEquals(json("""{"db":{"host":"a"},"empty":{}}"""), merged.root)
    }

    @Test fun `an empty table above a table takes the table's origin`() {
        val merged = merged("low" to """{"db":{"host":"a"}}""", "high" to """{"db":{}}""")
        assertEquals("high", merged.source(listOf("db")))
        assertEquals("low", merged.source(listOf("db", "host")))
    }

    @Test fun `the merged tree cannot be changed through a cast`() {
        val merged = merged("low" to """{"a":{"x":1}}""", "high" to """{"a":{"y":2},"b":2}""")
        assertFailsWith<UnsupportedOperationException> { (merged.root.keys as MutableSet<String>).remove("b") }
        assertFailsWith<UnsupportedOperationException> { ((merged.root["a"] as JsonObject).values as MutableCollection<*>).clear() }
        assertEquals(json("""{"a":{"x":1,"y":2},"b":2}"""), merged.root)
    }

    @Test fun `without documents the root is empty and the source is defaults`() {
        val merged = MergedDocuments(emptyList())
        assertEquals(JsonObject(emptyMap()), merged.root)
        assertEquals("defaults", merged.sources)
    }
}
