package kim.jade.chassis

import kim.jade.chassis.hook.HookPhase
import kim.jade.chassis.hook.hook
import kim.jade.chassis.hook.onInitialize
import kim.jade.chassis.hook.onRelease
import kim.jade.chassis.hook.onRequestStop
import kim.jade.chassis.hook.onStart
import kim.jade.chassis.hook.onStarted
import kim.jade.chassis.hook.onStop
import kim.jade.kotlinx.logger.LogLevel
import kim.jade.kotlinx.logger.LogRecord
import kim.jade.kotlinx.logger.Logger
import kim.jade.kotlinx.logger.pipeline.SinkPipe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.koin.core.error.NoDefinitionFoundException
import org.koin.dsl.module
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ApplicationFailureTest {
    private class Broken
    private class Missing

    private val calls = mutableListOf<String>()

    private suspend fun errorLogs(block: suspend () -> Unit): List<LogRecord> {
        val records = CopyOnWriteArrayList<LogRecord>()
        Logger.defaultPipeline.install(SinkPipe { records += it }, 0)
        try {
            block()
        } finally {
            Logger.defaultPipeline.uninstall(SinkPipe)
        }
        return records.filter { it.level == LogLevel.ERROR }
    }

    @Test fun `a failing startup hook runs every shutdown phase and fails the application`() = runTest {
        val boom = IllegalStateException("boom")
        val app = application(module {
            hook { recordAll(calls, "a") }
            onStart { throw boom }
            onStarted { calls += "never" }
        })
        app.requestStart()
        assertFailurePreserved(boom, assertFails { app.awaitActive() })
        assertFailurePreserved(boom, assertFails { app.awaitTermination() })
        assertFailurePreserved(boom, assertFails { app.awaitTermination() })
        assertEquals(ApplicationStatus.FAILED, app.status)
        assertEquals(listOf("Initialize/a", "Start/a", "RequestStop/a", "Stop/a", "Release/a"), calls)
    }

    @Test fun `a failing Started hook fails an application that is already active`() = runTest {
        val boom = IllegalStateException("boom")
        val app = application(module {
            hook { recordAll(calls, "a") }
            onStarted { throw boom }
            onStarted { calls += "never" }
        })
        app.requestStart()
        app.awaitActive()
        assertFailurePreserved(boom, assertFails { app.awaitTermination() })
        assertEquals(ApplicationStatus.FAILED, app.status)
        assertEquals(
            listOf("Initialize/a", "Start/a", "Started/a", "RequestStop/a", "Stop/a", "Release/a"),
            calls,
        )
    }

    @Test fun `a failing definition surfaces its own exception`() = runTest {
        val boom = IllegalArgumentException("definition")
        val app = application(module { single<Broken>(createdAtStart = true) { throw boom } })
        app.requestStart()
        assertFailurePreserved(boom, assertFails { app.awaitTermination() })
        assertEquals(ApplicationStatus.FAILED, app.status)
    }

    @Test fun `a failing hook block surfaces its own exception`() = runTest {
        val app = application(module { hook { get<Missing>() } })
        app.requestStart()
        assertFailsWith<NoDefinitionFoundException> { app.awaitTermination() }
        assertEquals(ApplicationStatus.FAILED, app.status)
    }

    @Test fun `failing shutdown hooks do not stop the others`() = runTest {
        val first = IllegalStateException("first")
        val app = application(module {
            onStop { calls += "last" }
            onStop { throw IllegalStateException("second") }
            onRequestStop { throw first }
            onRelease { calls += "release" }
        })
        app.start()
        val thrown = assertFails { app.stop() }
        assertFailurePreserved(first, thrown)
        assertEquals(setOf<String?>("second"), suppressedMessages(thrown).toSet())
        assertEquals(listOf("last", "release"), calls)
        assertEquals(ApplicationStatus.FAILED, app.status)
    }

    @Test fun `shutdown that exceeds the timeout skips the remaining hooks and phases`() = runTest {
        val app = application(
            module {
                onStop { calls += "skipped" }
                onStop { delay(60.seconds) }
                onRelease { calls += "release" }
            },
            shutdownTimeout = 5.seconds,
        )
        app.start()
        val thrown = assertFailsWith<ShutdownTimeoutException> { app.stop() }
        assertEquals(HookPhase.Stop, thrown.phase)
        assertEquals(emptyList(), calls)
        assertEquals(ApplicationStatus.FAILED, app.status)
        assertEquals(5_000, currentTime)
    }

    @Test fun `the shutdown phases share one timeout`() = runTest {
        val app = application(
            module {
                onRequestStop { delay(3.seconds) }
                onStop { delay(3.seconds) }
                onRelease { calls += "release" }
            },
            shutdownTimeout = 5.seconds,
        )
        app.start()
        val thrown = assertFailsWith<ShutdownTimeoutException> { app.stop() }
        assertEquals(HookPhase.Stop, thrown.phase)
        assertEquals(emptyList(), calls)
        assertEquals(ApplicationStatus.FAILED, app.status)
        assertEquals(5_000, currentTime)
    }

    @Test fun `phases that together fit within the timeout all run`() = runTest {
        val app = application(
            module {
                onRequestStop { delay(2.seconds) }
                onStop { delay(2.seconds) }
                onRelease { calls += "release" }
            },
            shutdownTimeout = 5.seconds,
        )
        app.start()
        app.stop()
        assertEquals(ApplicationStatus.STOPPED, app.status)
        assertEquals(listOf("release"), calls)
        assertEquals(4_000, currentTime)
    }

    @Test fun `a shutdown hook that ignores cancellation is waited for past the timeout`() = runTest {
        val app = application(
            module {
                onStop { withContext(NonCancellable) { delay(100.seconds) } }
                onRelease { calls += "release" }
            },
            shutdownTimeout = 5.seconds,
        )
        app.start()
        val thrown = assertFailsWith<ShutdownTimeoutException> { app.stop() }
        assertEquals(HookPhase.Stop, thrown.phase)
        assertEquals(emptyList(), calls)
        assertEquals(100_000, currentTime)
    }

    @Test fun `requestStop during startup cancels it and still shuts down`() = runTest {
        val app = application(module {
            onInitialize { awaitCancellation() }
            onStart { calls += "never" }
            onRelease { calls += "release" }
        })
        app.requestStart()
        runCurrent()
        app.stop()
        assertEquals(ApplicationStatus.STOPPED, app.status)
        assertEquals(listOf("release"), calls)
        assertFailsWith<IllegalStateException> { app.awaitActive() }
    }

    @Test fun `requestStop from inside a startup hook neither deadlocks nor runs later startup hooks`() = runTest {
        val app = application(module {
            hook { val self = get<Application>(); onInitialize { self.requestStop() } }
            onInitialize { calls += "never" }
            onStart { calls += "never" }
            onRelease { calls += "release" }
        })
        app.requestStart()
        app.awaitTermination()
        assertEquals(ApplicationStatus.STOPPED, app.status)
        assertEquals(listOf("release"), calls)
    }

    @Test fun `requestStop with a cause fails the application`() = runTest {
        val boom = IllegalStateException("boom")
        val app = application(module { onRelease { calls += "release" } })
        app.start()
        app.requestStop(boom)
        assertFailurePreserved(boom, assertFails { app.awaitTermination() })
        assertEquals(ApplicationStatus.FAILED, app.status)
        assertEquals(listOf("release"), calls)
    }

    @Test fun `a cause reported while stopping fails the application`() = runTest {
        val boom = IllegalStateException("boom")
        val app = application(module {
            hook { val self = get<Application>(); onStop { self.requestStop(boom) } }
        })
        app.start()
        assertFailurePreserved(boom, assertFails { app.stop() })
        assertEquals(ApplicationStatus.FAILED, app.status)
    }

    @Test fun `stopping a new application with a cause fails it`() = runTest {
        val boom = IllegalStateException("boom")
        val app = application(module { })
        app.requestStop(boom)
        assertFailurePreserved(boom, assertFails { app.awaitTermination() })
        assertFailurePreserved(boom, assertFails { app.awaitActive() })
        assertEquals(ApplicationStatus.FAILED, app.status)
    }

    @Test fun `a startup hook that cancels itself fails the application`() = runTest {
        val app = application(module { onStart { throw CancellationException("self") } })
        app.requestStart()
        val thrown = assertFailsWith<IllegalStateException> { app.awaitTermination() }
        assertIs<CancellationException>(generateSequence(thrown as Throwable) { it.cause }.last())
        assertEquals(ApplicationStatus.FAILED, app.status)
    }

    @Test fun `a hook block that cancels itself fails the application`() = runTest {
        val app = application(module { hook { throw CancellationException("self") } })
        app.requestStart()
        val thrown = assertFailsWith<IllegalStateException> { app.awaitTermination() }
        assertIs<CancellationException>(generateSequence(thrown as Throwable) { it.cause }.last())
        assertEquals(ApplicationStatus.FAILED, app.status)
        assertFailsWith<IllegalStateException> { app.awaitActive() }
    }

    @Test fun `a definition that throws a cancellation fails the application`() = runTest {
        val app = application(module { single<Broken>(createdAtStart = true) { throw CancellationException("definition") } })
        app.requestStart()
        val thrown = assertFailsWith<IllegalStateException> { app.awaitTermination() }
        assertFalse(thrown is CancellationException, "a failure must not look like caller cancellation")
        assertIs<CancellationException>(generateSequence(thrown as Throwable) { it.cause }.last())
        assertEquals(ApplicationStatus.FAILED, app.status)
    }

    @Test fun `requestStop with a cancellation as its cause fails the application`() = runTest {
        val app = application(module { })
        app.start()
        app.requestStop(CancellationException("user"))
        val thrown = assertFailsWith<IllegalStateException> { app.awaitTermination() }
        assertFalse(thrown is CancellationException, "a failure must not look like caller cancellation")
        assertIs<CancellationException>(generateSequence(thrown as Throwable) { it.cause }.last())
        assertEquals(ApplicationStatus.FAILED, app.status)
    }

    @Test fun `a failing definition is logged once with its cause`() = runTest {
        val boom = IllegalArgumentException("definition")
        val app = application(module { single<Broken>(createdAtStart = true) { throw boom } })
        val errors = errorLogs {
            app.requestStart()
            assertFails { app.awaitTermination() }
        }
        assertEquals(1, errors.count { it.exception === boom })
    }

    @Test fun `a failing hook block is logged once with its cause`() = runTest {
        val app = application(module { hook { get<Missing>() } })
        val errors = errorLogs {
            app.requestStart()
            assertFails { app.awaitTermination() }
        }
        assertEquals(1, errors.count { it.exception is NoDefinitionFoundException })
    }

    @Test fun `a failing hook is still logged only once`() = runTest {
        val app = application(module { onStart { throw IllegalStateException("hook") } })
        val errors = errorLogs {
            app.requestStart()
            assertFails { app.awaitTermination() }
        }
        assertEquals(1, errors.count { it.exception is IllegalStateException && it.exception?.message == "hook" })
    }
}
