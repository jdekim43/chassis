package kim.jade.chassis.ktor

import io.ktor.server.cio.CIO
import io.ktor.server.engine.ConnectorType
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kim.jade.chassis.Application
import kim.jade.chassis.ApplicationStatus
import kim.jade.chassis.hook.hook
import kim.jade.chassis.hook.onRelease
import kim.jade.chassis.hook.onStarted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.koin.core.module.Module
import org.koin.dsl.module
import java.net.BindException
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val SHUTDOWN_HOOK = "io.ktor.server.engine.ShutdownHook"

class KtorServerRulesTest {
    private class Hooked

    private fun Module.openOnStarted() = hook {
        val server = get<KtorServer>()
        onStarted("ktor server") { server.start() }
    }

    private suspend fun startFailure(server: KtorServer) =
        assertFailsWith<IllegalStateException> { server.start() }.message

    private fun withShutdownHookProperty(value: String?, block: () -> Unit) {
        val before = System.getProperty(SHUTDOWN_HOOK)
        fun set(text: String?) {
            if (text == null) System.clearProperty(SHUTDOWN_HOOK) else System.setProperty(SHUTDOWN_HOOK, text)
        }
        set(value)
        try {
            block()
        } finally {
            set(before)
        }
    }

    @Test fun `a second start fails and the server keeps running`() = serverTest(module { testServer() }) { app ->
        app.start()
        val server = app.koin.get<KtorServer>()
        server.start()
        assertEquals("Ktor server is already started", startFailure(server))
        assertTrue(isListening(app.serverPort()))
    }

    @Test fun `concurrent starts open the server once`() = serverTest(module { testServer() }) { app ->
        app.start()
        val server = app.koin.get<KtorServer>()
        val results = List(2) { async(Dispatchers.Default) { runCatching { server.start() } } }.awaitAll()
        assertEquals(1, results.count { it.isSuccess })
        assertEquals("Ktor server is already started", results.single { it.isFailure }.exceptionOrNull()?.message)
    }

    @Test fun `start after the application stopped fails`() = serverTest(module { testServer() }) { app ->
        app.start()
        val server = app.koin.get<KtorServer>()
        app.stop()
        assertEquals("Ktor server is already stopped", startFailure(server))
        assertEquals("Ktor server is not running", assertFailsWith<IllegalStateException> { server.connectors() }.message)
    }

    @Test fun `start before the application starts fails`() = withPortInUse { port ->
        serverTest(module { testServer(port) }) { app ->
            val server = app.koin.get<KtorServer>()
            assertEquals("Ktor server cannot start before the application starts", startFailure(server))
        }
    }

    @Test fun `start on an application that stops without starting opens nothing`() = runBlocking {
        repeat(30) { attempt ->
            val port = freePort()
            val app = Application(listOf(module { testServer(port) }))
            val server = app.koin.get<KtorServer>()
            val starting = async(Dispatchers.Default) { runCatching { server.start() } }
            app.requestStop()
            app.awaitTermination()
            assertTrue(starting.await().isFailure, "attempt $attempt")
            assertFalse(isListening(port), "attempt $attempt")
        }
    }

    @Test fun `start without a connector fails`() = serverTest(module { ktorServer(CIO) { } }) { app ->
        app.start()
        val server = app.koin.get<KtorServer>()
        assertEquals("Ktor server has no connector", startFailure(server))
        assertEquals("Ktor server is already stopped", startFailure(server))
    }

    @Test fun `a failing block fails start and leaves nothing listening`() {
        val port = freePort()
        serverTest(module {
            testServer(port)
            ktor { throw IllegalArgumentException("broken route") }
        }) { app ->
            app.start()
            val server = app.koin.get<KtorServer>()
            val failure = assertFailsWith<Exception> { server.start() }
            assertTrue(failure.causes().any { it is IllegalArgumentException && it.message == "broken route" }, "$failure")
            assertFalse(isListening(port))
            assertEquals("Ktor server is already stopped", startFailure(server))
        }
    }

    @Test fun `an object that registers a hook cannot be created first inside a block`() = serverTest(module {
        single { Hooked().also { onRelease { } } }
        testServer()
        ktor { get<Hooked>() }
    }) { app ->
        app.start()
        val failure = assertFailsWith<Exception> { app.koin.get<KtorServer>().start() }
        assertTrue(failure.causes().any { "createdAtStart = true" in it.message.orEmpty() }, "$failure")
    }

    @Test fun `an auto-started server answers once the application is active`() = serverTest(module {
        testServer(autoStart = true)
        ktor { it.routing { get("/") { call.respondText("ok") } } }
    }) { app ->
        app.start()
        assertEquals(200, fetch(app.serverPort(), "/").status)
    }

    private fun assertCouldNotOpen(port: Int, failure: Throwable) {
        // 앱을 거쳐 나온 예외는 코루틴이 스택을 되살리며 만든 사본일 수 있어 원인을 사슬에서 찾는다.
        val cause = failure.causes().firstOrNull { it is BindException }
        assertNotNull(cause, "$failure")
        assertEquals("Ktor server failed to start on http://127.0.0.1:$port: ${cause.message}", failure.message)
    }

