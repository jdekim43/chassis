package kim.jade.chassis.config.toml

import dev.eav.tomlkt.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// place는 StrictTypes와 같은 표기다. 테이블 키는 .name, 배열 원소는 [i]로 잇는다.
internal fun TomlTable.toJsonObject(place: String = ""): JsonObject =
    JsonObject(mapValues { (key, value) -> value.toJsonElement(if (place.isEmpty()) key else "$place.$key") })

private fun TomlElement.toJsonElement(place: String): JsonElement = when (this) {
    TomlNull -> fail("TOML does not define null values", place)
    is TomlTable -> toJsonObject(place)
    is TomlArray -> JsonArray(mapIndexed { index, item -> item.toJsonElement("$place[$index]") })
    is TomlLiteral -> toJsonPrimitive(place)
}

private fun TomlLiteral.toJsonPrimitive(place: String): JsonPrimitive = when (type) {
    TomlLiteral.Type.Boolean -> JsonPrimitive(toBoolean())
    TomlLiteral.Type.Integer -> JsonPrimitive(toLongOrNull() ?: fail("TOML integer does not fit in 64 bits", place))
    TomlLiteral.Type.Float -> toDouble().let {
        if (!it.isFinite()) fail("TOML float is not a finite number", place)
        JsonPrimitive(it)
    }
    TomlLiteral.Type.String,
    TomlLiteral.Type.LocalDateTime,
    TomlLiteral.Type.OffsetDateTime,
    TomlLiteral.Type.LocalDate,
    TomlLiteral.Type.LocalTime -> JsonPrimitive(content)
}

private fun fail(message: String, place: String): Nothing = throw IllegalArgumentException("$message at '$place'")
