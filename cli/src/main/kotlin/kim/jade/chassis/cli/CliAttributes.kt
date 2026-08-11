package kim.jade.chassis.cli

import com.github.ajalt.clikt.command.CoreSuspendingCliktCommand
import com.github.ajalt.clikt.parsers.CommandInvocation
import kim.jade.chassis.AttributeKey

val RunningCommand = AttributeKey<CoreSuspendingCliktCommand>("chassis.cli.runningCommand")

val CommandLineArguments = AttributeKey<List<String>>("chassis.cli.arguments")

internal val InvocationChain =
    AttributeKey<List<CommandInvocation<CoreSuspendingCliktCommand>>>("chassis.cli.invocationChain")

internal val CommandFailure = AttributeKey<Throwable>("chassis.cli.commandFailure")
