package example.catalog

import kim.jade.chassis.cli.runCli
import kim.jade.chassis.config.cli.ConfigOption
import kotlin.system.exitProcess

suspend fun catalog(arguments: Array<String>): Int =
    runCli("catalog", arguments, listOf(ListCommand(), ServeCommand()), catalogModule, options = listOf(ConfigOption()))

suspend fun main(arguments: Array<String>) {
    exitProcess(catalog(arguments))
}
