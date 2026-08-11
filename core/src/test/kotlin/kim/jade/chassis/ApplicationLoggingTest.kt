package kim.jade.chassis

import kim.jade.chassis.hook.HookPhase
import kim.jade.chassis.hook.onStart
import kim.jade.chassis.hook.onStop
import kim.jade.kotlinx.logger.LogLevel
import kim.jade.kotlinx.logger.LogRecord
import kim.jade.kotlinx.logger.Logger
import kim.jade.kotlinx.logger.pipeline.SinkPipe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.koin.dsl.module
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
class ApplicationLoggingTest {
    private suspend fun logs(block: suspend () -> Unit): List<LogRecord> {
        val records = CopyOnWriteArrayList<LogRecord>()
        val level = Logger.defaultLevel
        Logger.defaultLevel = LogLevel.DEBUG
        Logger.defaultPipeline.install(SinkPipe { records += it }, 0)
        try {
            block()
        } finally {
            Logger.defaultPipeline.uninstall(SinkPipe)
            Logger.defaultLevel = level
        }
        return records
    }

    private fun List<LogRecord>.bodies(level: LogLevel) = filter { it.level == level }.map { it.body }

    /** [prefix]로 시작하는 줄이 하나 있고, 나머지가 소요 시간으로 읽힌다. */
    private fun List<String>.duration(prefix: String): Duration =
        Duration.parse(single { it.startsWith(prefix) }.removePrefix(prefix))

    @Test fun `a hook logs when it starts and how long it took`() = runTest {
        val debug = logs {
            val app = application(module {
                onStart("http") {}
                onStop {}
            })
            app.start()
            app.stop()
        }.bodies(LogLevel.DEBUG)

        debug.duration("Hook Start/http finished in ")
        debug.duration("Hook Stop/Stop#0 finished in ")
        assertTrue(
            debug.indexOf("Hook Start/http starting") in 0 until debug.indexOfFirst { it.startsWith("Hook Start/http finished") },
            debug.toString(),
        )
    }

    @Test fun `a phase logs when it starts and how long its hooks took`() = runTest {
        val debug = logs {
            val app = application()
            app.start()
            runCurrent()
            app.stop()
        }.bodies(LogLevel.DEBUG)

        for (phase in HookPhase.entries) {
            assertEquals(1, debug.count { it == "$phase hooks starting" }, debug.toString())
            debug.duration("$phase hooks finished in ")
        }
    }

    @Test fun `a status change logs how long the application was in the status it left`() = runTest {
        val info = logs {
            val app = application()
            app.start()
            app.stop()
        }.bodies(LogLevel.INFO)

        info.duration("Application application: NEW -> STARTING after ")
        info.duration("Application application: STARTING -> ACTIVE after ")
        info.duration("Application application: ACTIVE -> STOPPING after ")
        info.duration("Application application: STOPPING -> STOPPED after ")
    }

    @Test fun `a failing hook logs its failure and nothing about finishing`() = runTest {
        val records = logs {
            val app = application(module { onStart("broken") { error("boom") } })
            runCatching { app.start() }
            runCatching { app.awaitTermination() }
        }
        val debug = records.bodies(LogLevel.DEBUG)

        assertTrue("Hook Start/broken starting" in debug, debug.toString())
        assertTrue(debug.none { it.startsWith("Hook Start/broken finished") }, debug.toString())
        assertTrue(debug.none { it.startsWith("Start hooks finished") }, debug.toString())
        assertEquals(listOf("Hook failed: Start/broken"), records.bodies(LogLevel.ERROR).filter { it.startsWith("Hook failed") })
    }

    @Test fun `creating the instances logs how long it took`() = runTest {
        val debug = logs {
            val app = application()
            app.start()
            app.stop()
        }.bodies(LogLevel.DEBUG)

        assertTrue("Instance creation starting" in debug, debug.toString())
        debug.duration("Instance creation finished in ")
    }

    @Test fun `a stop request logs why`() = runTest {
        suspend fun stopping(request: (Application) -> Unit): List<String> = logs {
            val app = application()
            app.start()
            request(app)
            runCatching { app.awaitTermination() }
        }.bodies(LogLevel.INFO).filter { "stop requested" in it }

        assertEquals(listOf("Application application: stop requested: maintenance"), stopping { it.requestStop(reason = "maintenance") })
        assertEquals(
            listOf("Application application: stop requested: IllegalStateException: broken"),
            stopping { it.requestStop(IllegalStateException("broken")) },
        )
        assertEquals(
            listOf("Application application: stop requested: disk full"),
            stopping { it.requestStop(IllegalStateException("broken"), reason = "disk full") },
        )
        assertEquals(listOf("Application application: stop requested"), stopping { it.requestStop() })
    }

    @Test fun `only the first stop request is logged`() = runTest {
        val info = logs {
            val app = application()
            app.start()
            app.requestStop(reason = "first")
            app.requestStop(reason = "second")
            app.awaitTermination()
            app.requestStop(reason = "late")
        }.bodies(LogLevel.INFO)

        assertEquals(listOf("Application application: stop requested: first"), info.filter { "stop requested" in it })
    }

    @Test fun `a failing startup says so in its stop request`() = runTest {
        val info = logs {
            val app = application(module { onStart { error("boom") } })
            runCatching { app.start() }
            runCatching { app.awaitTermination() }
        }.bodies(LogLevel.INFO)

        assertEquals(listOf("Application application: stop requested: startup failed"), info.filter { "stop requested" in it })
    }

    @Test fun `the application logs under its name and its hooks under the framework's logger`() = runTest {
        val records = logs {
            val app = Application(
                listOf(module { onStart("http") {} }),
                name = "catalog",
                context = StandardTestDispatcher(testScheduler),
            )
            app.start()
            app.stop()
        }

        assertEquals("catalog", records.single { it.body.startsWith("Application catalog: NEW -> STARTING") }.loggerName)
        assertEquals("kim.jade.chassis.hook", records.single { it.body == "Hook Start/http starting" }.loggerName)
    }

    @Test fun `runApplication says the caller was cancelled`() = runTest {
        val info = logs {
            val running = launch { runApplication(application()) }
            runCurrent()
            running.cancelAndJoin()
        }.bodies(LogLevel.INFO)

        assertTrue("Application application: stop requested: caller cancelled" in info, info.toString())
    }
}
