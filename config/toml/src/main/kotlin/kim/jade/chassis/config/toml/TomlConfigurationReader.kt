package kim.jade.chassis.config.toml

import kim.jade.chassis.config.document.ConfigurationDocument
import kim.jade.chassis.config.document.ConfigurationReader
import java.io.File

class TomlConfigurationReader : ConfigurationReader {
    override val extensions: Set<String> = setOf("toml")

    override fun read(file: File): ConfigurationDocument = TomlConfiguration.read(file, source = file.path)
}
