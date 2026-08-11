package kim.jade.chassis.config.json

import kim.jade.chassis.config.document.ConfigurationDocument
import kim.jade.chassis.config.document.ConfigurationReader
import java.io.File

class JsonConfigurationReader : ConfigurationReader {
    override val extensions = setOf("json")
    override fun read(file: File): ConfigurationDocument = JsonConfigurationFormat.read(file, source = file.path)
}
