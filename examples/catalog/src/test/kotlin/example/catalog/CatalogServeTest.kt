package example.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class CatalogServeTest {
    @TempDir lateinit var directory: File

    /** [text]를 담은 줄이 [count]번 나올 때까지 기다린다. 10초 안에 나오지 않으면 로그를 붙여 실패한다. */
    private suspend fun File.awaitLine(text: String, count: Int = 1) {
        withTimeoutOrNull(10.seconds) {
            while (!exists() || readLines().count { text in it } < count) delay(20.milliseconds)
        } ?: fail("'$text' x$count never appeared in:\n" + if (exists()) readText() else "<no log file>")
    }

    @Test fun `serve follows the source file and reports readiness as it changes`() = runBlocking {
        val products = File(directory, "products.txt").apply { writeText("Coffee") }
        val log = File(directory, "catalog.log")
        val config = File(directory, "application.toml").apply {
            writeText("[catalog]\nsource = '${products.path}'\nrefresh-interval = '20ms'\n\n[health]\ninterval = '20ms'\n\n[server]\nport = 0")
        }
        val serving = async(Dispatchers.Default) {
            catalog(arrayOf("--config", config.path, "--log", log.path, "serve"))
        }

        log.awaitLine("Readiness: healthy")
        products.writeText("Coffee\nTea")
        log.awaitLine("Catalog refreshed: 2 products")
        products.delete()
        log.awaitLine("Readiness: unhealthy (catalog: cannot read ${products.path})")
        products.writeText("Coffee")
        log.awaitLine("Readiness: healthy", count = 2)

        assertNotNull(withTimeoutOrNull(5.seconds) { serving.cancelAndJoin() }, "serve was still stopping after 5s")
        assertTrue(serving.isCancelled)
        val lines = log.readLines()
        assertTrue(lines.any { "stop requested: caller cancelled" in it }, log.readText())
        assertTrue(lines.any { "STOPPING -> STOPPED" in it }, log.readText())
        assertTrue(lines.none { "Worker CatalogRefresh failed" in it || "Worker HealthMonitor failed" in it }, log.readText())
        assertTrue(lines.none { "Workers still running" in it }, log.readText())
        assertTrue(lines.any { "Ktor server stopped" in it }, log.readText())
        val unreadable = lines.filter { "Catalog source unreadable" in it }
        assertEquals(1, unreadable.size, log.readText())
        assertContains(unreadable.single(), "source=${products.path}")
    }

    @Test fun `serve answers products and readiness over HTTP`() = runBlocking {
        val products = File(directory, "products.txt").apply { writeText("Coffee\nTea") }
        val log = File(directory, "catalog.log")
        val config = File(directory, "application.toml").apply {
            writeText("[catalog]\nsource = '${products.path}'\n\n[server]\nport = 0")
        }
        val serving = async(Dispatchers.Default) {
            catalog(arrayOf("--config", config.path, "--log", log.path, "serve"))
        }
        try {
            log.awaitLine("Ktor server started")
            val started = log.readLines().first { "Ktor server started" in it }
            val port = Regex("""connectors=http://127\.0\.0\.1:(\d+)""").find(started)?.groupValues?.get(1)?.toInt()
                ?: fail("no address in: $started")
            assertEquals("Coffee\nTea", URI("http://127.0.0.1:$port/products").toURL().readText())
            val ready = URI("http://127.0.0.1:$port/health/ready").toURL().readText().lines()
            assertEquals("ACTIVE healthy", ready.first())
            assertContains(ready, "catalog: healthy")
            log.awaitLine("Responding at http://127.0.0.1:$port")
            assertNotNull(withTimeoutOrNull(5.seconds) { serving.cancelAndJoin() }, "serve was still stopping after 5s")
        } finally {
            serving.cancel()
        }
        assertTrue(log.readLines().any { "Ktor server stopped" in it }, log.readText())
    }
}
