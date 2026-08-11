package kim.jade.chassis.config.internal

import kotlin.time.Duration
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonIgnoreUnknownKeys
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

internal class TypeMismatch(val place: List<String>, val reason: String, message: String) : SerializationException(message)

internal fun checkTypes(element: JsonElement, descriptor: SerialDescriptor): JsonElement =
    checkValue(element, descriptor, Place("", emptyList(), inList = false))

private class Place(val text: String, val keys: List<String>, val inList: Boolean) {
    fun field(name: String) = Place("$text.$name", if (inList) keys else keys + name, inList)
    fun item(index: Int) = Place("$text[$index]", keys, inList = true)

    // 리스트 안쪽은 keys가 원소를 가리키지 못하므로 이유에 위치를 싣는다.
    fun fail(reason: String): Nothing {
        val located = if (text.isEmpty()) reason else "$reason at '$text'"
        throw TypeMismatch(keys, if (inList) located else reason, located)
    }
}

private fun checkValue(element: JsonElement, descriptor: SerialDescriptor, place: Place): JsonElement {
    if (element === JsonNull) {
        if (!descriptor.isNullable) place.fail("Expected a non-null value")
        return element
    }
    if (descriptor.isInline) return checkValue(element, descriptor.getElementDescriptor(0), place)
    val primitive = element as? JsonPrimitive
    return when (descriptor.kind) {
        PrimitiveKind.BOOLEAN -> {
            if (primitive == null || primitive.isString || primitive.booleanOrNull == null) place.fail("Expected a boolean")
            element
        }
        PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG,
        PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> {
            if (primitive == null || primitive.isString || primitive.booleanOrNull != null) place.fail("Expected a number")
            element
        }
        PrimitiveKind.CHAR, PrimitiveKind.STRING, SerialKind.ENUM -> {
            if (primitive == null || !primitive.isString) place.fail("Expected a string")
            if (descriptor.isDuration()) isoDuration(primitive, place) else element
        }
        StructureKind.LIST -> {
            if (element !is JsonArray) place.fail("Expected an array")
            JsonArray(element.mapIndexed { index, item -> checkValue(item, descriptor.getElementDescriptor(0), place.item(index)) })
        }
        StructureKind.MAP -> {
            if (element !is JsonObject) place.fail("Expected a table")
            JsonObject(element.mapValues { (key, value) -> checkValue(value, descriptor.getElementDescriptor(1), place.field(key)) })
        }
        StructureKind.CLASS, StructureKind.OBJECT -> {
            if (element !is JsonObject) place.fail("Expected a table")
            val checked = LinkedHashMap(element)
            val known = HashSet<String>()
            for (index in 0 until descriptor.elementsCount) {
                val declared = descriptor.getElementName(index)
                known += declared
                known += descriptor.aliases(index)
                val name = element.fieldName(descriptor, index)
                if (name == null) {
                    if (!descriptor.isElementOptional(index)) place.field(declared).fail("Required value is missing")
                    continue
                }
                checked[name] = checkValue(element.getValue(name), descriptor.getElementDescriptor(index), place.field(name))
            }
            if (!descriptor.ignoresUnknownKeys()) {
                element.keys.firstOrNull { it !in known }?.let { place.field(it).fail("Unknown key") }
            }
            JsonObject(checked)
        }
        else -> place.fail("Configuration requires a concrete serialization schema")
    }
}

@OptIn(ExperimentalSerializationApi::class)
private fun SerialDescriptor.ignoresUnknownKeys() = annotations.any { it is JsonIgnoreUnknownKeys }

private fun SerialDescriptor.isDuration() = serialName.removeSuffix("?") == "kotlin.time.Duration"

// kotlinx.serialization의 Duration은 ISO 표기만 읽는다.
private fun isoDuration(text: JsonPrimitive, place: Place): JsonElement {
    val duration = Duration.parseOrNull(text.content) ?: place.fail("Expected a duration such as '30s' or 'PT30S'")
    return JsonPrimitive(duration.toIsoString())
}

@OptIn(ExperimentalSerializationApi::class)
private fun JsonObject.fieldName(descriptor: SerialDescriptor, index: Int): String? {
    val name = descriptor.getElementName(index)
    if (name in this) return name
    val aliases = descriptor.aliases(index)
    return keys.firstOrNull { it in aliases }
}

@OptIn(ExperimentalSerializationApi::class)
private fun SerialDescriptor.aliases(index: Int): List<String> =
    getElementAnnotations(index).filterIsInstance<JsonNames>().flatMap { it.names.toList() }
