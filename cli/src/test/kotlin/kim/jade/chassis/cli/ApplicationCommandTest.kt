package kim.jade.chassis.cli

import com.github.ajalt.clikt.command.CoreSuspendingCliktCommand
import com.github.ajalt.clikt.command.parse
import com.github.ajalt.clikt.core.obj
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import kim.jade.chassis.Application
import kim.jade.chassis.cli.testing.captureCli
import kim.jade.chassis.hook.onRelease
import kim.jade.chassis.hook.onRequestStop
import kim.jade.chassis.hook.onStarted
import kim.jade.chassis.hook.onStop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ApplicationCommandTest {
    private class Greeter { fun greet(name: String) = "hello $name" }
    private class Greet(val calls: MutableList<String>) : ApplicationCommand("greet") {
        val who by argument()
        override suspend fun run(application: Application) {
            calls += application.koin.get<Greeter>().greet(who)
        }
    }
    private class Parent(val calls: MutableList<String>) : ApplicationCommand("parent") {
        override suspend fun run(application: Application) { calls += "parent:${application.name}" }
    }
    private class Plain(val calls: MutableList<String>) : CoreSuspendingCliktCommand("plain") {
        override suspend fun run() { calls += "plain" }
    }
    private class Serve(val calls: MutableList<String>) : CoreSuspendingCliktCommand("serve") {
        override suspend fun run() {
            calls += "serving"
            try { awaitCancellation() } finally { calls += "cancelled" }
        }
    }
    private class SetsObject : CoreSuspendingCliktCommand("outer") {
        override suspend fun run() { currentContext.obj = "mine" }
    }
    private class ReadsObject(val calls: MutableList<String>) : ApplicationCommand("inner") {
        override suspend fun run(application: Application) {
            calls += "${currentContext.findObject<String>()}:${application.name}"
        }
    }

    private class Db
    private class UsesDb(val calls: MutableList<String>) : ApplicationCommand("db") {
        override suspend fun run(application: Application) {
            application.koin.get<Db>()
            calls += "db"
        }
    }

    private val calls = mutableListOf<String>()
    private val greeter = module { single { Greeter() } }

    private fun TestScope.app(vararg modules: Module) =
        Application(modules.toList(), name = "tool", context = StandardTestDispatcher(testScheduler))

    @Test fun `an application command gets its objects from Koin when it runs`() = runTest {
        val result = captureCli { runCli("tool", arrayOf("greet", "bob"), listOf(Greet(calls))) { app(greeter) } }
        assertEquals(0, result.code)
        assertEquals(listOf("hello bob"), calls)
    }

    @Test fun `plain and application commands can be given together`() = runTest {
        val plain = captureCli { runCli("tool", arrayOf("plain"), listOf(Plain(calls), Greet(calls))) { app(greeter) } }
        val greet = captureCli { runCli("tool", arrayOf("greet", "bob"), listOf(Plain(calls), Greet(calls))) { app(greeter) } }
        assertEquals(0, plain.code)
        assertEquals(0, greet.code)
        assertEquals(listOf("plain", "hello bob"), calls)
    }

    @Test fun `an application command under a group runs after its parent`() = runTest {
        val commands = listOf(Parent(calls).subcommands(Greet(calls)))
        val result = captureCli { runCli("tool", arrayOf("parent", "greet", "bob"), commands) { app(greeter) } }
        assertEquals(0, result.code)
        assertEquals(listOf("parent:tool", "hello bob"), calls)
    }

    @Test fun `an application command still works when another command uses the context object`() = runTest {
        val commands = listOf(SetsObject().subcommands(ReadsObject(calls)))
        val result = captureCli { runCli("tool", arrayOf("outer", "inner"), commands) { app() } }
        assertEquals(0, result.code)
        assertEquals(listOf("mine:tool"), calls)
    }

    @Test fun `an application command run outside runCli fails`() = runTest {
        val thrown = assertFailsWith<IllegalStateException> { Greet(calls).parse(listOf("bob")) }
        assertFalse(thrown is CancellationException)
        assertContains(thrown.message!!, "runCli")
        assertEquals(emptyList(), calls)
    }

    @Test fun `runCli works without modules`() = runTest {
        val result = captureCli { runCli("tool", arrayOf("plain"), listOf(Plain(calls))) }
        assertEquals(0, result.code)
        assertEquals(listOf("plain"), calls)
    }

    @Test fun `the name and modules form builds its own application`() = runTest {
        val result = captureCli { runCli("tool", arrayOf("greet", "bob"), listOf(Greet(calls)), greeter) }
        assertEquals(0, result.code)
        assertEquals(listOf("hello bob"), calls)
    }

    @Test fun `commands appear in help in the order they were given`() = runTest {
        val created = Created { app() }
        val commands = listOf(Plain(calls), Greet(calls))
        val result = captureCli { runCli("tool", arrayOf("--help"), commands, application = created) }
        assertEquals(0, result.code)
        assertTrue(result.out.indexOf("plain") in 0 until result.out.indexOf("greet"))
        assertNull(created.application)
    }

    @Test fun `the command starts after every other Started hook has finished`() = runTest {
        fun hooks() = module {
            onStarted { calls += "Started begin"; delay(1000); calls += "Started end" }
            onStarted { calls += "Started later" }
            onStop { calls += "Stop" }
        }
        val expected = listOf("Started begin", "Started end", "Started later", "plain", "Stop")
        repeat(2) {
            calls.clear()
            val result = captureCli { runCli("tool", arrayOf("plain"), listOf(Plain(calls))) { app(hooks()) } }
            assertEquals(0, result.code)
            assertEquals(expected, calls)
        }
    }

    @Test fun `a stop request cancels the command before the other RequestStop hooks run`() = runTest {
        val application = app(module { onRequestStop { calls += "RequestStop" } })
        val result = captureCli {
            val code = async { runCli("tool", arrayOf("serve"), listOf(Serve(calls))) { application } }
            advanceUntilIdle()
            application.requestStop()
            code.await()
        }
        assertEquals(0, result.code)
        assertEquals(listOf("serving", "cancelled", "RequestStop"), calls)
    }

    @Test fun `a failing definition resolved by a command is reported by its cause`() = runTest {
        val broken = module { single<Db> { throw IllegalStateException("no database") } }
        val result = captureCli { runCli("tool", arrayOf("db"), listOf(UsesDb(calls))) { app(broken) } }
        assertEquals(1, result.code)
        assertEquals("Error: no database", result.err.lines().last { it.isNotBlank() })
        assertEquals(emptyList(), calls)
    }

    @Test fun `a lazy definition that registers a hook cannot be resolved by a command`() = runTest {
        val lazy = module { single { Db().also { onRelease { calls += "Release" } } } }
        val result = captureCli { runCli("tool", arrayOf("db"), listOf(UsesDb(calls))) { app(lazy) } }
        assertEquals(1, result.code)
        val error = result.err.lines().last { it.isNotBlank() }
        assertContains(error, "Error: Hook registered after startup")
        assertContains(error, "createdAtStart")
        assertEquals(emptyList(), calls)
    }

    @Test fun `an eager definition that registers a hook can be resolved by a command`() = runTest {
        val eager = module { single(createdAtStart = true) { Db().also { onRelease { calls += "Release" } } } }
        val result = captureCli { runCli("tool", arrayOf("db"), listOf(UsesDb(calls))) { app(eager) } }
        assertEquals(0, result.code)
        assertEquals(listOf("db", "Release"), calls)
    }

    @Test fun `an exception from the application function propagates and restores logging`() = runTest {
        val before = consoleSinkState()
        val thrown = assertFailsWith<IllegalStateException> {
            runCli("tool", arrayOf("plain"), listOf(Plain(calls))) { throw IllegalStateException("no application") }
        }
        assertEquals("no application", thrown.message)
        assertEquals(emptyList(), calls)
        assertEquals(before, consoleSinkState())
    }
}
