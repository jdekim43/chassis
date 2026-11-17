package kim.jade.chassis.config.cli

import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import kim.jade.chassis.Application
import kim.jade.chassis.cli.GlobalOptions
import kim.jade.chassis.config.configurations
import kim.jade.chassis.config.document.ConfigurationDocument

class ConfigOption : GlobalOptions() {
    private val files by option("--config", help = "Configuration file; repeat to apply overrides in order")
        .file(mustExist = true, canBeDir = false, mustBeReadable = true)
        .multiple()

    private var loaded: List<ConfigurationDocument> = emptyList()

    override fun prepare() {
        loaded = files.map { ConfigurationDocument.load(it) }
    }

    override fun apply(application: Application) {
        loaded.forEach(application.configurations::add)
    }
}
