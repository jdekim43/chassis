package kim.jade.chassis.hook

import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.core.scope.Scope
import java.util.concurrent.atomic.AtomicLong

private val hookSequence = AtomicLong()

private fun Scope.register(phase: HookPhase, name: String?, block: suspend () -> Unit) =
    get<HookRegistry>().register(phase, name, block)

fun Scope.onInitialize(name: String? = null, block: suspend () -> Unit) = register(HookPhase.Initialize, name, block)
fun Scope.onStart(name: String? = null, block: suspend () -> Unit) = register(HookPhase.Start, name, block)
fun Scope.onStarted(name: String? = null, block: suspend () -> Unit) = register(HookPhase.Started, name, block)
fun Scope.onRequestStop(name: String? = null, block: suspend () -> Unit) = register(HookPhase.RequestStop, name, block)
fun Scope.onStop(name: String? = null, block: suspend () -> Unit) = register(HookPhase.Stop, name, block)
fun Scope.onRelease(name: String? = null, block: suspend () -> Unit) = register(HookPhase.Release, name, block)

fun Module.hook(block: Scope.() -> Unit) {
    val sequence = hookSequence.incrementAndGet()
    factory<HookBlock>(named("chassis.hook.$sequence")) {
        val scope = this
        HookBlock(sequence) { scope.block() }
    }
}

fun Module.onInitialize(name: String? = null, block: suspend () -> Unit) = hook { onInitialize(name, block) }
fun Module.onStart(name: String? = null, block: suspend () -> Unit) = hook { onStart(name, block) }
fun Module.onStarted(name: String? = null, block: suspend () -> Unit) = hook { onStarted(name, block) }
fun Module.onRequestStop(name: String? = null, block: suspend () -> Unit) = hook { onRequestStop(name, block) }
fun Module.onStop(name: String? = null, block: suspend () -> Unit) = hook { onStop(name, block) }
fun Module.onRelease(name: String? = null, block: suspend () -> Unit) = hook { onRelease(name, block) }

internal fun Koin.runHookBlocks() {
    getAll<HookBlock>().sortedBy { it.sequence }.forEach { it.run() }
}
