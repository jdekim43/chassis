package kim.jade.chassis.config.json

import kim.jade.chassis.config.document.ConfigurationFormat
import kotlinx.serialization.json.*

object JsonConfigurationFormat : ConfigurationFormat() {
    override fun parseTree(text: String): JsonObject {
        val root = Json.parseToJsonElement(text)
        require(root is JsonObject) { "Configuration root must be an object" }
        return root
    }
}
