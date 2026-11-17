package example.catalog

import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CatalogProcessTest {
    @TempDir lateinit var directory: File

    @Test fun `SIGTERM ends serve after its shutdown`() {
        val products = File(directory, "products.txt").apply { writeText("Coffee") }
        val log = File(directory, "catalog.log")
        val error = File(directory, "catalog.error")
        val config = File(directory, "application.toml").apply {
            writeText("[catalog]\nsource = '${products.path}'\nrefresh-interval = '20ms'\n\n[health]\ninterval = '20ms'\n\n[server]\nport = 0")
        }
        val output = File(directory, "catalog.output")
        val process = ProcessBuilder(
            File(System.getProperty("java.home"), "bin/java").path,
            "-cp", System.getProperty("catalog.classpath"),
            "example.catalog.MainKt", "--config", config.path, "--log", log.path, "serve",
        ).redirectOutput(output).redirectError(error).start()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!output.exists() || output.readLines().none { "Serving on" in it }) {
                check(process.isAlive) { error.readText() }
                check(System.nanoTime() < deadline) { "serve never became ready" }
                Thread.sleep(10)
            }
            process.destroy()
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "serve was still running 5s after SIGTERM:\n" + log.readText())
            assertEquals(143, process.exitValue())
            val lines = log.readLines()
            assertTrue(lines.any { "stop requested: JVM shutdown" in it }, log.readText())
            assertTrue(lines.any { "STOPPING -> STOPPED" in it }, log.readText())
            assertTrue(lines.any { "Ktor server stopped" in it }, log.readText())
            val first = output.readLines().firstOrNull().orEmpty()
            assertTrue(Regex("""Serving on http://127\.0\.0\.1:\d+\. Press Ctrl\+C to stop\.""").matches(first), first)
        } finally {
            if (process.isAlive) process.destroyForcibly().waitFor()
        }
    }
}
