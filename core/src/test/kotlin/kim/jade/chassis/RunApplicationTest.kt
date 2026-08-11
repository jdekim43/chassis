package kim.jade.chassis

import kim.jade.chassis.hook.hook
import kim.jade.chassis.hook.onRelease
import kim.jade.chassis.hook.onStart
import kim.jade.chassis.hook.onStarted
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RunApplicationTest {
    private val calls = mutableListOf<String>()

    @Test fun `returns when the application stops`() = runTest {
        val app = application(module {
            hook { val self = get<Application>(); onStarted { self.requestStop() } }
            onRelease { calls += "release" }
        })
        runApplication(app)
        assertEquals(ApplicationStatus.STOPPED, app.status)
        assertEquals(listOf("release"), calls)
    }

    @Test fun `throws the cause when the application fails`() = runTest {
        val boom = IllegalStateException("boom")
        val app = application(module { onStart { throw boom } })
        assertFailurePreserved(boom, assertFails { runApplication(app) })
        assertEquals(ApplicationStatus.FAILED, app.status)
    }

    @Test fun `throws a failure, not a cancellation, when a definition throws a cancellation`() = runTest {
        val app = application(module { single<String>(createdAtStart = true) { throw CancellationException("definition") } })
        val thrown = assertFailsWith<IllegalStateException> { runApplication(app) }
        assertFalse(thrown is CancellationException, "a failure must not look like caller cancellation")
        assertEquals(ApplicationStatus.FAILED, app.status)
    }

    @Test fun `cancelling the caller stops the application before propagating`() = runTest {
        val app = application(module { onRelease { calls += "release" } })
        val job = launch { runApplication(app) }
        advanceUntilIdle()
        assertEquals(ApplicationStatus.ACTIVE, app.status)
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(ApplicationStatus.STOPPED, app.status)
        assertEquals(listOf("release"), calls)
    }
}
