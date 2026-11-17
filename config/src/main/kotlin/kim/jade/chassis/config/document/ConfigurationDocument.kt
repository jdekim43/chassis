package kim.jade.chassis.config.document

import kim.jade.chassis.config.ConfigurationException
import kim.jade.chassis.config.internal.MergedDocuments
import java.io.File
import java.util.Collections
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

class ConfigurationDocument(root: JsonObject, val source: String) {
    val root: JsonObject = root.deepCopy()

    private val view by lazy { MergedDocuments(listOf(this)) }

    fun <T> decode(deserializer: DeserializationStrategy<T>, path: List<String>): T = view.read(deserializer, path)

    companion object {
        fun load(file: File): ConfigurationDocument {
            val reader = ConfigurationReader.loadAll()[file.extension.lowercase()]
                ?: throw ConfigurationException.UnsupportedFormat(file.path, file.extension)
            return reader.read(file)
        }
    }
}

private fun JsonObject.deepCopy(): JsonObject =
    JsonObject(Collections.unmodifiableMap(mapValuesTo(LinkedHashMap()) { (_, value) -> value.deepCopy() }))

private fun JsonElement.deepCopy(): JsonElement = when (this) {
    is JsonObject -> deepCopy()
    is JsonArray -> JsonArray(Collections.unmodifiableList(map { it.deepCopy() }))
    else -> this
}

inline fun <reified T> ConfigurationDocument.decode(section: List<String>): T =
    decode(serializer<T>(), section)

inline fun <reified T> ConfigurationDocument.decode(vararg section: String): T =
    decode<T>(section.toList())
