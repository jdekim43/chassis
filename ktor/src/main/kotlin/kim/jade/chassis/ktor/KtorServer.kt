package kim.jade.chassis.ktor

import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.EngineConnectorConfig
import kim.jade.chassis.Application
import kim.jade.chassis.ApplicationStatus
import kim.jade.kotlinx.logger.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import io.ktor.server.application.Application as KtorApplication

private const val SHUTDOWN_HOOK_PROPERTY = "io.ktor.server.engine.ShutdownHook"

private fun EngineConnectorConfig.address(): String = "${type.name.lowercase()}://$host:$port"

class KtorServer internal constructor(
    private val application: Application,
    private val create: (blocks: suspend KtorApplication.() -> Unit) -> EmbeddedServer<*, *>,
) {
    private enum class State { NEW, STARTED, STOPPED }

    private val logger = Logger.named("kim.jade.chassis.ktor")
    private val mutex = Mutex()
    private var state = State.NEW
    @Volatile private var running: EmbeddedServer<*, *>? = null

    @Volatile private var armed = false

    internal fun arm() {
        armed = true
    }

    suspend fun start() = mutex.withLock {
        val status = application.status
        check(armed) { "Ktor server cannot start before the application starts" }
        check(state != State.STARTED) { "Ktor server is already started" }
        check(state == State.NEW && status != ApplicationStatus.STOPPED && status != ApplicationStatus.FAILED) {
            "Ktor server is already stopped"
        }
        //실패해도 다시 시작은 불가하므로 open 전에 먼저 stopped 로 변경하여 중복 시작과 동시에 방어
        state = State.STOPPED
        val server = open()
        running = server
        state = State.STARTED
        logger.info("Ktor server started", meta = mapOf("connectors" to addresses().joinToString()))
    }

    private suspend fun open(): EmbeddedServer<*, *> {
        if (System.getProperty(SHUTDOWN_HOOK_PROPERTY) == null) {
            System.setProperty(SHUTDOWN_HOOK_PROPERTY, "false")
        }

        var loaded = false
        val server = create {
            application.ktorBlocks.all.forEach { it.apply(this) }
            loaded = true
        }
        check(server.engineConfig.connectors.isNotEmpty()) { "Ktor server has no connector" }
        try {
            server.startSuspend(wait = false)
        } catch (e: Throwable) {
            if (!loaded) {
                throw e
            }

            val failure = engineFailure(server, e)
            withContext(NonCancellable) {
                try {
                    server.stopSuspend()
                } catch (closing: Exception) {
                    failure.addSuppressed(closing)
                }
            }
            throw failure
        }
        return server
    }

    private suspend fun engineFailure(server: EmbeddedServer<*, *>, thrown: Throwable): Throwable {
        if (thrown is CancellationException && !currentCoroutineContext().isActive) {
            return thrown
        }

        val cause = generateSequence(thrown) { it.cause }.firstOrNull { it !is CancellationException } ?: thrown
        val addresses = server.engineConfig.connectors.joinToString { it.address() }
        val message = listOfNotNull("Ktor server failed to start on $addresses", cause.message).joinToString(": ")
        return IllegalStateException(message, cause)
    }

    suspend fun connectors(): List<EngineConnectorConfig> {
        val server = running ?: error("Ktor server is not running")
        return server.engine.resolvedConnectors()
    }

    suspend fun addresses(): List<String> = connectors().map { it.address() }

    internal suspend fun stop() = mutex.withLock {
        state = State.STOPPED
        val server = running ?: return@withLock
        running = null
        server.stopSuspend()
        logger.info("Ktor server stopped")
    }
}
