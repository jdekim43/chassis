package kim.jade.chassis.worker

import kim.jade.chassis.Application
import kim.jade.kotlinx.logger.LogLevel
import kim.jade.kotlinx.logger.LogRecord
import kim.jade.kotlinx.logger.Logger
import kim.jade.kotlinx.logger.pipeline.SinkPipe
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
        val options = module { single { WorkerOptions(context = dispatcher) } }
        return Application(listOf(workers, options), context = dispatcher)
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
    }
}
