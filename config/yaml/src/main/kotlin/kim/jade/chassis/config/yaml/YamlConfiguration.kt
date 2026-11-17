package kim.jade.chassis.config.yaml

import kim.jade.chassis.config.document.ConfigurationFormat
import java.io.StringReader
import java.math.BigInteger
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.serialization.json.*
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.AbstractConstruct
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.Tag

object YamlConfiguration : ConfigurationFormat() {
    override fun parseTree(text: String): JsonObject {
        val options = LoaderOptions().apply {
            setAllowDuplicateKeys(false)
            setAllowRecursiveKeys(false)
        }
        val constructor = ConfigurationConstructor(options)
        val node = Yaml(constructor).compose(StringReader(text))
        val value = if (node == null) emptyMap<String, Any?>() else constructor.construct(node)
        val element = toJson(value, "", Collections.newSetFromMap(IdentityHashMap<Any, Boolean>()))
        require(element is JsonObject) { "Configuration root must be a mapping" }
        return element
    }

    // place는 StrictTypes와 같은 표기다. 매핑 키는 .name, 시퀀스 원소는 [i]로 잇는다.
    private fun toJson(value: Any?, place: String, ancestors: MutableSet<Any>): JsonElement = when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Number -> {
            if (value is Double && !value.isFinite() || value is Float && !value.isFinite()) {
                fail("Configuration numbers must be finite", place)
            }
            JsonPrimitive(value)
        }
        is Map<*, *>, is List<*> -> {
            if (!ancestors.add(value)) fail("Recursive YAML aliases are not supported in configuration", place)
            try {
                when (value) {
                    is Map<*, *> -> toJsonObject(value, place, ancestors)
                    else -> JsonArray((value as List<*>).mapIndexed { index, item -> toJson(item, "$place[$index]", ancestors) })
                }
            } finally {
                ancestors.remove(value)
            }
        }
        else -> fail("Unsupported YAML configuration value type", place)
    }

    // 정수 키는 10진수 이름이 된다. 1과 "1"처럼 바꾼 뒤 겹치는 키는 받지 않는다.
    private fun toJsonObject(mapping: Map<*, *>, place: String, ancestors: MutableSet<Any>): JsonObject {
        val entries = LinkedHashMap<String, JsonElement>()
        for ((key, child) in mapping) {
            val name = when (key) {
                is String -> key
                is Int, is Long, is BigInteger -> key.toString()
                else -> fail("Configuration mapping keys must be strings or integers", place)
            }
            val childPlace = if (place.isEmpty()) name else "$place.$name"
            if (name in entries) fail("Duplicate configuration mapping key", childPlace)
            entries[name] = toJson(child, childPlace, ancestors)
        }
        return JsonObject(entries)
    }

    private fun fail(message: String, place: String): Nothing =
        throw IllegalArgumentException("$message at '${place.ifEmpty { "<root>" }}'")
}

/** Construct only standard YAML values; retain timestamp text for serialization-based decoding. */
private class ConfigurationConstructor(options: LoaderOptions) : SafeConstructor(options) {
    init {
        yamlConstructors[Tag.TIMESTAMP] = object : AbstractConstruct() {
            override fun construct(node: Node): Any = (node as ScalarNode).value
        }
    }

    fun construct(node: Node): Any? = constructDocument(node)
}
