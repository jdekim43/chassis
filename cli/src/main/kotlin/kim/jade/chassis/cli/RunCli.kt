package kim.jade.chassis.cli

import com.github.ajalt.clikt.command.CoreSuspendingCliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parsers.CommandInvocation
import kim.jade.chassis.Application
import kim.jade.chassis.ApplicationStatus
import kim.jade.chassis.runApplication
import kim.jade.kotlinx.logger.Logger
import kim.jade.kotlinx.logger.context.CoroutineThreadLogContext
import kim.jade.kotlinx.logger.context.asCoroutineLogContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.koin.core.module.Module
import java.io.IOException

suspend fun runCli(
    name: String,
    arguments: Array<String>,
    commands: List<CoreSuspendingCliktCommand>,
    vararg modules: Module,
    options: List<GlobalOptions> = emptyList(),
): Int = runCli(name, arguments, commands, options) { Application(modules.toList(), name = name) }

suspend fun runCli(
    name: String,
    arguments: Array<String>,
    commands: List<CoreSuspendingCliktCommand>,
    options: List<GlobalOptions> = emptyList(),
    application: () -> Application,
): Int {
    var root: RootCommand? = null
    val chain = try {
        root = RootCommand(name, commands, options)
        parseInvocations(root, arguments.toList())
    } catch (failure: Throwable) {
        return report(root, failure)
    }
    if (chain.size == 1) {
        root.getFormattedHelp()?.let(::println)
        return 0
    }
    try {
        CliLogging.install(root.log)
    } catch (failure: IOException) {
        val reason = failure.message ?: failure::class.java.simpleName
        System.err.println("Error: Cannot open log file '${root.log}': $reason")
        return 2
    }
    return try {
        try {
            options.forEach { it.prepare() }
        } catch (failure: Throwable) {
            return reportOptionFailure(root, failure, "Preparing global options failed")
        }
        val app = application()
        withContext(app.logContext.asCoroutineLogContext(CoroutineThreadLogContext(context = null))) {
            run(root, chain, arguments, options, app)
        }
    } finally {
        CliLogging.restore()
    }
}

private suspend fun run(
    root: RootCommand,
    chain: List<CommandInvocation<CoreSuspendingCliktCommand>>,
    arguments: Array<String>,
    options: List<GlobalOptions>,
    application: Application,
): Int {
    val status = application.status
    if (status != ApplicationStatus.NEW) {
        System.err.println("Error: Application cannot start from $status")
        return 1
    }
    application.koin.loadModules(listOf(cliModule()))
    application.attributes[CommandLineArguments] = arguments.toList()
    application.attributes[RunningCommand] = chain.last().command
    application.attributes[InvocationChain] = chain
    root.currentContext.data[APPLICATION_CONTEXT_KEY] = application
    try {
        options.forEach { it.apply(application) }
    } catch (failure: Throwable) {
        val code = reportOptionFailure(root, failure, "Applying global options failed")
        try {
            application.stop()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (stopFailure: Throwable) {
            report(root, stopFailure)
        }
        return code
    }
    val appFailure = try {
        runApplication(application)
        null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        failure
    }
    val commandFailure = application.attributes.getOrNull(CommandFailure)
    val code = commandFailure?.let { report(root, it) } ?: 0
    return when {
        commandFailure != null && code != 0 -> code
        appFailure != null -> report(root, appFailure)
        else -> code
    }
}

private suspend fun reportOptionFailure(root: RootCommand, failure: Throwable, message: String): Int {
    if (failure is CancellationException) throw failure
    if (failure !is CliktError) {
        Logger.named("kim.jade.chassis.cli").error {
            withCoroutine()
            exception(failure)
            message
        }
    }
    return report(root, failure)
}

private fun report(root: CoreSuspendingCliktCommand?, failure: Throwable): Int {
    if (failure is CliktError && root != null) {
        root.getFormattedHelp(failure)?.let { message ->
            val toStderr = failure is UsageError || failure.printError || failure.statusCode != 0 ||
                (failure is PrintHelpMessage && failure.error)
            if (toStderr) System.err.println(message) else println(message)
        }
        return if (failure is UsageError && failure.statusCode == 1) 2 else failure.statusCode
    }
    System.err.println("Error: ${failure.message ?: failure::class.java.simpleName}")
    return 1
}
