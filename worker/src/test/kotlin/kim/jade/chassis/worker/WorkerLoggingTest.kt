package kim.jade.chassis.worker

import kim.jade.chassis.Application
import kim.jade.kotlinx.logger.LogLevel
import kim.jade.kotlinx.logger.LogRecord
import kim.jade.kotlinx.logger.Logger
import kim.jade.kotlinx.logger.context.CoroutineLogContext
import kim.jade.kotlinx.logger.context.CoroutineThreadLogContext
import kim.jade.kotlinx.logger.context.LogContext
import kim.jade.kotlinx.logger.context.MutableLogContext
import kim.jade.kotlinx.logger.context.ThreadLogContext
import kim.jade.kotlinx.logger.context.currentCoroutineLogContext
import kim.jade.kotlinx.logger.pipeline.SinkPipe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
class WorkerLoggingTest {
    private class Named(
        override val name: String,
        val onStop: () -> Unit = {},
        val body: suspend () -> Unit,
    ) : Worker {
        override suspend fun run() = body()
        override fun onRequestStop() = onStop()
    }

    private fun TestScope.application(workers: Module): Application {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return Application(listOf(workers), context = dispatcher)
    }

    private suspend fun logs(block: suspend () -> Unit): List<LogRecord> {
        val records = CopyOnWriteArrayList<LogRecord>()
        Logger.defaultPipeline.install(SinkPipe { records += it }, 0)
        try {
            block()
        } finally {
            Logger.defaultPipeline.uninstall(SinkPipe)
        }
        return records
    }

    private fun List<LogRecord>.duration(level: LogLevel, prefix: String): Duration =
        Duration.parse(single { it.level == level && it.body.startsWith(prefix) }.body.removePrefix(prefix))

    @Test fun `overlapping workers receive app and worker fields without sharing local log data`() = runTest {
        val logger = Logger.named("test.worker.context")
        val firstEntered = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val records = logs {
            val workers = module {
                worker(named("first")) {
                    Named("first", onStop = { logger.info("sync first stop") }) {
                        currentCoroutineLogContext()["local"] = "first"
                        ThreadLogContext["threadLocal"] = "first"
                        firstEntered.complete(Unit)
                        secondEntered.await()
                        logger.info("sync first worker")
                        logger.info {
                            withCoroutine()
                            "first body"
                        }
                    }
                }
                worker(named("second")) {
                    Named("second") {
                        firstEntered.await()
                        logger.info("sync second worker")
                        logger.info {
                            withCoroutine()
                            "second body"
                        }
                        secondEntered.complete(Unit)
                    }
                }
            }
            val dispatcher = StandardTestDispatcher(testScheduler)
            val options = module {
                single {
                    WorkerOptions(
                        context = dispatcher + CoroutineLogContext(mapOf("queue" to "jobs")) +
                            CoroutineThreadLogContext(MutableLogContext("workerThread" to "pool")),
                    )
                }
            }
            val app = Application(
                listOf(workers, options),
                name = "catalog",
                context = dispatcher,
                logContext = LogContext("env" to "test"),
            )
            app.start()
            runCurrent()
            app.stop()
        }

        val first = records.single { it.body == "first body" }.context
        val second = records.single { it.body == "second body" }.context
        assertEquals("first", first["local"])
        assertNull(second["local"])
        for ((context, name) in listOf(first to "first", second to "second")) {
            assertEquals("catalog", context["application"])
            assertEquals("test", context["env"])
            assertEquals("jobs", context["queue"])
            assertEquals(name, context["worker"])
        }
        val firstFinished = records.single { it.body.startsWith("Worker first finished after ") }
        assertEquals("first", firstFinished.context["local"])
        val firstThread = records.single { it.body == "sync first worker" }.context
        assertEquals("first", firstThread["worker"])
        assertEquals("pool", firstThread["workerThread"])
        assertEquals("first", firstThread["threadLocal"])
        assertEquals("first", firstThread["local"])
        val secondThread = records.single { it.body == "sync second worker" }.context
        assertEquals("second", secondThread["worker"])
        assertEquals("pool", secondThread["workerThread"])
        assertNull(secondThread["threadLocal"])
        assertNull(secondThread["local"])
        val stopThread = records.single { it.body == "sync first stop" }.context
        assertEquals("first", stopThread["worker"])
        assertEquals("catalog", stopThread["application"])
        assertNull(stopThread["threadLocal"])
    }

    @Test fun `a worker logs how long it ran when it finishes or is cancelled`() = runTest {
        val records = logs {
            val app = application(module {
                worker(named("done")) { Named("done") {} }
                worker(named("loop")) { Named("loop") { awaitCancellation() } }
            })
            app.start()
            runCurrent()
            app.stop()
        }

        records.duration(LogLevel.INFO, "Worker done finished after ")
        records.duration(LogLevel.INFO, "Worker loop cancelled after ")
    }

    @Test fun `a failing worker logs how long it ran and tells the application why it stops`() = runTest {
        val records = logs {
            val app = application(module { worker { Named("broken") { error("boom") } } })
            app.start()
            runCatching { app.awaitTermination() }
        }

        records.duration(LogLevel.ERROR, "Worker broken failed after ")
        assertTrue(
            records.any { it.body == "Application application: stop requested: worker broken failed" },
            records.map { it.body }.toString(),
        )
    }

    @Test fun `a worker failing on the stop request is logged with its name`() = runTest {
        val records = logs {
            val app = application(module { worker { Named("broken", onStop = { error("boom") }) { awaitCancellation() } } })
            app.start()
            runCurrent()
            runCatching { app.stop() }
        }

        val record = records.single { it.level == LogLevel.ERROR && it.body == "Worker broken failed on the stop request" }
        assertEquals("boom", record.exception?.message)
        assertEquals("broken", record.context["worker"])
    }
}
