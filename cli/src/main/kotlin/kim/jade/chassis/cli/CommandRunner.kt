package kim.jade.chassis.cli

import com.github.ajalt.clikt.core.CliktError
import kim.jade.chassis.Application
import kim.jade.chassis.timedInCoroutine
import kim.jade.kotlinx.logger.Logger
import kim.jade.kotlinx.logger.context.asCoroutineLogContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.core.error.InstanceCreationException

internal class CommandRunner(private val application: Application) {
    private val logger = Logger.named("kim.jade.chassis.cli")
    @Volatile private var job: Job? = null

    suspend fun launch() {
        val chain = application.attributes.getOrNull(InvocationChain) ?: return
        val name = chain.last().command.commandName
        val commandPath = chain.drop(1).joinToString(" ") { it.command.commandName }
        val logContext = application.logContext + mapOf("command" to commandPath)
        val scope = CoroutineScope(SupervisorJob() + currentCoroutineContext().minusKey(Job))
        job = scope.launch(logContext.asCoroutineLogContext()) {
            var commandFailure: Throwable? = null
            var cancellation: CancellationException? = null
            try {
                logger.timedInCoroutine("Command $name") { chain.forEach { it.command.run() } }
            } catch (cancelled: CancellationException) {
                if (isActive) commandFailure = IllegalStateException("Command cancelled itself", cancelled)
                else cancellation = cancelled
            } catch (failure: Throwable) {
                commandFailure = unwrapCreationFailure(failure)
            }
            val closeFailures = mutableListOf<Throwable>()
            for (invocation in chain.asReversed()) {
                try {
                    invocation.command.currentContext.close()
                } catch (failure: Throwable) {
                    closeFailures += failure
                }
            }
            val zeroResult = commandFailure is CliktError && commandFailure.statusCode == 0
            val failures = if (zeroResult && closeFailures.isNotEmpty()) {
                closeFailures
            } else {
                listOfNotNull(commandFailure) + closeFailures
            }
            val cause = failures.firstOrNull()
            failures.drop(1).filter { it !== cause }.forEach { cause?.addSuppressed(it) }
            if (cause == null) {
                application.requestStop(reason = "command $name finished")
            } else {
                if (cause !is CliktError) {
                    logger.error {
                        withCoroutine()
                        exception(cause)
                        "Command failed: $name"
                    }
                }
                application.attributes[CommandFailure] = cause
                val failed = cause.takeUnless { it is CliktError && it.statusCode == 0 }
                application.requestStop(failed, "command $name " + if (failed == null) "finished" else "failed")
            }
            cancellation?.let { throw it }
        }
    }

    suspend fun cancelAndJoin() {
        job?.cancelAndJoin()
    }
}

private fun unwrapCreationFailure(failure: Throwable): Throwable {
    var current = failure
    while (current is InstanceCreationException) current = current.cause ?: break
    return current
}
