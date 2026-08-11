package kim.jade.chassis.hook

import kim.jade.chassis.timed
import kim.jade.kotlinx.logger.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class HookRegistry {
    private class Hook(val name: String, val block: suspend () -> Unit)

    private val lock = ReentrantLock()
    private val hooks = HookPhase.entries.associateWith { mutableListOf<Hook>() }
    private var sealed = false

    fun register(phase: HookPhase, name: String?, block: suspend () -> Unit) {
        lock.withLock {
            val list = hooks.getValue(phase)
            val hookName = name ?: "$phase#${list.size}"
            check(!sealed) {
                "Hook registered after startup: $phase/$hookName. " +
                    "Declare the definition with createdAtStart = true or resolve it inside hook { }."
            }
            list += Hook(hookName, block)
        }
    }

    fun seal() {
        lock.withLock { sealed = true }
    }

    suspend fun run(phase: HookPhase, onShutdownFailure: (Throwable) -> Unit = {}) {
        val snapshot = lock.withLock { hooks.getValue(phase).toList() }
        val ordered = if (phase.isLaunchPhase) snapshot else snapshot.asReversed()
        for (hook in ordered) {
            currentCoroutineContext().ensureActive()
            try {
                logger.timed("Hook $phase/${hook.name}") { coroutineScope { hook.block() } }
            } catch (e: Throwable) {
                val failure = if (e is CancellationException) {
                    currentCoroutineContext().ensureActive()
                    IllegalStateException("Hook $phase/${hook.name} cancelled itself", e)
                } else e
                logger.error("Hook failed: $phase/${hook.name}", exception = failure)
                if (phase.isLaunchPhase) throw failure
                onShutdownFailure(failure)
            }
        }
    }

    private companion object {
        val logger = Logger.named("kim.jade.chassis.hook")
    }
}