    @Test fun `a port in use fails the start of an application with an auto-started server`() = withPortInUse { port ->
        serverTest(module { testServer(port, autoStart = true) }) { app ->
            assertCouldNotOpen(port, assertFailsWith<IllegalStateException> { app.start() })
            runCatching { app.awaitTermination() }
            assertEquals(ApplicationStatus.FAILED, app.status)
        }
    }

    @Test fun `a port in use fails a direct start and names the address`() = withPortInUse { port ->
        serverTest(module { testServer(port) }) { app ->
            app.start()
            val server = app.koin.get<KtorServer>()
            val failure = assertFailsWith<IllegalStateException> { server.start() }
            assertCouldNotOpen(port, failure)
            assertEquals(emptyList<Throwable>(), failure.suppressedExceptions)
            assertEquals("Ktor server is already stopped", startFailure(server))
        }
    }

    @Test fun `a failing block does not touch a port that is in use`() = withPortInUse { port ->
        recordingUncaught { uncaught ->
            serverTest(module {
                testServer(port)
                ktor { throw IllegalArgumentException("broken route") }
            }) { app ->
                app.start()
                val failure = assertFailsWith<Exception> { app.koin.get<KtorServer>().start() }
                assertTrue(failure.causes().any { it is IllegalArgumentException }, "$failure")
                assertTrue(failure.causes().none { it is BindException }, "$failure")
            }
            assertEquals(emptyList<Throwable>(), uncaught.toList())
        }
    }

    @Test fun `a start failure names the scheme of the connector`() = serverTest(module {
        ktorServer(CIO) { engine ->
            engine.connectors += EngineConnectorBuilder(ConnectorType.HTTPS).apply {
                host = "127.0.0.1"
                port = 0
            }
        }
    }) { app ->
        app.start()
        val message = startFailure(app.koin.get<KtorServer>()).orEmpty()
        assertTrue(message.startsWith("Ktor server failed to start on https://127.0.0.1:0"), message)
    }

    @Test fun `a server opened from a Started hook answers after the application is active`() = serverTest(module {
        testServer()
        ktor { it.routing { get("/") { call.respondText("ok") } } }
        openOnStarted()
    }) { app ->
        app.start()
        val port = withTimeout(5.seconds) {
            var found: Int? = null
            while (found == null) {
                found = runCatching { app.serverPort() }.getOrNull()
                if (found == null) delay(10.milliseconds)
            }
            found
        }
        assertEquals(200, fetch(port, "/").status)
    }

    @Test fun `a port in use fails a running application when opened from a Started hook`() = withPortInUse { port ->
        serverTest(module {
            testServer(port)
            openOnStarted()
        }) { app ->
            app.start()
            assertCouldNotOpen(port, assertFailsWith<IllegalStateException> { app.awaitTermination() })
            assertEquals(ApplicationStatus.FAILED, app.status)
        }
    }

    @Test fun `a cancelled start leaves nothing listening`() {
        val port = freePort()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        serverTest(module {
            testServer(port)
            ktor {
                entered.countDown()
                release.await()
            }
        }) { app ->
            app.start()
            val server = app.koin.get<KtorServer>()
            val starting = launch(Dispatchers.Default) { server.start() }
            withContext(Dispatchers.IO) { entered.await() }
            starting.cancel()
            release.countDown()
            starting.join()
            assertFalse(isListening(port))
            assertEquals("Ktor server is already stopped", startFailure(server))
        }
    }

    @Test fun `a stop requested while an auto-started server is opening ends without failure`() {
        val port = freePort()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        serverTest(module {
            testServer(port, autoStart = true)
            ktor {
                entered.countDown()
                release.await()
            }
        }) { app ->
            app.requestStart()
            withContext(Dispatchers.IO) { entered.await() }
            app.requestStop()
            release.countDown()
            app.awaitTermination()
            assertEquals(ApplicationStatus.STOPPED, app.status)
            assertFalse(isListening(port))
        }
    }

    @Test fun `blocks without a server definition leave the application alone`() {
        var ran = false
        serverTest(module { ktor { ran = true } }) { app ->
            app.start()
            app.stop()
            assertEquals(ApplicationStatus.STOPPED, app.status)
            assertFalse(ran)
        }
    }

    @Test fun `start turns the Ktor shutdown hook off when the property is unset`() = withShutdownHookProperty(null) {
        serverTest(module { testServer() }) { app ->
            app.start()
            app.koin.get<KtorServer>().start()
            assertEquals("false", System.getProperty(SHUTDOWN_HOOK))
        }
    }

    @Test fun `start keeps a shutdown hook property that is already set`() = withShutdownHookProperty("true") {
        serverTest(module { testServer() }) { app ->
            app.start()
            app.koin.get<KtorServer>().start()
            assertEquals("true", System.getProperty(SHUTDOWN_HOOK))
        }
    }
}
