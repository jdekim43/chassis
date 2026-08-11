package kim.jade.chassis.ktor

import io.ktor.server.cio.CIO
import io.ktor.server.engine.connector
import kim.jade.chassis.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.koin.core.module.Module
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

internal class Response(val status: Int, val contentType: String, val body: String)

// keep-alive 연결이 남으면 CIO가 닫을 때 shutdownGracePeriod를 다 기다리므로 요청마다 연결을 닫는다.
internal fun fetch(port: Int, path: String): Response {
    val connection = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
    connection.setRequestProperty("Connection", "close")
    connection.connectTimeout = 2_000
    connection.readTimeout = 10_000
    val status = connection.responseCode
    val stream = if (status < 400) connection.inputStream else connection.errorStream
    return Response(status, connection.contentType.orEmpty(), stream?.use { it.readBytes().decodeToString() }.orEmpty())
}

internal fun Module.testServer(
    port: Int = 0,
    graceMillis: Long = 50,
    autoStart: Boolean = false,
) = ktorServer(CIO, autoStart) { engine ->
    engine.connector {
        host = "127.0.0.1"
        this.port = port
    }
    engine.shutdownGracePeriod = graceMillis
    engine.shutdownTimeout = graceMillis + 2_000
}

internal fun serverTest(vararg modules: Module, block: suspend CoroutineScope.(Application) -> Unit) = runBlocking {
    withTimeout(30.seconds) {
        val app = Application(modules.toList())
        try {
            block(app)
        } finally {
            withContext(NonCancellable) { runCatching { app.stop() } }
        }
    }
}

internal suspend fun Application.serverPort(): Int = koin.get<KtorServer>().connectors().single().port

// 놓아준 포트를 다른 프로세스가 먼저 잡으면 헛실패한다. 서버가 열었다 닫는 것을 봐야 하는 테스트에만 쓴다.
internal fun freePort(): Int = ServerSocket(0).use { it.localPort }

internal fun <T> withPortInUse(block: (port: Int) -> T): T =
    ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { block(it.localPort) }

internal fun <T> recordingUncaught(block: (uncaught: List<Throwable>) -> T): T {
    val uncaught = CopyOnWriteArrayList<Throwable>()
    val before = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { _, failure -> uncaught += failure }
    try {
        return block(uncaught)
    } finally {
        Thread.setDefaultUncaughtExceptionHandler(before)
    }
}

internal fun isListening(port: Int): Boolean =
    try {
        Socket("127.0.0.1", port).close()
        true
    } catch (_: IOException) {
        false
    }

internal fun Throwable.causes(): Sequence<Throwable> = generateSequence(this) { it.cause }
