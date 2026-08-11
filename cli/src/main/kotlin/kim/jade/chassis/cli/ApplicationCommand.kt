package kim.jade.chassis.cli

import com.github.ajalt.clikt.command.CoreSuspendingCliktCommand
import kim.jade.chassis.Application

internal const val APPLICATION_CONTEXT_KEY = "kim.jade.chassis.cli.application"

abstract class ApplicationCommand(name: String? = null) : CoreSuspendingCliktCommand(name) {
    final override suspend fun run() {
        val application = currentContext.data[APPLICATION_CONTEXT_KEY] as? Application
            ?: throw IllegalStateException("ApplicationCommand must be run by runCli")
        run(application)
    }

    abstract suspend fun run(application: Application)
}
