package kim.jade.chassis.config.internal

import kim.jade.chassis.config.ConfigurationException
import kim.jade.chassis.config.document.ConfigurationDocument
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

// 앞 문서 우선
internal class MergedDocuments(val documents: List<ConfigurationDocument>) {
    val sources: String = documents.joinToString(" + ") { it.source }.ifEmpty { "defaults" }

    private val tree: Table by lazy {
        Table(sources).also { table -> documents.asReversed().forEach { overlay(table, it.root, it.source) } }
    }

    val root: JsonObject by lazy { tree.toJson() }

    fun source(path: List<String>): String? {
        var node: Node = tree
        for (name in path) node = (node as? Table)?.entries?.get(name) ?: return null
        return node.source
    }

    fun <T> read(deserializer: DeserializationStrategy<T>, path: List<String>): T {
        val descriptor = deserializer.descriptor
        val section = section(path) ?: when {
            descriptor.isTable() -> JsonObject(emptyMap())
            else -> fail(sources, path, "No value is present")
        }
        return try {
            json.decodeFromJsonElement(deserializer, checkTypes(section, descriptor))
        } catch (mismatch: TypeMismatch) {
            val at = path + mismatch.place
            throw ConfigurationException.DecodeFailed(source(at) ?: sources, at, mismatch, mismatch.reason)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ConfigurationException) {
            throw failure
        } catch (failure: Exception) {
            throw ConfigurationException.DecodeFailed(sources, path, failure)
        }
    }

    private fun section(path: List<String>): JsonElement? {
        var node: JsonElement = root
        for ((index, name) in path.withIndex()) {
            if (node !is JsonObject) {
                val above = path.take(index)
                fail(source(above) ?: sources, above, "Expected a table")
            }
            node = node[name] ?: return null
        }
        return node
    }

    private fun fail(source: String, path: List<String>, message: String): Nothing =
        throw ConfigurationException.DecodeFailed(
            source, path, SerializationException("$message at '${path.display()}'"), message,
        )

    private companion object {
        val json = Json {
            ignoreUnknownKeys = false
            coerceInputValues = false
        }
    }
}

private sealed interface Node { val source: String }
private class Leaf(val element: JsonElement, override val source: String) : Node
private class Table(override var source: String) : Node { val entries = LinkedHashMap<String, Node>() }

private fun overlay(table: Table, values: JsonObject, source: String) {
    table.source = source
    for ((key, value) in values) when (value) {
        JsonNull -> continue
        is JsonObject -> overlay((table.entries[key] as? Table) ?: Table(source).also { table.entries[key] = it }, value, source)
        else -> table.entries[key] = Leaf(value, source)
    }
}

private fun Node.toJson(): JsonElement = when (this) {
    is Leaf -> element
    is Table -> toJson()
}

private fun Table.toJson(): JsonObject =
    JsonObject(Collections.unmodifiableMap(entries.mapValuesTo(LinkedHashMap()) { (_, node) -> node.toJson() }))

private fun SerialDescriptor.isTable() =
    !isInline && (kind == StructureKind.CLASS || kind == StructureKind.OBJECT || kind == StructureKind.MAP)

private fun List<String>.display(): String = joinToString(".").ifEmpty { "<root>" }
