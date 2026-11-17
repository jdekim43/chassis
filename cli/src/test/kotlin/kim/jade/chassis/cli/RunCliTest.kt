package kim.jade.chassis.cli

import com.github.ajalt.clikt.command.CoreSuspendingCliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import kim.jade.chassis.Application
import kim.jade.chassis.ApplicationStatus
import kim.jade.chassis.cli.testing.captureCli
import kim.jade.chassis.hook.hook
import kim.jade.chassis.hook.onInitialize
import kim.jade.chassis.hook.onRelease
import kim.jade.chassis.hook.onStart
import kim.jade.chassis.hook.onStop
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RunCliTest {
    private class Echo(val calls: MutableList<String>) : CoreSuspendingCliktCommand("echo") {
        val text by argument()
        override suspend fun run() { calls += "run:$text" }
    }
    private class Group : CoreSuspendingCliktCommand("group") {
        override suspend fun run() = Unit
    }
    private class AlsoEcho : CoreSuspendingCliktCommand("echo") {
        override suspend fun run() = Unit
    }

    private val calls = mutableListOf<String>()

    private fun TestScope.app(vararg modules: Module) =
        Application(modules.toList(), name = "tool", context = StandardTestDispatcher(testScheduler))

    private fun lifecycle() = module {
        onInitialize { calls += "Initialize" }
        onStart { calls += "Start" }
        onStop { calls += "Stop" }
        onRelease { calls += "Release" }
    }

    @Test fun `a command runs between the startup and shutdown hooks and returns 0`() = runTest {
        val app = app(lifecycle())
        val result = captureCli { runCli("tool", arrayOf("echo", "hi"), listOf(Echo(calls))) { app } }
        assertEquals(0, result.code)
        assertEquals(listOf("Initialize", "Start", "run:hi", "Stop", "Release"), calls)
        assertEquals(ApplicationStatus.STOPPED, app.status)
    }

    @Test fun `help prints to stdout and starts nothing`() = runTest {
        val created = Created { app(lifecycle()) }
        val result = captureCli { runCli("tool", arrayOf("--help"), listOf(Echo(calls)), application = created) }
        assertEquals(0, result.code)
        assertContains(result.out, "Usage: tool")
        assertEquals(emptyList(), calls)
        assertNull(created.application)
    }

    @Test fun `help for a command starts nothing`() = runTest {
        val created = Created { app(lifecycle()) }
        val result = captureCli { runCli("tool", arrayOf("echo", "--help"), listOf(Echo(calls)), application = created) }
        assertEquals(0, result.code)
        assertContains(result.out, "Usage: tool echo")
        assertEquals(emptyList(), calls)
        assertNull(created.application)
    }

    @Test fun `no command prints help and starts nothing`() = runTest {
        val created = Created { app(lifecycle()) }
        val result = captureCli { runCli("tool", emptyArray(), listOf(Echo(calls)), application = created) }
        assertEquals(0, result.code)
        assertContains(result.out, "Usage: tool")
        assertEquals(emptyList(), calls)
        assertNull(created.application)
    }

    @Test fun `an application without commands prints help and returns 0`() = runTest {
        val created = Created { app(lifecycle()) }
        val result = captureCli { runCli("tool", emptyArray(), emptyList(), application = created) }
        assertEquals(0, result.code)
        assertContains(result.out, "Usage: tool")
        assertNull(created.application)
    }

    @Test fun `a usage error returns 2 on stderr and starts nothing`() = runTest {
        val created = Created { app(lifecycle()) }
        val missing = captureCli { runCli("tool", arrayOf("echo"), listOf(Echo(calls)), application = created) }
        assertEquals(2, missing.code)
        assertTrue(missing.err.isNotBlank())
        val unknown = captureCli { runCli("tool", arrayOf("nope"), listOf(Echo(calls)), application = created) }
        assertEquals(2, unknown.code)
        assertTrue(unknown.err.isNotBlank())
        assertEquals(emptyList(), calls)
        assertNull(created.application)
    }

    @Test fun `a group without its subcommand prints its help to stderr and starts nothing`() = runTest {
        val created = Created { app(lifecycle()) }
        val commands = listOf(Group().subcommands(Echo(calls)))
        val result = captureCli { runCli("tool", arrayOf("group"), commands, application = created) }
        assertEquals(0, result.code)
        assertContains(result.err, "Usage: tool group")
        assertEquals(emptyList(), calls)
        assertNull(created.application)
    }

    @Test fun `duplicate command names return 1 and start nothing`() = runTest {
        val created = Created { app(lifecycle()) }
        val result = captureCli { runCli("tool", arrayOf("echo", "hi"), listOf(Echo(calls), AlsoEcho()), application = created) }
        assertEquals(1, result.code)
        assertContains(result.err, "Error: Duplicate command 'echo'")
        assertEquals(emptyList(), calls)
        assertNull(created.application)
    }

    @Test fun `hooks read the running command and the arguments`() = runTest {
        var running: CoreSuspendingCliktCommand? = null
        var arguments: List<String>? = null
        val app = app(module {
            hook {
                val self = get<Application>()
                onStart {
                    running = self.attributes.getOrNull(RunningCommand)
                    arguments = self.attributes.getOrNull(CommandLineArguments)
                }
            }
        })
        captureCli { runCli("tool", arrayOf("echo", "hi"), listOf(Echo(calls))) { app } }
        assertIs<Echo>(running)
        assertEquals(listOf("echo", "hi"), arguments)
    }

    @Test fun `the running command is the innermost one in a group`() = runTest {
        var running: CoreSuspendingCliktCommand? = null
        val app = app(module {
            hook { val self = get<Application>(); onStart { running = self.attributes.getOrNull(RunningCommand) } }
        })
        val commands = listOf(Group().subcommands(Echo(calls)))
        val result = captureCli { runCli("tool", arrayOf("group", "echo", "hi"), commands) { app } }
        assertEquals(0, result.code)
        assertIs<Echo>(running)
        assertEquals(listOf("run:hi"), calls)
    }

    private class Closing(val calls: MutableList<String>, val closeFailure: Throwable? = null) :
        CoreSuspendingCliktCommand("closing") {
        override suspend fun run() {
            currentContext.callOnClose {
                calls += "closed"
                closeFailure?.let { throw it }
            }
            calls += "run"
        }
    }

    private class Other(val calls: MutableList<String>) : CoreSuspendingCliktCommand("other") {
        override suspend fun run() { calls += "other" }
    }

    @Test fun `a context closeable is closed after the command and before the shutdown hooks`() = runTest {
        val result = captureCli { runCli("tool", arrayOf("closing"), listOf(Closing(calls))) { app(lifecycle()) } }
        assertEquals(0, result.code)
        assertEquals(listOf("Initialize", "Start", "run", "closed", "Stop", "Release"), calls)
    }

    @Test fun `a throwing context closeable fails the application instead of hanging`() = runTest {
        val app = app(lifecycle())
        val commands = listOf(Closing(calls, IllegalStateException("close failed")))
        val result = captureCli { runCli("tool", arrayOf("closing"), commands) { app } }
        assertEquals(1, result.code)
        assertContains(result.err, "Error: close failed")
        assertEquals(ApplicationStatus.FAILED, app.status)
        assertEquals(listOf("Initialize", "Start", "run", "closed", "Stop", "Release"), calls)
    }

    @Test fun `only the selected one of two commands runs, once`() = runTest {
        val result = captureCli { runCli("tool", arrayOf("echo", "hi"), listOf(Echo(calls), Other(calls))) { app() } }
        assertEquals(0, result.code)
        assertEquals(listOf("run:hi"), calls)
    }
}
