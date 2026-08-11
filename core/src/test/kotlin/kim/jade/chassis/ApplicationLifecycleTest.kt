package kim.jade.chassis

import kim.jade.chassis.hook.hook
import kim.jade.chassis.hook.onStart
import kim.jade.chassis.hook.onStarted
import kim.jade.chassis.hook.onStop
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ApplicationLifecycleTest {
    private class Eager

    private val calls = mutableListOf<String>()

    @Test fun `start runs the startup phases and stop runs the shutdown phases`() = runTest {
        val app = application(module { hook { recordAll(calls, "m") } })
        assertEquals(ApplicationStatus.NEW, app.status)
        app.start()
        assertEquals(ApplicationStatus.ACTIVE, app.status)
        advanceUntilIdle()
        assertEquals(listOf("Initialize/m", "Start/m", "Started/m"), calls)
        app.stop()
        assertEquals(ApplicationStatus.STOPPED, app.status)
        assertEquals(listOf("RequestStop/m", "Stop/m", "Release/m"), calls.drop(3))
    }

    @Test fun `an application without hooks starts and stops`() = runTest {
        val app = application(module { })
        app.start()
        assertEquals(ApplicationStatus.ACTIVE, app.status)
        app.stop()
        assertEquals(ApplicationStatus.STOPPED, app.status)
    }

    @Test fun `requestStart returns at once and awaitActive waits for Start hooks`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val app = application(module { onStart { gate.await() } })
        app.requestStart()
        assertEquals(ApplicationStatus.STARTING, app.status)
        val waiter = async { app.awaitActive() }
        advanceUntilIdle()
        assertFalse(waiter.isCompleted)
        assertEquals(ApplicationStatus.STARTING, app.status)
        gate.complete(Unit)
        waiter.await()
        assertEquals(ApplicationStatus.ACTIVE, app.status)
        app.awaitActive()
        app.stop()
    }

    @Test fun `the application is active before Started hooks run`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var statusInHook: ApplicationStatus? = null
        val app = application(module {
            hook {
                val self = get<Application>()
                onStarted { statusInHook = self.status; calls += "begin"; gate.await(); calls += "end" }
            }
        })
        app.requestStart()
        val waiter = async { app.awaitActive() }
        advanceUntilIdle()
        assertTrue(waiter.isCompleted)
        assertEquals(ApplicationStatus.ACTIVE, statusInHook)
        assertEquals(listOf("begin"), calls)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("begin", "end"), calls)
        app.stop()
    }

    @Test fun `a stop requested during Started hooks cancels the remaining ones`() = runTest {
        val app = application(module {
            onStarted { calls += "first"; awaitCancellation() }
            onStarted { calls += "never" }
            onStop { calls += "Stop" }
        })
        app.requestStart()
        advanceUntilIdle()
        assertEquals(ApplicationStatus.ACTIVE, app.status)
        assertEquals(listOf("first"), calls)
        app.stop()
        assertEquals(ApplicationStatus.STOPPED, app.status)
        assertEquals(listOf("first", "Stop"), calls)
    }

    @Test fun `awaitTermination blocks without stop until the application ends`() = runTest {
        val app = application(module { })
        app.start()
        val waiter = async { app.awaitTermination() }
        advanceUntilIdle()
        assertFalse(waiter.isCompleted)
        app.requestStop()
        waiter.await()
        assertEquals(ApplicationStatus.STOPPED, app.status)
        app.awaitTermination()
        app.awaitTermination()
    }

    @Test fun `requestStart twice throws`() = runTest {
        val app = application(module { })
        app.requestStart()
        assertFailsWith<IllegalStateException> { app.requestStart() }
        app.stop()
    }

    @Test fun `stopping a new application runs no hooks`() = runTest {
        val app = application(module { hook { recordAll(calls, "m") } })
        app.stop()
        assertEquals(ApplicationStatus.STOPPED, app.status)
        assertEquals(emptyList(), calls)
        assertFailsWith<IllegalStateException> { app.awaitActive() }
        assertFailsWith<IllegalStateException> { app.requestStart() }
    }

    @Test fun `requestStop twice runs the shutdown once`() = runTest {
        val app = application(module { onStop { calls += "stop" } })
        app.start()
        app.requestStop()
        app.requestStop()
        app.awaitTermination()
        app.requestStop()
        assertEquals(listOf("stop"), calls)
    }

    @Test fun `the application resolves itself from Koin and creates nothing before start`() = runTest {
        var created = 0
        val app = application(module { single(createdAtStart = true) { created++; Eager() } })
        assertSame(app, app.koin.get<Application>())
        assertEquals(0, created)
        app.start()
        assertEquals(1, created)
        app.stop()
    }

    @Test fun `a job in the supplied context is neither used nor cancelled`() = runTest {
        val callerJob = Job()
        val app = Application(
            listOf(module { }),
            context = StandardTestDispatcher(testScheduler) + callerJob,
        )
        app.start()
        app.stop()
        assertEquals(ApplicationStatus.STOPPED, app.status)
        assertTrue(callerJob.isActive)
        assertTrue(callerJob.children.none())
        callerJob.cancel()
    }
}
