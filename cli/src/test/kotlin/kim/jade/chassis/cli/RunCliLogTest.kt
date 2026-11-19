package kim.jade.chassis.cli

import com.github.ajalt.clikt.command.CoreSuspendingCliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import kim.jade.chassis.Application
import kim.jade.chassis.cli.testing.captureCli
import kim.jade.chassis.hook.onStart
import kim.jade.chassis.hook.onStop
import kim.jade.kotlinx.logger.LogLevel
import kim.jade.kotlinx.logger.LogRecord
import kim.jade.kotlinx.logger.Logger
import kim.jade.kotlinx.logger.context.CoroutineThreadLogContext
import kim.jade.kotlinx.logger.context.LogContext
import kim.jade.kotlinx.logger.context.MutableLogContext
import kim.jade.kotlinx.logger.context.ThreadLogContext
import kim.jade.kotlinx.logger.context.currentCoroutineLogContext
import kim.jade.kotlinx.logger.pipeline.SinkPipe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.koin.core.module.Module
import org.koin.dsl.module
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RunCliLogTest {
    private class Say(val calls: MutableList<String>) : CoreSuspendingCliktCommand("say") {
        val text by argument()
        override suspend fun run() { calls += "say:$text" }
    }
    private class Serve : CoreSuspendingCliktCommand("serve") {
        override suspend fun run() { awaitCancellation() }
    }
    private class Fail : CoreSuspendingCliktCommand("fail") {
        override suspend fun run() { throw IllegalStateException("boom") }
    }

    private val logger = Logger.named("test.cli.log")
    private val calls = mutableListOf<String>()
    private val dir = createTempDirectory("cli-log")
    private val file = dir.resolve("out.log")

    @AfterTest fun cleanup() { dir.toFile().deleteRecursively() }

    private fun TestScope.app(vararg modules: Module) =
        Application(modules.toList(), name = "tool", context = StandardTestDispatcher(testScheduler))

    private fun logged(startFailure: Throwable? = null) = module {
        onStart {
            calls += "Start"
            logger.info("after start")
            startFailure?.let { throw it }
        }
    }

    private fun commands() = listOf(Say(calls), Fail())

    @Test fun `the command context is available during the invocation and discarded for shutdown and the next CLI`() = runTest {
        val records = CopyOnWriteArrayList<LogRecord>()
        val pipeline = Logger.defaultPipeline
        pipeline.install(SinkPipe { records += it }, 0)
        val hooks = module {
            onStop {
                logger.info {
                    withCoroutine()
                    "CLI cleanup"
                }
            }
        }
        try {
            for (name in listOf("first", "second")) {
                val command = object : CoreSuspendingCliktCommand(name) {
                    override suspend fun run() {
                        if (name == "first") {
                            currentCoroutineLogContext()["local"] = name
                            ThreadLogContext["threadLocal"] = name
                        }
                        logger.info("sync $name")
                        logger.info {
                            withCoroutine()
                            "run $name"
                        }
                    }
                }
                val result = captureCli {
                    runCli("tool", arrayOf(name), listOf(command)) {
                        Application(
                            listOf(hooks),
                            name = "tool",
                            context = StandardTestDispatcher(testScheduler) +
                                CoroutineThreadLogContext(MutableLogContext("appThread" to name)),
                            logContext = LogContext("invocation" to name),
                        )
                    }
                }
                assertEquals(0, result.code)
            }
        } finally {
            pipeline.uninstall(SinkPipe)
        }

        for (name in listOf("first", "second")) {
            val context = records.single { it.body == "run $name" }.context
            assertEquals("tool", context["application"])
            assertEquals(name, context["invocation"])
            assertEquals(name, context["command"])
            assertNull(context["hook"])
            val thread = records.single { it.body == "sync $name" }.context
            assertEquals(name, thread["command"])
            assertEquals(name, thread["invocation"])
            assertEquals(name, thread["appThread"])
            assertNull(thread["hook"])
            assertEquals(name.takeIf { it == "first" }, thread["local"])
        }
        val cleanup = records.filter { it.body == "CLI cleanup" }
        assertEquals(listOf("first", "second"), cleanup.map { it.context["invocation"] })
        cleanup.forEach {
            assertNull(it.context["command"])
            assertNull(it.context["local"])
            assertNull(it.context["threadLocal"])
        }
        assertEquals("first", records.single { it.body == "run first" }.context["local"])
        assertNull(records.single { it.body == "run second" }.context["local"])
        assertEquals("first", records.single { it.body == "sync first" }.context["threadLocal"])
        assertNull(records.single { it.body == "sync second" }.context["threadLocal"])
        records.filter { it.body.startsWith("Application tool:") }.forEach { assertNull(it.context["command"]) }
    }

    @Test fun `without --log info logs are dropped`() = runTest {
        val result = captureCli { runCli("tool", arrayOf("say", "hi"), commands()) { app(logged()) } }
        assertEquals(0, result.code)
        assertEquals(listOf("Start", "say:hi"), calls)
        assertEquals("", result.out)
        assertEquals("", result.err)
    }

    @Test fun `without --log a failing hook's error log and stack trace are on stderr`() = runTest {
        val failing = logged(IllegalStateException("no database"))
        val result = captureCli { runCli("tool", arrayOf("say", "hi"), commands()) { app(failing) } }
        assertEquals(1, result.code)
        assertEquals("", result.out)
        assertFalse("after start" in result.err)
        assertContains(result.err, "Hook failed: Start/")
        assertContains(result.err, "java.lang.IllegalStateException: no database")
        assertContains(result.err, "\tat ")
        assertContains(result.err, "Error: no database")
    }

    @Test fun `without --log a failing command's error log is on stderr once`() = runTest {
        val result = captureCli { runCli("tool", arrayOf("fail"), commands()) { app(logged()) } }
        assertEquals(1, result.code)
        assertEquals(1, Regex("Command failed: fail").findAll(result.err).count())
        assertEquals(1, Regex("Error: boom").findAll(result.err).count())
    }

    @Test fun `the log says why the application stops after a command`() = runTest {
        val done = captureCli { runCli("tool", arrayOf("--log", "stderr", "say", "hi"), commands()) { app(logged()) } }
        assertContains(done.err, "Application tool: stop requested: command say finished")

        val failed = captureCli { runCli("tool", arrayOf("--log", "stderr", "fail"), commands()) { app(logged()) } }
        assertContains(failed.err, "Application tool: stop requested: command fail failed")
    }

    @Test fun `at debug the log says how long the command took`() = runTest {
        val level = Logger.defaultLevel
        Logger.defaultLevel = LogLevel.DEBUG
        try {
            val result = captureCli { runCli("tool", arrayOf("--log", "stderr", "say", "hi"), commands()) { app(logged()) } }
            assertContains(result.err, "Command say starting")
            assertContains(result.err, Regex("Command say finished in \\d"))
        } finally {
            Logger.defaultLevel = level
        }
    }

    @Test fun `--log stderr sends every log to stderr`() = runTest {
        val result = captureCli { runCli("tool", arrayOf("--log", "stderr", "say", "hi"), commands()) { app(logged()) } }
        assertEquals(0, result.code)
        assertEquals("", result.out)
        assertContains(result.err, "after start")
    }

    @Test fun `--log stdout sends every log to stdout`() = runTest {
        val failing = logged(IllegalStateException("no database"))
        val result = captureCli { runCli("tool", arrayOf("--log", "stdout", "say", "hi"), commands()) { app(failing) } }
        assertEquals(1, result.code)
        assertContains(result.out, "after start")
        assertEquals(1, Regex("Hook failed: Start/").findAll(result.out).count())
        assertFalse("Hook failed" in result.err)
        assertContains(result.err, "Error: no database")
    }

    @Test fun `--log with a path writes every log to the file and appends`() = runTest {
        repeat(2) {
            val result = captureCli {
                runCli("tool", arrayOf("--log", file.toString(), "say", "hi"), commands()) { app(logged()) }
            }
            assertEquals(0, result.code)
            assertEquals("", result.out)
            assertEquals("", result.err)
        }
        assertEquals(2, Regex("after start").findAll(file.readText()).count())
    }

    @Test fun `with a log file a failure's error log is in the file and stderr has only the error line`() = runTest {
        val failing = logged(IllegalStateException("no database"))
        val result = captureCli {
            runCli("tool", arrayOf("--log", file.toString(), "say", "hi"), commands()) { app(failing) }
        }
        assertEquals(1, result.code)
        assertEquals(listOf("Error: no database"), result.err.lines().filter { it.isNotBlank() })
        val text = file.readText()
        assertContains(text, "Hook failed: Start/")
        assertContains(text, "java.lang.IllegalStateException: no database")
        assertContains(text, "\tat ")
    }

    @Test fun `logs written while the application is created go to the target`() = runTest {
        val result = captureCli {
            runCli("tool", arrayOf("--log", file.toString(), "say", "hi"), commands()) {
                logger.info("creating application")
                app(logged())
            }
        }
        assertEquals(0, result.code)
        assertContains(file.readText(), "creating application")
    }

    @Test fun `help, a usage error and no command create no log file and no application`() = runTest {
        val before = consoleSinkState()
        for (arguments in listOf(arrayOf("--help"), arrayOf("say"), emptyArray())) {
            val created = Created { app(logged()) }
            captureCli { runCli("tool", arrayOf("--log", file.toString(), *arguments), commands(), application = created) }
            assertFalse(file.exists(), arguments.joinToString(" "))
            assertNull(created.application, arguments.joinToString(" "))
            assertEquals(before, consoleSinkState(), arguments.joinToString(" "))
        }
    }

    @Test fun `--log after the command name is not the root option`() = runTest {
        val created = Created { app(logged()) }
        val result = captureCli { runCli("tool", arrayOf("say", "--log", file.toString(), "hi"), commands(), application = created) }
        assertEquals(2, result.code)
        assertFalse(file.exists())
        assertNull(created.application)
    }

    @Test fun `a log file that cannot be opened returns 2 and creates no application`() = runTest {
        for (path in listOf(dir.resolve("missing/out.log").toString(), dir.toString())) {
            val created = Created { app(logged()) }
            val result = captureCli { runCli("tool", arrayOf("--log", path, "say", "hi"), commands(), application = created) }
            assertEquals(2, result.code)
            assertContains(result.err, "Error: Cannot open log file '$path': ")
            assertNull(created.application)
        }
        assertEquals(emptyList(), calls)
    }

    @Test fun `help describes --log`() = runTest {
        val result = captureCli { runCli("tool", arrayOf("--help"), commands()) { app() } }
        assertEquals(0, result.code)
        assertContains(result.out, "--log=stdout|stderr|file")
        assertContains(result.out, "errors")
    }

    @Test fun `the name and modules form prints only help on stdout`() = runTest {
        val result = captureCli { runCli("tool", arrayOf("--help"), commands(), logged()) }
        assertEquals(0, result.code)
        assertTrue(result.out.startsWith("Usage: tool"))
        assertEquals("", result.err)
    }

    @Test fun `the logging pipeline is the same after every way of ending`() = runTest {
        val before = consoleSinkState()
        val runs = listOf(
            arrayOf("say", "hi") to logged(),
            arrayOf("--log", "stderr", "say", "hi") to logged(),
            arrayOf("--log", file.toString(), "say", "hi") to logged(IllegalStateException("no database")),
            arrayOf("fail") to logged(),
            arrayOf("--log", dir.toString(), "say", "hi") to logged(),
        )
        for ((arguments, module) in runs) {
            captureCli { runCli("tool", arguments, commands()) { app(module) } }
            assertEquals(before, consoleSinkState(), arguments.joinToString(" "))
        }
    }

    @Test fun `the logging pipeline is restored when the caller is cancelled`() = runTest {
        val before = consoleSinkState()
        val job = launch { runCli("tool", arrayOf("--log", file.toString(), "serve"), listOf(Serve())) { app() } }
        advanceUntilIdle()
        assertTrue(consoleSinkState().cliSinkInstalled)
        job.cancelAndJoin()
        assertEquals(before, consoleSinkState())
    }

    @Test fun `an application that is not NEW leaves an empty log file and restores the pipeline`() = runTest {
        val before = consoleSinkState()
        val app = app(logged())
        app.stop()
        val result = captureCli { runCli("tool", arrayOf("--log", file.toString(), "say", "hi"), commands()) { app } }
        assertEquals(1, result.code)
        assertEquals("", file.readText())
        assertEquals(before, consoleSinkState())
    }
}
