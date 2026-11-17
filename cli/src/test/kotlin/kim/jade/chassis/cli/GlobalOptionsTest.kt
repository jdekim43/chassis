package kim.jade.chassis.cli

import com.github.ajalt.clikt.command.CoreSuspendingCliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.option
import kim.jade.chassis.Application
import kim.jade.chassis.ApplicationStatus
import kim.jade.chassis.cli.testing.captureCli
import kim.jade.chassis.hook.onStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class GlobalOptionsTest {
    private class Recording(
        val calls: MutableList<String>,
        val label: String,
        name: String = "--$label",
        val prepareFailure: Throwable? = null,
        val applyFailure: Throwable? = null,
    ) : GlobalOptions() {
        val value by option(name)
        override fun prepare() {
            calls += "prepare:$label:$value"
            prepareFailure?.let { throw it }
        }
        override fun apply(application: Application) {
            calls += "apply:$label:${application.status}"
            applyFailure?.let { throw it }
        }
    }
    private class Run(val calls: MutableList<String>) : CoreSuspendingCliktCommand("run") {
        override suspend fun run() { calls += "run" }
    }

    private val calls = mutableListOf<String>()

    private fun TestScope.app(vararg modules: Module) =
        Application(modules.toList(), name = "tool", context = StandardTestDispatcher(testScheduler))
    private fun started() = module { onStart { calls += "Start" } }

    @Test fun `prepare runs before the application exists and apply before it starts, in the order given`() = runTest {
        val options = listOf(Recording(calls, "a"), Recording(calls, "b"))
        val created = Created { calls += "create"; app(started()) }
        val result = captureCli {
            runCli("tool", arrayOf("--a", "x", "run"), listOf(Run(calls)), options, created)
        }
        assertEquals(0, result.code)
        assertEquals(
            listOf("prepare:a:x", "prepare:b:null", "create", "apply:a:NEW", "apply:b:NEW", "Start", "run"),
            calls,
        )
    }

    @Test fun `an option of a group is listed in help and unknown without the group`() = runTest {
        val help = captureCli {
            runCli("tool", arrayOf("--help"), listOf(Run(calls)), listOf(Recording(calls, "a"))) { app() }
        }
        assertContains(help.out, "--a")
        val without = captureCli { runCli("tool", arrayOf("--a", "x", "run"), listOf(Run(calls))) { app() } }
        assertEquals(2, without.code)
        assertEquals(emptyList(), calls)
    }

    @Test fun `help, a usage error and no command call neither prepare nor apply`() = runTest {
        for (arguments in listOf(arrayOf("--help"), arrayOf("nope"), emptyArray())) {
            val created = Created { app() }
            captureCli { runCli("tool", arguments, listOf(Run(calls)), listOf(Recording(calls, "a")), created) }
            assertNull(created.application, arguments.joinToString(" "))
        }
        assertEquals(emptyList(), calls)
    }

    @Test fun `a failing prepare returns 1, logs the failure and creates no application`() = runTest {
        val before = consoleSinkState()
        val failing = Recording(calls, "a", prepareFailure = IllegalStateException("bad file", IllegalArgumentException("line 3")))
        val created = Created { app() }
        val result = captureCli { runCli("tool", arrayOf("run"), listOf(Run(calls)), listOf(failing), created) }
        assertEquals(1, result.code)
        assertNull(created.application)
        assertContains(result.err, "Caused by: java.lang.IllegalArgumentException: line 3")
        assertEquals("Error: bad file", result.err.lines().last { it.isNotBlank() })
        assertEquals(before, consoleSinkState())
    }

    @Test fun `a usage error from prepare returns 2 without an error log`() = runTest {
        val failing = Recording(calls, "a", prepareFailure = UsageError("pick one"))
        val result = captureCli { runCli("tool", arrayOf("run"), listOf(Run(calls)), listOf(failing)) { app() } }
        assertEquals(2, result.code)
        assertContains(result.err, "pick one")
        assertFalse("\tat " in result.err)
    }

    @Test fun `a failing apply returns 1 and closes the application without starting it`() = runTest {
        val failing = Recording(calls, "a", applyFailure = IllegalStateException("cannot merge"))
        val application = app(started())
        val result = captureCli { runCli("tool", arrayOf("run"), listOf(Run(calls)), listOf(failing)) { application } }
        assertEquals(1, result.code)
        assertEquals("Error: cannot merge", result.err.lines().last { it.isNotBlank() })
        assertEquals(ApplicationStatus.STOPPED, application.status)
        assertEquals(listOf("prepare:a:null", "apply:a:NEW"), calls)
    }

    @Test fun `apply is not called for an application that is not NEW`() = runTest {
        val application = app()
        application.stop()
        val result = captureCli {
            runCli("tool", arrayOf("run"), listOf(Run(calls)), listOf(Recording(calls, "a"))) { application }
        }
        assertEquals(1, result.code)
        assertEquals(listOf("prepare:a:null"), calls)
    }

    @Test fun `two groups declaring the same option name return 1`() = runTest {
        val options = listOf(Recording(calls, "a", name = "--same"), Recording(calls, "b", name = "--same"))
        val created = Created { app() }
        val result = captureCli { runCli("tool", arrayOf("run"), listOf(Run(calls)), options, created) }
        assertEquals(1, result.code)
        assertContains(result.err, "--same")
        assertNull(created.application)
    }

    @Test fun `the name and modules form takes options`() = runTest {
        val result = captureCli {
            runCli("tool", arrayOf("--a", "x", "run"), listOf(Run(calls)), started(), options = listOf(Recording(calls, "a")))
        }
        assertEquals(0, result.code)
        assertEquals(listOf("prepare:a:x", "apply:a:NEW", "Start", "run"), calls)
    }
}
