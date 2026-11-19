package kim.jade.chassis

import kim.jade.chassis.hook.HookPhase
import kim.jade.chassis.hook.HookRegistry
import kim.jade.chassis.hook.runHookBlocks
import kim.jade.kotlinx.logger.Logger
import kim.jade.kotlinx.logger.context.CoroutineThreadLogContext
import kim.jade.kotlinx.logger.context.EmptyLogContext
import kim.jade.kotlinx.logger.context.GlobalLogContext
import kim.jade.kotlinx.logger.context.LogContext
import kim.jade.kotlinx.logger.context.asCoroutineLogContext
import kim.jade.kotlinx.logger.context.logContext
import kim.jade.kotlinx.logger.context.withLogContext
import kim.jade.kotlinx.logger.integration.koin.KoinLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.Koin
import org.koin.core.KoinApplication
import org.koin.core.error.InstanceCreationException
import org.koin.core.module.Module
import org.koin.dsl.module
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class Application(
    modules: List<Module>,
    val name: String = "application",
    val shutdownTimeout: Duration = 30.seconds,
    context: CoroutineContext = Dispatchers.Default,
    logContext: LogContext = EmptyLogContext,
) {
    constructor(vararg modules: Module) : this(modules.toList())

    val logContext: LogContext = LogContext(
        context.logContext() + ("application" to name) + logContext,
    )

    private val logger = Logger.named(name)
    private val scope = run {
        val thread = context[CoroutineThreadLogContext]?.data?.clone()
        val bridge = thread?.let(::CoroutineThreadLogContext) ?: CoroutineThreadLogContext(context = null)
        CoroutineScope(context.minusKey(Job) + SupervisorJob() + this.logContext.asCoroutineLogContext(bridge))
    }
    private val dispatcher = context[ContinuationInterceptor] ?: Dispatchers.Default
    private val registry = HookRegistry()
    private val koinApplication = KoinApplication.init().apply {
        logger(KoinLogger())
        modules(module {
            single { this@Application }
            single { registry }
        })
        modules(modules)
    }

    private val lock = ReentrantLock()
    private var currentStatus = ApplicationStatus.NEW
    private val inStatus = Stopwatch()
    private var startJob: Job? = null
    private val failures = mutableListOf<Throwable>()
    private val active = CompletableDeferred<Unit>()
    private val terminated = CompletableDeferred<Unit>()

    val status: ApplicationStatus get() = lock.withLock { currentStatus }
    val koin: Koin get() = koinApplication.koin

    val coroutineContext: CoroutineContext get() = scope.coroutineContext

    val attributes = Attributes()

    fun requestStart() {
        val job = lock.withLock {
            check(currentStatus == ApplicationStatus.NEW) { "Application cannot start from $currentStatus" }
            transition(ApplicationStatus.STARTING)
            scope.launch(start = CoroutineStart.LAZY) { runStartup() }.also { startJob = it }
        }
        job.start()
    }

    suspend fun awaitActive() = withContext(dispatcher) { active.await() }

    suspend fun start() {
        requestStart()
        awaitActive()
    }

    fun requestStop(cause: Throwable? = null, reason: String? = null) {
        val (previous, job) = lock.withLock {
            val previous = currentStatus
            if (previous == ApplicationStatus.STOPPED || previous == ApplicationStatus.FAILED) return
            if (cause != null) record(cause)
            if (previous == ApplicationStatus.STOPPING) return
            val why = reason ?: cause?.let { "${it::class.simpleName}: ${it.message}" }
            logger.info {
                context = GlobalLogContext + logContext
                context("status" to previous.name)

                "Application $name: stop requested" + if (why == null) "" else ": $why"
            }
            transition(ApplicationStatus.STOPPING)
            previous to startJob
        }
        job?.cancel()
        scope.launch { runShutdown(previous, job) }
    }

    suspend fun awaitTermination() = withContext(dispatcher) { terminated.await() }

    suspend fun stop() {
        requestStop()
        awaitTermination()
    }

    private suspend fun runStartup() {
        try {
            try {
                logger.timedInCoroutine("Instance creation") {
                    koin.createEagerInstances()
                    koin.runHookBlocks()
                }
            } catch (e: Throwable) {
                if (e !is CancellationException) {
                    logger.error {
                        withCoroutine()
                        exception(unwrapCreationFailure(e))
                        "Application $name: creating instances failed"
                    }
                }
                throw e
            }
            registry.seal()
            runPhase(HookPhase.Initialize)
            runPhase(HookPhase.Start)
            val becameActive = lock.withLock {
                if (currentStatus == ApplicationStatus.STARTING) {
                    transition(ApplicationStatus.ACTIVE)
                    true
                } else false
            }
            if (!becameActive) return
            active.complete(Unit)
            runPhase(HookPhase.Started)
        } catch (e: CancellationException) {
            val selfCancelled = lock.withLock { currentStatus == ApplicationStatus.STARTING }
            if (selfCancelled) requestStop(IllegalStateException("Startup cancelled itself", e), "startup cancelled itself")
        } catch (e: Throwable) {
            requestStop(unwrapCreationFailure(e), "startup failed")
        }
    }

    private fun unwrapCreationFailure(failure: Throwable): Throwable {
        var current = failure
        while (current is InstanceCreationException) current = current.cause ?: break
        return current
    }

    private suspend fun runShutdown(previous: ApplicationStatus, startJob: Job?) = withContext(NonCancellable) {
        try {
            startJob?.join()
            if (previous != ApplicationStatus.NEW) {
                registry.seal()
                runShutdownPhases()
            }
            try {
                koinApplication.close()
            } catch (e: Throwable) {
                logger.error {
                    withCoroutine()
                    exception(e)
                    "Closing Koin failed"
                }
                record(e)
            }
        } finally {
            finish()
        }
    }

    private suspend fun runPhase(phase: HookPhase, onFailure: (Throwable) -> Unit = {}) =
        withLogContext("phase" to phase.name) {
            logger.timedInCoroutine("$phase hooks") { registry.run(phase, onFailure) }
        }

    private suspend fun runShutdownPhases() = coroutineScope {
        val current = AtomicReference(HookPhase.RequestStop)
        // 취소를 무시하는 Hook이 종료를 붙잡고 있어도 상한을 넘긴 시점에 로그를 남긴다.
        // 종료 절차의 timeout보다 늦지 않도록 UNDISPATCHED로 시작한다.
        val watchdog = launch(start = CoroutineStart.UNDISPATCHED) {
            delay(shutdownTimeout)
            val phase = current.get()
            logger.error {
                withCoroutine()
                context("phase", phase.name)
                "Shutdown exceeded $shutdownTimeout during $phase; " +
                    "waiting for its hook to honour cancellation"
            }
        }
        val completed = withTimeoutOrNull(shutdownTimeout) {
            for (phase in listOf(HookPhase.RequestStop, HookPhase.Stop, HookPhase.Release)) {
                current.set(phase)
                runPhase(phase, ::record)
            }
        }
        if (completed == null) {
            watchdog.join()
            record(ShutdownTimeoutException(current.get()))
        } else {
            watchdog.cancel()
        }
    }

    private fun record(failure: Throwable) {
        val original = failure.original()
        val recorded = if (original is CancellationException) {
            IllegalStateException("Failed with a cancellation", original)
        } else original
        lock.withLock { failures += recorded }
    }

    private fun finish() {
        val primary = lock.withLock {
            transition(if (failures.isEmpty()) ApplicationStatus.STOPPED else ApplicationStatus.FAILED)
            failures.firstOrNull()?.also { primary ->
                val attached = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
                attached += primary
                failures.drop(1).filter { attached.add(it) }.forEach { primary.addSuppressed(it) }
            }
        }
        try {
            if (primary == null) {
                active.completeExceptionally(IllegalStateException("Application stopped before becoming active"))
                terminated.complete(Unit)
            } else {
                active.completeExceptionally(primary)
                terminated.completeExceptionally(primary)
            }
        } finally {
            scope.cancel()
        }
    }

    /** [lock]을 잡은 상태에서만 호출해야함 */
    private fun transition(to: ApplicationStatus) {
        logger.info {
            context = GlobalLogContext + logContext
            context("fromStatus" to currentStatus.name, "toStatus" to to.name)

            "Application $name: $currentStatus -> $to after ${inStatus.lap()}"
        }
        currentStatus = to
    }
}

/**
 * 코루틴 스택 복원(디버그 모드)은 실패를 복사해서 다시 던진다. 복사본은 원본을 cause로 가지고 스택에 `_COROUTINE` 프레임이 있다.
 */
private fun Throwable.original(): Throwable {
    var current = this
    while (true) {
        val cause = current.cause ?: return current
        val recoveredCopy = cause::class == current::class &&
            current.stackTrace.any { it.className.startsWith("_COROUTINE") }
        if (!recoveredCopy) return current
        current = cause
    }
}
