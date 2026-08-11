package kim.jade.chassis.cli

import com.github.ajalt.clikt.command.CoreSuspendingCliktCommand
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import kim.jade.chassis.Application
import kim.jade.chassis.ApplicationStatus
import kim.jade.chassis.cli.testing.captureCli
import kim.jade.chassis.hook.onRelease
import kim.jade.chassis.hook.onStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RunCliFailureTest {
    private class Failing(val failure: () -> Throwable) : CoreSuspendingCliktCommand("fail") {
        override suspend fun run() { throw failure() }
    }
    private class Serve(val calls: MutableList<String>) : CoreSuspendingCliktCommand("serve") {
        override suspend fun run() {
            calls += "serving"
            try { awaitCancellation() } finally { calls += "cancelled" }
        }
    }
    private class Quick(val calls: MutableList<String>) : CoreSuspendingCliktCommand("quick") {
        override suspend fun run() { calls += "run" }
    }
    private class ClosingResult : CoreSuspendingCliktCommand("closing") {
        override suspend fun run() {
            currentContext.callOnClose { throw IllegalStateException("close failed") }
            throw ProgramResult(0)
        }
    }

    private val calls = mutableListOf<String>()

    private fun TestScope.app(vararg modules: Module) =
        Application(modules.toList(), name = "tool", context = StandardTestDispatcher(testScheduler))

    @Test fun `a failing command returns 1 and still runs the shutdown hooks`() = runTest {
        val app = app(module { onRelease { calls += "Release" } })
        val commands = listOf(Failing { IllegalStateException("boom") })
        val result = captureCli { runCli("tool", arrayOf("fail"), commands) { app } }
        assertEquals(1, result.code)
        assertContains(result.err, "Error: boom")
        assertEquals(listOf("Release"), calls)
        assertEquals(ApplicationStatus.FAILED, app.status)
    }

    @Test fun `a status 0 result with a failing context close returns 1`() = runTest {
        val result = captureCli { runCli("tool", arrayOf("closing"), listOf(ClosingResult())) { app() } }
        assertEquals(1, result.code)
        assertContains(result.err, "Error: close failed")
    }

    @Test fun `a failure without a message is reported by its class name`() = runTest {
        val commands = listOf(Failing { IllegalStateException() })
        val result = captureCli { runCli("tool", arrayOf("fail"), commands) { app() } }
        assertEquals(1, result.code)
        assertContains(result.err, "Error: IllegalStateException")
    }

    @Test fun `ProgramResult sets the exit code`() = runTest {
        val three = captureCli { runCli("tool", arrayOf("fail"), listOf(Failing { ProgramResult(3) })) { app() } }
        assertEquals(3, three.code)
        val zeroApp = app()
        val zero = captureCli { runCli("tool", arrayOf("fail"), listOf(Failing { ProgramResult(0) })) { zeroApp } }
        assertEquals(0, zero.code)
        assertEquals(ApplicationStatus.STOPPED, zeroApp.status)
    }

    @Test fun `a usage error thrown by a command returns 2`() = runTest {
        val commands = listOf(Failing { UsageError("bad input") })
        val result = captureCli { runCli("tool", arrayOf("fail"), commands) { app() } }
        assertEquals(2, result.code)
        assertContains(result.err, "bad input")
    }

    @Test fun `a command failure wins over a shutdown failure`() = runTest {
        val app = app(module { onRelease { throw IllegalStateException("leak") } })
        val commands = listOf(Failing { UsageError("bad input") })
        val result = captureCli { runCli("tool", arrayOf("fail"), commands) { app } }
        assertEquals(2, result.code)
        assertContains(result.err, "bad input")
    }

    @Test fun `a status 0 result with a failing shutdown hook returns 1`() = runTest {
        val app = app(module { onRelease { throw IllegalStateException("leak") } })
        val commands = listOf(Failing { PrintMessage("done") })
        val result = captureCli { runCli("tool", arrayOf("fail"), commands) { app } }
        assertEquals(1, result.code)
        assertContains(result.out, "done")
        assertContains(result.err, "Error: leak")
    }

    @Test fun `a command failure is printed once`() = runTest {
        val commands = listOf(Failing { IllegalStateException("boom") })
        val result = captureCli { runCli("tool", arrayOf("fail"), commands) { app() } }
        assertEquals(1, Regex("Error: boom").findAll(result.err).count())
    }

    @Test fun `a failing startup hook returns 1 and the command does not run`() = runTest {
        val app = app(module { onStart { throw IllegalStateException("no database") } })
        val result = captureCli { runCli("tool", arrayOf("quick"), listOf(Quick(calls))) { app } }
        assertEquals(1, result.code)
        assertContains(result.err, "Error: no database")
        assertEquals(emptyList(), calls)
    }

    @Test fun `a failing shutdown hook after a successful command returns 1`() = runTest {
        val app = app(module { onRelease { throw IllegalStateException("leak") } })
        val result = captureCli { runCli("tool", arrayOf("quick"), listOf(Quick(calls))) { app } }
        assertEquals(1, result.code)
        assertContains(result.err, "Error: leak")
        assertEquals(listOf("run"), calls)
    }

    @Test fun `a long running command is cancelled by a stop request and returns 0`() = runTest {
        val app = app()
        val result = captureCli {
            val code = async { runCli("tool", arrayOf("serve"), listOf(Serve(calls))) { app } }
            advanceUntilIdle()
            assertEquals(ApplicationStatus.ACTIVE, app.status)
            assertEquals(listOf("serving"), calls)
            app.requestStop()
            code.await()
        }
        assertEquals(0, result.code)
        assertEquals(listOf("serving", "cancelled"), calls)
        assertEquals(ApplicationStatus.STOPPED, app.status)
    }

    @Test fun `a stop with a cause cancels the command and returns 1`() = runTest {
        val app = app()
        val result = captureCli {
            val code = async { runCli("tool", arrayOf("serve"), listOf(Serve(calls))) { app } }
            advanceUntilIdle()
            app.requestStop(IllegalStateException("worker died"))
            code.await()
        }
        assertEquals(1, result.code)
        assertContains(result.err, "Error: worker died")
        assertEquals(listOf("serving", "cancelled"), calls)
    }

    @Test fun `a command that cancels itself returns 1`() = runTest {
        val app = app()
        val commands = listOf(Failing { CancellationException("self") })
        val result = captureCli { runCli("tool", arrayOf("fail"), commands) { app } }
        assertEquals(1, result.code)
        assertEquals(ApplicationStatus.FAILED, app.status)
    }

    @Test fun `an application that was already started returns 1 and is left running`() = runTest {
        val app = app()
        app.start()
        val result = captureCli { runCli("tool", arrayOf("quick"), listOf(Quick(calls))) { app } }
        assertEquals(1, result.code)
        assertContains(result.err, "Error: Application cannot start from ACTIVE")
        assertEquals(emptyList(), calls)
        assertEquals(ApplicationStatus.ACTIVE, app.status)
        assertFalse(CommandLineArguments in app.attributes)
        app.stop()
    }

    @Test fun `an application that already stopped returns 1`() = runTest {
        val app = app()
        app.stop()
        val result = captureCli { runCli("tool", arrayOf("quick"), listOf(Quick(calls))) { app } }
        assertEquals(1, result.code)
        assertContains(result.err, "Error: Application cannot start from STOPPED")
        assertEquals(emptyList(), calls)
    }

    @Test fun `help does not ask for the application`() = runTest {
        val created = Created { error("not needed") }
        val result = captureCli { runCli("tool", arrayOf("--help"), listOf(Quick(calls)), application = created) }
        assertEquals(0, result.code)
        assertContains(result.out, "Usage: tool")
        assertNull(created.application)
    }

    @Test fun `cancelling the caller stops the application and propagates`() = runTest {
        val app = app()
        val job = launch { runCli("tool", arrayOf("serve"), listOf(Serve(calls))) { app } }
        advanceUntilIdle()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(ApplicationStatus.STOPPED, app.status)
        assertEquals(listOf("serving", "cancelled"), calls)
    }
}
