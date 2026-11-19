package kim.jade.chassis.worker

import kim.jade.chassis.Application
import kim.jade.chassis.Stopwatch
import kim.jade.kotlinx.logger.Logger
import kim.jade.kotlinx.logger.context.CoroutineLogContext
import kim.jade.kotlinx.logger.context.CoroutineThreadLogContext
import kim.jade.kotlinx.logger.context.asCoroutineLogContext
import kim.jade.kotlinx.logger.context.logContext
import kim.jade.kotlinx.logger.context.withLogContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal class WorkerSupervisor(
    private val application: Application,
    private val workers: List<Worker>,
    private val options: WorkerOptions,
) {
    private val logger = Logger.named("kim.jade.chassis.worker")
    private val scope = run {
        val applicationContext = application.coroutineContext
        val context = applicationContext + options.context.minusKey(Job)
        val thread = context[CoroutineThreadLogContext]?.data?.clone()
        val bridge = thread?.let(::CoroutineThreadLogContext) ?: CoroutineThreadLogContext(context = null)
        val logContext = options.context.logContext().plus(other = applicationContext.logContext())
        CoroutineScope(context + SupervisorJob(applicationContext.job) + logContext.asCoroutineLogContext(bridge))
    }

    fun launch() {
        workers.forEach { worker ->
            val logContext = CoroutineLogContext(scope.coroutineContext.logContext() + ("worker" to worker.name))
            scope.launch(CoroutineName(worker.name) + logContext) {
                logger.info {
                    withCoroutine()
                    "Worker ${worker.name} started"
                }
                val running = Stopwatch()
                try {
                    worker.run()
                    logger.info {
                        withCoroutine()
                        "Worker ${worker.name} finished after ${running.lap()}"
                    }
                } catch (cancelled: CancellationException) {
                    // 아직 active라면 stop()이 아니라 Worker 자신이 취소한 것이다(예: withTimeout 만료).
                    if (!isActive) {
                        logger.info {
                            withCoroutine()
                            "Worker ${worker.name} cancelled after ${running.lap()}"
                        }
                        throw cancelled
                    }
                    failed(worker, running, IllegalStateException("Worker ${worker.name} cancelled itself", cancelled))
                } catch (failure: Throwable) {
                    failed(worker, running, failure)
                }
            }
        }
    }

    private suspend fun failed(worker: Worker, running: Stopwatch, failure: Throwable) {
        logger.error {
            withCoroutine()
            exception(failure)
            "Worker ${worker.name} failed after ${running.lap()}"
        }
        application.requestStop(failure, "worker ${worker.name} failed")
    }

    suspend fun requestStop() {
        for (worker in workers) {
            withLogContext("worker" to worker.name) {
                try {
                    worker.onRequestStop()
                } catch (failure: Exception) {
                    logger.error {
                        withCoroutine()
                        exception(failure)
                        "Worker ${worker.name} failed on the stop request"
                    }
                    application.requestStop(failure)
                }
            }
        }
    }

    suspend fun stop() {
        try {
            val jobs = scope.coroutineContext.job.children.toList()
            if (withTimeoutOrNull(options.graceful) { jobs.joinAll() } == null) {
                logger.info {
                    withCoroutine()
                    "Workers still running after ${options.graceful}; cancelling"
                }
                scope.cancel()
                jobs.joinAll()
            }
        } finally {
            // Stop 단계가 timeout으로 이 Hook을 대기 도중에 취소한 경우에도 여기로 온다.
            scope.cancel()
        }
    }
}
