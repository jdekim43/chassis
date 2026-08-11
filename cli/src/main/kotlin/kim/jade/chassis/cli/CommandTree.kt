package kim.jade.chassis.cli

import com.github.ajalt.clikt.command.CoreSuspendingCliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parsers.CommandInvocation
import com.github.ajalt.clikt.parsers.CommandLineParser

internal class RootCommand(
    name: String,
    commands: List<CoreSuspendingCliktCommand>,
    options: List<GlobalOptions> = emptyList(),
) : CoreSuspendingCliktCommand(name) {
    override val invokeWithoutSubcommand = true

    val log: String? by option(
        "--log",
        metavar = "stdout|stderr|file",
        help = "Write logs to stdout, stderr or a file. Without it only errors are written, to stderr.",
    )

    init {
        options.forEach { it.provideDelegate(this, ::log) }
        validateCommands(commands)
        subcommands(commands)
    }

    override suspend fun run() = Unit
}

internal fun validateCommands(commands: List<CoreSuspendingCliktCommand>) = validate(commands, emptyList())

private fun validate(commands: List<CoreSuspendingCliktCommand>, parentPath: List<String>) {
    commands.groupBy { it.commandName }.forEach { (name, sameName) ->
        require(sameName.size == 1) {
            "Duplicate command '${(parentPath + name).joinToString(" ")}': " +
                sameName.joinToString { it::class.qualifiedName ?: it::class.java.name }
        }
    }
    for (command in commands) {
        val path = parentPath + command.commandName
        require(!command.allowMultipleSubcommands) {
            "Command chaining is not supported at '${path.joinToString(" ")}'"
        }
        validate(command.registeredSubcommands(), path)
    }
}

internal fun parseInvocations(
    root: CoreSuspendingCliktCommand,
    arguments: List<String>,
): List<CommandInvocation<CoreSuspendingCliktCommand>> {
    val result = CommandLineParser.parse(root, arguments)
    val chain = generateSequence(result.invocation) { it.subcommandInvocations.singleOrNull() }.toList()
    chain.forEach(CommandLineParser::finalizeEagerOptions)
    chain.forEach(CommandLineParser::finalizeCommand)
    return chain
}
