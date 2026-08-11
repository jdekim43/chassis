package kim.jade.chassis.config.toml

import dev.eav.tomlkt.*
import kim.jade.chassis.config.document.ConfigurationFormat
import kotlinx.serialization.json.JsonObject

object TomlConfiguration : ConfigurationFormat() {
    override fun parseTree(text: String): JsonObject = Toml.parseToTomlTable(text).toJsonObject()
}
