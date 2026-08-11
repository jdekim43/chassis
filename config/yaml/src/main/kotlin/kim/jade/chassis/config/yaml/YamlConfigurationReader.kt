package kim.jade.chassis.config.yaml

import kim.jade.chassis.config.document.ConfigurationDocument
import kim.jade.chassis.config.document.ConfigurationReader
import java.io.File

class YamlConfigurationReader : ConfigurationReader {
    override val extensions = setOf("yaml", "yml")
    override fun read(file: File): ConfigurationDocument = YamlConfiguration.read(file, source = file.path)
}
