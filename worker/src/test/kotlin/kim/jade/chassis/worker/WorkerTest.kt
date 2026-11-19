package kim.jade.chassis.worker

import kim.jade.chassis.Application
import kim.jade.chassis.ApplicationStatus
import kim.jade.chassis.ShutdownTimeoutException
import kim.jade.chassis.hook.HookPhase
import kim.jade.chassis.hook.onRelease
import kim.jade.chassis.hook.onRequestStop
import kim.jade.chassis.hook.onStart
import kim.jade.chassis.hook.onStop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.koin.core.KoinApplication
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class WorkerTest {
    private class Dependency
    private class Recording(
        val calls: MutableList<String>,
        val onStop: () -> Unit = {},
        val body: suspend () -> Unit,
    ) : Worker {
        override suspend fun run() { calls += "run"; body() }
        override fun onRequestStop() = onStop()
    }
    private class Stoppable(
        override val name: String,
        val calls: MutableList<String>,
        val onStop: () -> Unit = {},
    ) : Worker {
        private val stopped = CompletableDeferred<Unit>()
        override suspend fun run() { calls += "run:$name"; stopped.await(); calls += "end:$name" }
        override fun onRequestStop() { calls += "stop:$name"; onStop(); stopped.complete(Unit) }
    }

    private val calls = mutableListOf<String>()

    private fun TestScope.application(
        vararg modules: Module,
        graceful: Duration = 10.seconds,
        shutdownTimeout: Duration = 30.seconds,
    ): Application {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val options = module { single { WorkerOptions(graceful = graceful) } }
        return Application(modules.toList() + options, shutdownTimeout = shutdownTimeout, context = dispatcher)
    }

    @Test fun `worker modules compose without overriding bindings`() {
        val recording = module { worker { Recording(calls) {} } }
        val stoppable = module { worker { Stoppable("stoppable", calls) } }

        val app = KoinApplication.init()
        try {
            app.allowOverride(false)
            app.modules(recording, stoppable)
            val recordingWorker = app.koin.get<Recording>()
            val stoppableWorker = app.koin.get<Stoppable>()
            val workers = app.koin.getAll<Worker>()
            assertEquals(2, workers.size)
            assertEquals(setOf(recordingWorker, stoppableWorker), workers.toSet())
        } finally {
            app.close()
        }
    }

    @Test fun `workers start in Started after every Start hook`() = runTest {
        val app = application(module {
            worker { Recording(calls) {} }
            onStart { calls += "start" }
        })
        app.start()
        advanceUntilIdle()
        assertEquals(listOf("start", "run"), calls)
        app.stop()
    }

    @Test fun `workers inherit the application coroutine context by default`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val marker = ThreadLocal<String>()
        val observed = CompletableDeferred<Pair<ContinuationInterceptor?, String?>>()
        val app = Application(
            listOf(module {
                worker {
                    Recording(calls) {
                        observed.complete(currentCoroutineContext()[ContinuationInterceptor] to marker.get())
                    }
                }
            }),
            context = dispatcher + marker.asContextElement("application"),
        )
        try {
            app.start()
            assertEquals(dispatcher to "application", observed.await())
        } finally {
            app.stop()
        }
    }

    @Test fun `a worker that ends on RequestStop is not cancelled`() = runTest {
        val inbox = Channel<String>()
        val app = application(module {
            worker {
                Recording(calls, onStop = { inbox.close() }) {
                    try { for (message in inbox) calls += message; calls += "drained" }
                    catch (cancelled: CancellationException) { calls += "cancelled"; throw cancelled }
                }
            }
        })
        app.start()
        inbox.send("a")
        app.stop()
        assertEquals(listOf("run", "a", "drained"), calls)
        assertEquals(0, currentTime)
        assertEquals(ApplicationStatus.STOPPED, app.status)
    }

    @Test fun `a stop request reaches every worker once and they end without waiting for graceful`() = runTest {
        val app = application(module {
            worker(named("a")) { Stoppable("a", calls) }
            worker(named("b")) { Stoppable("b", calls) }
        })
        app.start()
        advanceUntilIdle()
        app.stop()
        assertEquals(listOf("end:a", "end:b", "run:a", "run:b", "stop:a", "stop:b"), calls.sorted())
        assertEquals(0, currentTime)
        assertEquals(ApplicationStatus.STOPPED, app.status)
    }

    @Test fun `a worker that never ran still gets the stop request`() = runTest {
        val app = application(module {
            worker { Stoppable("a", calls) }
            onStart { awaitCancellation() }
        })
        app.requestStart()
        runCurrent()
        app.requestStop()
        runCatching { app.awaitTermination() }
        assertEquals(listOf("stop:a"), calls)
    }

    @Test fun `a worker that already returned still gets the stop request`() = runTest {
        val app = application(module { worker { Recording(calls, onStop = { calls += "stop" }) {} } })
        app.start()
        advanceUntilIdle()
        assertEquals(listOf("run"), calls)
        app.stop()
        assertEquals(listOf("run", "stop"), calls)
        assertEquals(ApplicationStatus.STOPPED, app.status)
    }

    @Test fun `a worker failing on the stop request fails the application and the others are still told`() = runTest {
        val app = application(
            module {
                worker(named("a")) { Stoppable("a", calls) { throw IllegalStateException("boom a") } }
                worker(named("b")) { Stoppable("b", calls) { throw IllegalStateException("boom b") } }
            },
            graceful = 1.seconds,
        )
        app.start()
        advanceUntilIdle()
        val thrown = assertFailsWith<IllegalStateException> { app.stop() }
        // Worker를 부르는 순서는 정해져 있지 않다. 어느 쪽이 먼저든 둘 다 불려야 한다.
        assertTrue("stop:a" in calls && "stop:b" in calls, calls.toString())
        val reported = generateSequence(thrown as Throwable) { it.cause }.flatMap { sequenceOf(it) + it.suppressed }
        assertEquals(setOf<String?>("boom a", "boom b"), reported.map { it.message }.toSet())
        assertEquals(ApplicationStatus.FAILED, app.status)
    }

    @Test fun `a worker still running after graceful is cancelled`() = runTest {
        val app = application(
            module { worker { Recording(calls) { try { awaitCancellation() } finally { calls += "cancelled" } } } },
            graceful = 1.seconds,
        )
        app.start()
        advanceUntilIdle()
        app.stop()
        assertEquals(listOf("run", "cancelled"), calls)
        assertEquals(1_000, currentTime)
        assertEquals(ApplicationStatus.STOPPED, app.status)
    }

    @Test fun `a failing worker stops the application with its failure`() = runTest {
        val app = application(module { worker { Recording(calls) { throw IllegalStateException("boom") } } })
        app.requestStart()
        val thrown = assertFailsWith<IllegalStateException> { app.awaitTermination() }
        assertEquals("boom", thrown.message)
        assertEquals(ApplicationStatus.FAILED, app.status)
    }

    @Test fun `a worker that cancels itself fails the application`() = runTest {
        val app = application(module { worker { Recording(calls) { withTimeout(1.seconds) { awaitCancellation() } } } })
        app.requestStart()
        advanceUntilIdle()
        assertEquals(ApplicationStatus.FAILED, app.status)
        val thrown = assertFailsWith<IllegalStateException> { app.awaitTermination() }
        assertFalse(thrown is CancellationException, "a failure must not look like caller cancellation")
        assertIs<CancellationException>(generateSequence(thrown as Throwable) { it.cause }.last())
    }

    @Test fun `a worker failing during shutdown fails the application`() = runTest {
        val stop = CompletableDeferred<Unit>()
        val app = application(module {
            worker {
                Recording(calls, onStop = { stop.complete(Unit) }) { stop.await(); throw IllegalStateException("late") }
            }
        })
        app.start()
        advanceUntilIdle()
        val thrown = assertFailsWith<IllegalStateException> { app.stop() }
        assertEquals("late", thrown.message)
        assertEquals(ApplicationStatus.FAILED, app.status)
    }

    @Test fun `a worker that returns leaves the application active`() = runTest {
        val app = application(module { worker { Recording(calls) {} } })
        app.start()
        advanceUntilIdle()
        assertEquals(listOf("run"), calls)
        assertEquals(ApplicationStatus.ACTIVE, app.status)
        app.stop()
    }

    @Test fun `a worker ends before its dependency stops`() = runTest {
        val app = application(
            module {
                single {
                    Dependency().also {
                        onStop { calls += "dependency stop" }
                        onRelease { calls += "dependency release" }
                    }
                }
                worker {
                    get<Dependency>()
                    Recording(calls) { try { awaitCancellation() } finally { calls += "worker end" } }
                }
            },
            graceful = Duration.ZERO,
        )
        app.start()
        advanceUntilIdle()
        app.stop()
        assertEquals(listOf("run", "worker end", "dependency stop", "dependency release"), calls)
    }

    @Test fun `default and explicitly qualified workers of one type both run`() = runTest {
        val first = mutableListOf<String>()
        val second = mutableListOf<String>()
        val app = application(module {
            worker { Recording(first) {} }
            worker(named<Recording>()) { Recording(second) {} }
        })
        app.start()
        advanceUntilIdle()
        assertSame(first, app.koin.get<Recording>().calls)
        assertSame(second, app.koin.get<Recording>(named<Recording>()).calls)
        assertSame(app.koin.get<Recording>(named<Recording>()), app.koin.get<Worker>(named<Recording>()))
        assertEquals(listOf("run"), first)
        assertEquals(listOf("run"), second)
        app.stop()
    }

    @Test fun `workers are cancelled when the Stop phase is cut short`() = runTest {
        var ticks = 0
        val app = application(
            module {
                // 테스트 범위를 훨씬 넘는 횟수만큼 tick한다. 무한 루프로 두면 회귀가 생겼을 때 테스트가 실패하지 않고,
                // 본문이 끝난 뒤에도 테스트 스케줄러가 계속 돈다.
                worker { Recording(calls) { repeat(10_000) { delay(1.seconds); ticks++ } } }
                // Worker보다 나중에 등록되므로 Stop에서 먼저 실행되어 단계 시간의 대부분을 쓴다.
                onStop { delay(25.seconds) }
            },
            graceful = 10.seconds,
            shutdownTimeout = 30.seconds,
        )
        app.start()
        runCurrent()
        val thrown = assertFailsWith<ShutdownTimeoutException> { app.stop() }
        assertEquals(HookPhase.Stop, thrown.phase)
        assertEquals(ApplicationStatus.FAILED, app.status)
        val ticksAtTermination = ticks
        advanceTimeBy(20.seconds)
        runCurrent()
        assertEquals(ticksAtTermination, ticks)
    }

    @Test fun `application shutdown cancels workers when the Stop phase is skipped`() = runTest {
        val app = application(
            module {
                worker {
                    Recording(calls) {
                        try { delay(20.seconds) } finally { calls += "worker end" }
                    }
                }
                onRequestStop { delay(10.seconds) }
            },
            shutdownTimeout = 1.seconds,
        )
        app.start()
        runCurrent()
        val thrown = assertFailsWith<ShutdownTimeoutException> { app.stop() }
        assertEquals(HookPhase.RequestStop, thrown.phase)
        runCurrent()
        assertEquals(listOf("run", "worker end"), calls)
    }

    @Test fun `worker context overrides the dispatcher and ignores its supplied job`() = runTest {
        val callerJob = Job()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val workerDispatcher = StandardTestDispatcher(testScheduler)
        val marker = ThreadLocal<String>()
        val observed = CompletableDeferred<Pair<ContinuationInterceptor?, String?>>()
        val app = Application(
            listOf(module {
                worker {
                    Recording(calls) {
                        observed.complete(currentCoroutineContext()[ContinuationInterceptor] to marker.get())
                        awaitCancellation()
                    }
                }
                single { WorkerOptions(graceful = 1.seconds, context = workerDispatcher + callerJob) }
            }),
            context = dispatcher + marker.asContextElement("application"),
        )
        try {
            app.start()
            assertEquals(workerDispatcher to "application", observed.await())
            assertTrue(callerJob.children.none())
        } finally {
            app.stop()
            assertTrue(callerJob.isActive)
            callerJob.cancel()
        }
    }
}
