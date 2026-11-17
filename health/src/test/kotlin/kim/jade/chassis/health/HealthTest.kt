package kim.jade.chassis.health

import kim.jade.chassis.Application
import kim.jade.chassis.ApplicationStatus
import kim.jade.chassis.hook.onStart
import kim.jade.chassis.hook.onStop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class HealthTest {
    private class Dependency(val name: String)

    private fun TestScope.application(vararg modules: Module) =
        Application(modules.toList(), context = StandardTestDispatcher(testScheduler))

    private val healthy = HealthCheckResult("ok", healthy = true)

    @Test fun `without checks the report follows the application status`() = runTest {
        val app = application()
        assertEquals(HealthReport(ApplicationStatus.NEW, false, emptyList()), app.checkLiveness())
        assertEquals(HealthReport(ApplicationStatus.NEW, false, emptyList()), app.checkReadiness())
        app.start()
        assertEquals(HealthReport(ApplicationStatus.ACTIVE, true, emptyList()), app.checkLiveness())
        assertEquals(HealthReport(ApplicationStatus.ACTIVE, true, emptyList()), app.checkReadiness())
        app.stop()
        assertEquals(HealthReport(ApplicationStatus.STOPPED, false, emptyList()), app.checkLiveness())
        assertEquals(HealthReport(ApplicationStatus.STOPPED, false, emptyList()), app.checkReadiness())
    }

    @Test fun `a failed application is neither alive nor ready`() = runTest {
        val app = application(module { liveness("ok") { Health.healthy() } })
        app.start()
        app.requestStop(IllegalStateException("broken"))
        runCatching { app.awaitTermination() }
        assertEquals(HealthReport(ApplicationStatus.FAILED, false, emptyList()), app.checkLiveness())
        assertEquals(HealthReport(ApplicationStatus.FAILED, false, emptyList()), app.checkReadiness())
    }

    @Test fun `a check reads objects from Koin and reports its name health and message`() = runTest {
        var created = 0
        val app = application(module {
            single { created++; Dependency("db") }
            readiness("storage") { Health.unhealthy("no connection to ${get<Dependency>().name}") }
            liveness("loop") { Health.healthy("running") }
        })
        app.start()
        assertEquals(0, created)
        assertEquals(
            HealthReport(ApplicationStatus.ACTIVE, false, listOf(HealthCheckResult("storage", false, "no connection to db"))),
            app.checkReadiness(),
        )
        assertEquals(1, created)
        assertEquals(
            HealthReport(ApplicationStatus.ACTIVE, true, listOf(HealthCheckResult("loop", true, "running"))),
            app.checkLiveness(),
        )
        assertEquals(ApplicationStatus.ACTIVE, app.status)
        app.stop()
    }

    @Test fun `every check of the kind runs and the two kinds may share a name`() = runTest {
        val ran = mutableListOf<String>()
        val first = module {
            liveness("b") { ran += "b"; Health.healthy() }
            readiness("shared") { ran += "ready"; Health.healthy() }
        }
        val second = module {
            liveness("a") { ran += "a"; Health.unhealthy() }
            liveness("shared") { ran += "alive"; Health.healthy() }
        }
        val app = application(second, first)
        app.start()
        val report = app.checkLiveness()
        assertEquals(listOf("a", "alive", "b"), ran.sorted())
        assertEquals(
            listOf("a" to false, "b" to true, "shared" to true),
            report.checks.map { it.name to it.healthy }.sortedBy { it.first },
        )
        assertFalse(report.healthy)
        ran.clear()
        assertEquals(listOf("shared"), app.checkReadiness().checks.map { it.name })
        assertEquals(listOf("ready"), ran)
        app.stop()
    }

    @Test fun `a name declared twice for one kind fails the start`() = runTest {
        val sameModule = application(module {
            liveness("storage") { Health.healthy() }
            liveness("storage") { Health.healthy() }
        })
        val failure = assertFailsWith<IllegalArgumentException> { sameModule.start() }
        assertContains(failure.message.orEmpty(), "liveness")
        assertContains(failure.message.orEmpty(), "'storage'")
        assertEquals(ApplicationStatus.FAILED, sameModule.status)

        val twoModules = application(
            module { readiness("storage") { Health.healthy() } },
            module { readiness("storage") { Health.healthy() } },
        )
        assertContains(assertFailsWith<IllegalArgumentException> { twoModules.start() }.message.orEmpty(), "readiness")
    }

    @Test fun `applications built from the same module each have their own checks`() = runTest {
        val shared = module { liveness("ok") { Health.healthy() } }
        val one = application(shared)
        val other = application(shared)
        one.start()
        other.start()
        assertEquals(listOf(healthy), one.checkLiveness().checks)
        assertEquals(listOf(healthy), other.checkLiveness().checks)
        one.stop()
        assertEquals(listOf(healthy), other.checkLiveness().checks)
        other.stop()
    }

    @Test fun `a check that throws is unhealthy with its cause and the others still run`() = runTest {
        val broken = IllegalStateException("broken")
        val app = application(module {
            liveness("first") { throw broken }
            liveness("ok") { Health.healthy() }
        })
        app.start()
        val report = app.checkLiveness()
        assertFalse(report.healthy)
        assertEquals(setOf(HealthCheckResult("first", false, null, broken), healthy), report.checks.toSet())
        assertSame(broken, report.checks.single { it.name == "first" }.cause)
        app.stop()
    }

    @Test fun `a check can hand over the exception behind an unhealthy result`() = runTest {
        val refused = IllegalStateException("refused")
        val app = application(module { readiness("storage") { Health.unhealthy("no connection", refused) } })
        app.start()
        assertEquals(
            listOf(HealthCheckResult("storage", false, "no connection", refused)),
            app.checkReadiness().checks,
        )
        app.stop()
    }

    @Test fun `a check that cancels itself is a failed check and the others still run`() = runTest {
        val app = application(module {
            liveness("timeout") { withTimeout(1.seconds) { awaitCancellation() } }
            liveness("cancelled") { throw CancellationException("gave up") }
            liveness("ok") { Health.healthy() }
        })
        app.start()
        val report = app.checkLiveness()
        assertFalse(report.healthy)
        assertEquals(mapOf("timeout" to false, "cancelled" to false, "ok" to true), report.checks.associate { it.name to it.healthy })
        assertIs<TimeoutCancellationException>(report.checks.single { it.name == "timeout" }.cause)
        assertIs<CancellationException>(report.checks.single { it.name == "cancelled" }.cause)
        assertEquals(ApplicationStatus.ACTIVE, app.status)
        app.stop()
    }

    @Test fun `an error is not turned into a result`() = runTest {
        val app = application(module { readiness("error") { throw AssertionError("boom") } })
        app.start()
        assertFailsWith<AssertionError> { app.checkReadiness() }
        app.stop()
    }

    @Test fun `a caller's timeout ends the call without running the remaining checks`() = runTest {
        var entered = 0
        val app = application(module {
            liveness("stuck") { entered++; awaitCancellation() }
            liveness("also stuck") { entered++; awaitCancellation() }
        })
        app.start()
        assertFailsWith<TimeoutCancellationException> { withTimeout(1.seconds) { app.checkLiveness() } }
        assertEquals(1, entered)
        assertEquals(ApplicationStatus.ACTIVE, app.status)
        app.stop()
    }

    @Test fun `callers asking at the same time each get a whole report`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var entered = 0
        val app = application(module {
            liveness("slow") { entered++; gate.await(); Health.healthy() }
            liveness("ok") { Health.healthy() }
        })
        app.start()
        val reports = List(2) { async { app.checkLiveness() } }
        runCurrent()
        assertEquals(2, entered)
        gate.complete(Unit)
        for (report in reports.awaitAll()) {
            assertEquals(ApplicationStatus.ACTIVE, report.status)
            assertEquals(true, report.healthy)
            assertEquals(setOf(HealthCheckResult("slow", true), healthy), report.checks.toSet())
        }
        app.stop()
    }

    @Test fun `no check runs before the application starts or after it ends`() = runTest {
        var ran = 0
        val app = application(module {
            liveness("counted") { ran++; Health.healthy() }
            readiness("counted") { ran++; Health.healthy() }
        })
        app.checkLiveness()
        app.checkReadiness()
        app.start()
        app.stop()
        app.checkLiveness()
        app.checkReadiness()
        assertEquals(0, ran)
    }

    @Test fun `while starting the application is alive and not ready and no check runs`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var ran = 0
        val app = application(module {
            onStart { gate.await() }
            liveness("unhealthy") { ran++; Health.unhealthy() }
            readiness("unhealthy") { ran++; Health.unhealthy() }
        })
        app.requestStart()
        runCurrent()
        assertEquals(HealthReport(ApplicationStatus.STARTING, true, emptyList()), app.checkLiveness())
        assertEquals(HealthReport(ApplicationStatus.STARTING, false, emptyList()), app.checkReadiness())
        assertEquals(0, ran)
        gate.complete(Unit)
        app.awaitActive()
        assertFalse(app.checkLiveness().healthy)
        app.stop()
    }

    @Test fun `while stopping the application is alive and not ready and no check runs`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var ran = 0
        val app = application(module {
            onStop { gate.await() }
            liveness("unhealthy") { ran++; Health.unhealthy() }
            readiness("unhealthy") { ran++; Health.unhealthy() }
        })
        app.start()
        app.requestStop()
        runCurrent()
        assertEquals(HealthReport(ApplicationStatus.STOPPING, true, emptyList()), app.checkLiveness())
        assertEquals(HealthReport(ApplicationStatus.STOPPING, false, emptyList()), app.checkReadiness())
        assertEquals(0, ran)
        gate.complete(Unit)
        app.awaitTermination()
    }

    @Test fun `a stop requested while checking is answered from the status and keeps the results`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val app = application(module {
            onStop { gate.await() }
            liveness("stopper") { get<Application>().requestStop(); Health.unhealthy("stopping") }
        })
        app.start()
        assertEquals(
            HealthReport(ApplicationStatus.STOPPING, true, listOf(HealthCheckResult("stopper", false, "stopping"))),
            app.checkLiveness(),
        )
        gate.complete(Unit)
        app.awaitTermination()

        val again = application(module {
            onStop { gate.await() }
            readiness("ok") { Health.healthy() }
            readiness("stopper") { get<Application>().requestStop(); Health.healthy() }
        })
        again.start()
        val report = again.checkReadiness()
        assertEquals(ApplicationStatus.STOPPING, report.status)
        assertFalse(report.healthy)
        assertEquals(setOf(healthy, HealthCheckResult("stopper", true)), report.checks.toSet())
        again.awaitTermination()
    }

    @Test fun `a blank name is rejected when the check is declared`() {
        assertFailsWith<IllegalArgumentException> { module { liveness(" ") { Health.healthy() } } }
        assertFailsWith<IllegalArgumentException> { module { readiness("") { Health.healthy() } } }
    }
}
