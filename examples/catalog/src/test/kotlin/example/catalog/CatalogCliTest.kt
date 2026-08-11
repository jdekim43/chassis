package example.catalog

import kim.jade.chassis.cli.testing.CliResult
import kim.jade.chassis.cli.testing.captureCli
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CatalogCliTest {
    @TempDir lateinit var directory: File

    private fun file(name: String, text: String) = File(directory, name).apply { writeText(text) }
    private fun run(vararg arguments: String) = runBlocking { captureCli { catalog(arrayOf(*arguments)) } }
    private fun CliResult.lastError() = err.lines().last { it.isNotBlank() }

    @Test fun `list prints the product names from the source file`() {
        val products = file("products.txt", "Coffee\n\n  Tea  \n")
        val config = file("application.toml", "[catalog]\nsource = '${products.path}'")
        val result = run("--config", config.path, "list")
        assertEquals(0, result.code)
        assertEquals(listOf("Coffee", "Tea"), result.out.lines().filter { it.isNotEmpty() })
        assertEquals("", result.err)
    }

    @Test fun `a later config file overrides an earlier one`() {
        val first = file("first.txt", "Coffee")
        val second = file("second.txt", "Tea")
        val base = file("base.toml", "[catalog]\nsource = '${first.path}'")
        val override = file("override.toml", "[catalog]\nsource = '${second.path}'")
        val result = run("--config", base.path, "--config", override.path, "list")
        assertEquals(listOf("Tea"), result.out.lines().filter { it.isNotEmpty() })
    }

    @Test fun `starting without configuration names the missing key`() {
        val result = run("list")
        assertEquals(1, result.code)
        assertEquals(
            "Error: Configuration decoding failed (source=defaults, section=catalog.source): Required value is missing",
            result.lastError(),
        )
        assertEquals("", result.out)
    }

    @Test fun `a blank source is rejected`() {
        val config = file("application.toml", "[catalog]\nsource = ''")
        val result = run("--config", config.path, "list")
        assertEquals(1, result.code)
        assertContains(result.lastError(), "catalog.source=BLANK")
    }

    @Test fun `help describes both commands`() {
        val result = run("--help")
        assertEquals(0, result.code)
        val lines = result.out.lines()
        assertTrue(lines.any { "list" in it && "Print the product names" in it }, result.out)
        assertTrue(lines.any { "serve" in it && "Serve the catalog over HTTP" in it }, result.out)
    }

    @Test fun `a refresh interval that is not positive is rejected`() {
        val products = file("products.txt", "Coffee")
        val config = file("application.toml", "[catalog]\nsource = '${products.path}'\nrefresh-interval = '0s'")
        val result = run("--config", config.path, "list")
        assertEquals(1, result.code)
        assertContains(result.lastError(), "catalog.refresh-interval=NOT_POSITIVE")
    }

    @Test fun `a health interval that is not positive is rejected`() {
        val products = file("products.txt", "Coffee")
        val config = file("application.toml", "[catalog]\nsource = '${products.path}'\n\n[health]\ninterval = '-1s'")
        val result = run("--config", config.path, "list")
        assertEquals(1, result.code)
        assertContains(result.lastError(), "health.interval=NOT_POSITIVE")
    }

    @Test fun `list does not open the server port`() {
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { taken ->
            val products = file("products.txt", "Coffee")
            val config = file(
                "application.toml",
                "[catalog]\nsource = '${products.path}'\n\n[server]\nport = ${taken.localPort}",
            )
            val result = run("--config", config.path, "list")
            assertEquals(0, result.code)
            assertEquals(listOf("Coffee"), result.out.lines().filter { it.isNotEmpty() })
            assertEquals("", result.err)
        }
    }

    @Test fun `a server port out of range is rejected at startup`() {
        val products = file("products.txt", "Coffee")
        val config = file("application.toml", "[catalog]\nsource = '${products.path}'\n\n[server]\nport = 70000")
        val result = run("--config", config.path, "list")
        assertEquals(1, result.code)
        assertContains(result.lastError(), "server.port=OUT_OF_RANGE")
    }

    @Test fun `a source file that cannot be read fails the start`() {
        val missing = File(directory, "missing.txt")
        val config = file("application.toml", "[catalog]\nsource = '${missing.path}'")
        val result = run("--config", config.path, "list")
        assertEquals(1, result.code)
        assertTrue(result.lastError().startsWith("Error:"), result.err)
        assertContains(result.lastError(), "missing.txt")
        assertEquals("", result.out)
    }
}
