package kim.jade.chassis.config.toml

import kim.jade.chassis.config.document.ConfigurationDocument
import kim.jade.chassis.config.document.decode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.Serializable

class ConfigurationDocumentTest {
    @Test fun `typed document decode preserves nested and literal path segments`() {
        val document: ConfigurationDocument = TomlConfiguration.parse("""
            [company.worker]
            values = [{ count = 1 }, { count = 2 }]
            ["company.worker"]
            values = [{ count = 4 }]
        """.trimIndent())
        val nested: Box<Item> = document.decode<Box<Item>>("company", "worker")
        val literal: Box<Item> = document.decode<Box<Item>>(listOf("company.worker"))
        assertEquals(listOf(Item(1), Item(2)), nested.values)
        assertEquals(listOf(Item(4)), literal.values)
    }

    @Test fun `typed root decode retains generic arguments through interface references`() {
        val document: ConfigurationDocument = TomlConfiguration.parse("values = [1, 2]")
        val root: Box<Int> = document.decode<Box<Int>>()
        val values: List<Int> = document.decode<List<Int>>(listOf("values"))
        assertEquals(Box(listOf(1, 2)), root)
        assertEquals(listOf(1, 2), values)
    }

    @Serializable data class Box<T>(val values: List<T>)
    @Serializable data class Item(val count: Int)
}
