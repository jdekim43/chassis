package kim.jade.chassis.ktor

import io.ktor.server.cio.CIO
import io.ktor.server.engine.connector
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kim.jade.chassis.Application
import kim.jade.kotlinx.logger.LogRecord
import kim.jade.kotlinx.logger.context.CoroutineThreadLogContext
import kim.jade.kotlinx.logger.context.LogContext
import kim.jade.kotlinx.logger.context.ThreadLogContext
import kim.jade.kotlinx.logger.context.withLogContext
import kim.jade.kotlinx.logger.pipeline.SinkPipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.koin.dsl.module
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.seconds
import kim.jade.kotlinx.logger.Logger as KotlinxLogger

class KtorLoggingTest {
    @Test fun `HTTP requests inherit server context without automatic request fields`() {
        runBlocking {
            val records = CopyOnWriteArrayList<LogRecord>()
            val pipeline = KotlinxLogger.defaultPipeline
            val log = LoggerFactory.getLogger("test.ktor.context")
            val serverLocal = ThreadLocal<String>()
            val serverContext = serverLocal.asContextElement("catalog-http")
            val routes = module {
                ktorServer(CIO, coroutineContext = serverContext) { engine ->
                    engine.connector {
                        host = "127.0.0.1"
                        port = 0
                    }
                    engine.shutdownGracePeriod = 50
                    log.info("configure server")
                }
                ktor { server ->
                    assertSame(serverContext, server.coroutineContext[serverContext.key])
                    assertNotNull(server.coroutineContext[CoroutineThreadLogContext])
                    server.routing {
                        get("/") {
                            withContext(Dispatchers.IO) {
                                assertEquals("catalog-http", serverLocal.get())
                                log.info("request")
                                KotlinxLogger.named("test.ktor.context").info {
                                    withCoroutine()
                                    "coroutine request"
                                }
                            }
                            call.respondText("ok")
                        }
                    }
                }
            }
            val app = Application(
                listOf(routes),
                name = "catalog",
                logContext = LogContext("env" to "test"),
            )
            pipeline.install(SinkPipe { records += it }, 0)
            try {
                withTimeout(15.seconds) {
                    app.start()
                    withLogContext("caller" to "manual start") {
                        ThreadLogContext["startThread"] = "manual start"
                        app.koin.get<KtorServer>().start()
                    }
                    val port = app.serverPort()
                    val response = withContext(Dispatchers.IO) { fetch(port, "/") }
                    assertEquals(200, response.status)
                    assertEquals("ok", response.body)
                }
            } finally {
                withContext(NonCancellable) {
                    try {
                        app.stop()
                    } finally {
                        pipeline.uninstall(SinkPipe)
                    }
                }
            }

            val configured = records.single { it.body == "configure server" }.context
            assertEquals("test", configured["env"])
            assertEquals("ktor", configured["component"])
            for (message in listOf("request", "coroutine request")) {
                val context = records.single { it.body == message }.context
                assertEquals("catalog", context["application"])
                assertEquals("test", context["env"])
                assertEquals("ktor", context["component"])
                assertNull(context["requestId"])
                assertNull(context["method"])
                assertNull(context["path"])
                assertNull(context["hook"])
                assertNull(context["caller"])
                assertNull(context["startThread"])
            }
            val shutdown = records.single { it.body == "Ktor server stopped" }.context
            assertEquals("test", shutdown["env"])
        }
    }
}
