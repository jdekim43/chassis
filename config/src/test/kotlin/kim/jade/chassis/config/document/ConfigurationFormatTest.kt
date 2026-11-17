package kim.jade.chassis.config.document

import kim.jade.chassis.config.ConfigurationException
import java.io.ByteArrayInputStream
import java.io.File
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.io.TempDir

class ConfigurationFormatTest {
    @TempDir lateinit var directory: File

    private val broken = IllegalArgumentException("broken")
    private val failing = object : ConfigurationFormat() {
        override fun parseTree(text: String): JsonObject = throw broken
    }

    private val cancelled = CancellationException("stop")
    private val cancelling = object : ConfigurationFormat() {
        override fun parseTree(text: String): JsonObject = throw cancelled
    }

    @Test fun `an exception from the tree is a parse failure with the given source however the text is read`() {
        val file = File(directory, "app.conf").apply { writeText("x") }
        for ((source, read) in listOf<Pair<String, () -> ConfigurationDocument>>(
            "string" to { failing.parse("x", "string") },
            "reader" to { failing.read(StringReader("x"), "reader") },
            "stream" to { failing.read(ByteArrayInputStream("x".toByteArray()), "stream") },
            "file" to { failing.read(file, "file") },
        )) {
            val failure = assertFailsWith<ConfigurationException.ParseFailed>(source) { read() }
            assertEquals(source, failure.source)
            assertSame(broken, failure.cause)
        }
    }

    @Test fun `cancellation from the tree comes out untouched`() {
        assertSame(cancelled, assertFailsWith<CancellationException> { cancelling.parse("x") })
        assertSame(cancelled, assertFailsWith<CancellationException> { cancelling.read(StringReader("x")) })
    }
}
