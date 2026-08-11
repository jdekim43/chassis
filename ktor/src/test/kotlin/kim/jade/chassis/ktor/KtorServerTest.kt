package kim.jade.chassis.ktor

import io.ktor.server.cio.CIO
import io.ktor.server.engine.connector
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kim.jade.chassis.ApplicationStatus
import kim.jade.chassis.hook.onRelease
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import org.koin.dsl.module
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class KtorServerTest {
    private class Greeter {
        fun greet() = "hello"
    }

    private class Settings(val host: String)

    @Test fun `start opens the port and a block answers with an object from Koin`() = serverTest(module {
        single { Greeter() }
        testServer()
        ktor { server ->
            val greeter = get<Greeter>()
            server.routing { get("/greet") { call.respondText(greeter.greet()) } }
        }
    }) { app ->
        app.start()
        app.koin.get<KtorServer>().start()
        val response = fetch(app.serverPort(), "/greet")
        assertEquals(200, response.status)
        assertEquals("hello", response.body)
    }

    @Test fun `without start no block runs and the port is left alone`() = withPortInUse { port ->
        var ran = false
        serverTest(module {
            testServer(port)
            ktor { ran = true }
        }) { app ->
            app.start()
            assertFalse(ran)
            val failure = assertFailsWith<IllegalStateException> { app.koin.get<KtorServer>().connectors() }
            assertEquals("Ktor server is not running", failure.message)
            app.stop()
            assertEquals(ApplicationStatus.STOPPED, app.status)
        }
    }

    @Test fun `blocks of one module run in declaration order`() {
        val order = CopyOnWriteArrayList<String>()
        serverTest(module {
            testServer()
            ktor { order += "first" }
            ktor { order += "second" }
            ktor { order += "third" }
        }) { app ->
            app.start()
            app.koin.get<KtorServer>().start()
            assertEquals(listOf("first", "second", "third"), order.toList())
        }
    }

    @Test fun `configure reads objects from Koin`() {
        val settings = Settings("127.0.0.1")
        var seen: Settings? = null
        serverTest(module {
            single { settings }
            ktorServer(CIO) { engine ->
                val found = get<Settings>()
                seen = found
                engine.connector {
                    host = found.host
                    port = 0
                }
                engine.shutdownGracePeriod = 50
            }
            ktor { it.routing { get("/") { call.respondText("ok") } } }
        }) { app ->
            app.start()
            app.koin.get<KtorServer>().start()
            assertSame(settings, seen)
            assertEquals(200, fetch(app.serverPort(), "/").status)
        }
    }

    @Test fun `addresses lists where the server listens`() = serverTest(module { testServer() }) { app ->
        app.start()
        val server = app.koin.get<KtorServer>()
        assertEquals("Ktor server is not running", assertFailsWith<IllegalStateException> { server.addresses() }.message)
        server.start()
        assertEquals(listOf("http://127.0.0.1:${app.serverPort()}"), server.addresses())
    }

    @Test fun `stopping the application closes the port`() = serverTest(module { testServer() }) { app ->
        app.start()
        app.koin.get<KtorServer>().start()
        val port = app.serverPort()
        assertTrue(isListening(port))
        app.stop()
        assertFalse(isListening(port))
        assertEquals(ApplicationStatus.STOPPED, app.status)
    }

    @Test fun `a request in flight is answered before Release hooks run`() {
        val events = CopyOnWriteArrayList<String>()
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        serverTest(module {
            testServer(graceMillis = 10_000)
            ktor {
                it.routing {
                    get("/slow") {
                        entered.complete(Unit)
                        gate.await()
                        call.respondText("done")
                        events += "responded"
                    }
                }
            }
            onRelease { events += "released" }
        }) { app ->
            app.start()
            app.koin.get<KtorServer>().start()
            val port = app.serverPort()
            val response = async(Dispatchers.IO) { fetch(port, "/slow") }
            entered.await()
            app.requestStop()
            delay(200.milliseconds)
            assertEquals(emptyList<String>(), events.toList())
            gate.complete(Unit)
            assertEquals("done", response.await().body)
            app.awaitTermination()
            assertEquals(listOf("responded", "released"), events.toList())
        }
    }
}
