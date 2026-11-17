package kim.jade.chassis.config.toml

import kim.jade.chassis.config.ConfigurationException
import kim.jade.chassis.config.document.ConfigurationDocument
import kim.jade.chassis.config.document.ConfigurationReader
import kim.jade.chassis.config.document.decode
import java.io.*
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.junit.jupiter.api.io.TempDir

class TomlConfigurationTest {
    @TempDir lateinit var directory: File

    @Test fun `the reader is registered for its extension`() {
        assertIs<TomlConfigurationReader>(ConfigurationReader.loadAll()["toml"])
    }

    @Test fun `a document loaded from a file is labelled with the file's path`() {
        val file = File(directory, "app.toml").apply { writeText("") }
        assertEquals(file.path, ConfigurationDocument.load(file).source)
    }

    @Test fun `root schema supports nested configs lists defaults names and enums`() {
        val document = TomlConfiguration.parse("""
            # Service settings
            [worker]
            concurrency = 3
            queues = ["orders", "알림"]
            log-level = "DEBUG"
            [worker.pool]
            size = 8
        """.trimIndent())
        val root = document.decode<Root>()
        assertEquals(3, root.worker.concurrency)
        assertEquals(listOf("orders", "알림"), root.worker.queues)
        assertEquals(Level.DEBUG, root.worker.level)
        assertEquals(8, root.worker.pool.size)
        assertTrue(root.worker.enabled)
        assertEquals(root.worker, document.decode<Worker>("worker"))
    }

    @Test fun `section segments preserve literal dots in quoted keys`() {
        val doc = TomlConfiguration.parse("""["company.worker"]
            concurrency = 7
        """.trimIndent())
        assertEquals(7, doc.decode<Worker>("company.worker").concurrency)
        assertFailsWith<ConfigurationException.DecodeFailed> { doc.decode<Worker>("company", "worker") }
    }

    @Test fun `absent sections decode constructor defaults through every entry point`() {
        for (text in listOf("", "[company]", "[company.worker]")) {
            val document = TomlConfiguration.parse(text)
            assertEquals(Defaults(), document.decode<Defaults>("company", "worker"))
            assertEquals(Defaults(), document.decode<Defaults>(listOf("company", "worker")))
            assertEquals(Defaults(), document.decode(Defaults.serializer(), listOf("company", "worker")))
        }
        assertEquals(Defaults(), TomlConfiguration.parse("").decode<Defaults>())
        assertEquals(Defaults(), TomlConfiguration.parse("[unrelated]\nvalue = 1").decode<Defaults>("worker"))
    }

    @Test fun `present fields override defaults without discarding other constructor defaults`() {
        val doc = TomlConfiguration.parse("[worker]\nconcurrency = 9")
        assertEquals(Defaults(concurrency = 9), doc.decode<Defaults>("worker"))
        assertEquals(Defaults(), doc.decode<Defaults>("company.worker"))
    }

    @Test fun `absent sections still reject required fields and required nested objects`() {
        for (text in listOf("", "[company]", "[company.worker]")) {
            val doc = TomlConfiguration.parse(text, "test-source")
            val failure = assertFailsWith<ConfigurationException.DecodeFailed> { doc.decode<Worker>("company", "worker") }
            assertEquals(listOf("company", "worker", "concurrency"), failure.path)
            assertEquals("test-source", failure.source)
            assertNotNull(failure.cause)
            assertFalse(failure.cause is ConfigurationException)
            val nested = assertFailsWith<ConfigurationException.DecodeFailed> { doc.decode<RequiredNested>("company", "worker") }
            assertEquals(listOf("company", "worker", "pool"), nested.path)
        }
    }

    @Test fun `invalid ancestors are decode errors rather than absent sections`() {
        for ((text, path) in listOf(
            "company = 42" to listOf("company"),
            "company = false" to listOf("company"),
            "company = \"text\"" to listOf("company"),
            "company = []" to listOf("company"),
            "[[company]]" to listOf("company"),
            "[company]\nworker = 42" to listOf("company", "worker"),
            "[company]\nworker = []" to listOf("company", "worker"),
        )) {
            val failure = assertFailsWith<ConfigurationException.DecodeFailed>(text) {
                TomlConfiguration.parse(text, "test-source").decode<Defaults>("company", "worker")
            }
            assertEquals(path, failure.path, text)
            assertEquals("test-source", failure.source)
        }
    }

    @Test fun `defaults do not conceal invalid values unknown fields or constructor validation`() {
        for (text in listOf(
            "concurrency = \"4\"", "concurrency = false", "concurrency = 0",
            "unknown = 1", "pool = 42", "pool = { size = \"bad\" }",
        )) {
            assertFailsWith<ConfigurationException.DecodeFailed>(text) {
                TomlConfiguration.parse("[worker]\n$text").decode<Defaults>("worker")
            }
        }
    }

    @Test fun `absent section is passed to the serializer without swallowing cancellation`() {
        val cancelled = CancellationException("default decode cancelled")
        val serializer = object : KSerializer<Defaults> {
            override val descriptor = Defaults.serializer().descriptor
            override fun deserialize(decoder: Decoder): Defaults = throw cancelled
            override fun serialize(encoder: Encoder, value: Defaults) = error("not used")
        }
        assertSame(cancelled, assertFailsWith<CancellationException> {
            TomlConfiguration.parse("").decode(serializer, listOf("worker"))
        })
    }

    @Test fun `unknown keys missing required fields and scalar sections fail decoding`() {
        for (text in listOf("concurency = 4", "[pool]\nsize = 3", "concurrency = 4\nextra = 8")) {
            assertFailsWith<ConfigurationException.DecodeFailed> { TomlConfiguration.parse(text).decode<Worker>() }
        }
        assertFailsWith<ConfigurationException.DecodeFailed> { TomlConfiguration.parse("worker = 42").decode<Worker>("worker") }
    }

    @Test fun `quoted numeric and boolean values and numeric strings are not coerced`() {
        for (text in listOf(
            "concurrency = \"4\"",
            "concurrency = 4\nenabled = \"true\"",
            "concurrency = 4\nqueues = [42]",
            "concurrency = 4.0",
            "concurrency = 2147483648",
            "concurrency = 4\n[pool]\nsize = \"2\"",
        )) {
            assertFailsWith<ConfigurationException.DecodeFailed>(text) { TomlConfiguration.parse(text).decode<Worker>() }
        }
    }

    @Test fun `syntax duplicate keys and null extension are rejected without echoing values`() {
        for (text in listOf("secret = [", "secret = 1\nsecret = 2", "secret = null")) {
            val failure = assertFailsWith<ConfigurationException.ParseFailed> { TomlConfiguration.parse(text, "test") }
            assertFalse(failure.message.orEmpty().contains("secret"))
            assertNotNull(failure.cause)
        }
    }

    @Test fun `file stream and reader loaders support UTF8 and caller ownership`() {
        val text = "concurrency = 1\nqueues = [\"카메라\"]"
        val file = File(directory, "application.toml").apply { writeText(text, Charsets.UTF_8) }
        assertEquals(listOf("카메라"), TomlConfiguration.read(file).decode<Worker>().queues)
        var streamClosed = false
        val stream = object : ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)) {
            override fun close() { streamClosed = true; super.close() }
        }
        assertEquals(1, TomlConfiguration.read(stream).decode<Worker>().concurrency)
        assertFalse(streamClosed)
        stream.close()
        var readerClosed = false
        val reader = object : StringReader(text) {
            override fun close() { readerClosed = true; super.close() }
        }
        assertEquals(1, TomlConfiguration.read(reader).decode<Worker>().concurrency)
        assertFalse(readerClosed)
        reader.close()
    }

    @Test fun `read failures have stable codes and safe source labels`() {
        val failure = assertFailsWith<ConfigurationException.ReadFailed> {
            TomlConfiguration.read(File(directory, "secret-file.toml"), "external-config")
        }
        assertEquals("external-config", failure.source)
        assertIs<FileNotFoundException>(failure.cause)
        assertFalse("secret-file" in failure.message.orEmpty())
    }

    @Test fun `custom serializer cancellation propagates unchanged`() {
        val cancelled = CancellationException("cancel decode")
        val serializer = object : KSerializer<Int> {
            override val descriptor = PrimitiveSerialDescriptor("Cancelled", PrimitiveKind.INT)
            override fun deserialize(decoder: Decoder): Int = throw cancelled
            override fun serialize(encoder: Encoder, value: Int) = encoder.encodeInt(value)
        }
        assertSame(cancelled, assertFailsWith<CancellationException> {
            TomlConfiguration.parse("value = 1").decode(serializer, listOf("value"))
        })
    }

    @Test fun `unrelated sections are allowed only when decoding a selected section`() {
        val doc = TomlConfiguration.parse("[worker]\nconcurrency = 1\n[unrelated]\nvalue = true")
        assertEquals(1, doc.decode<Worker>("worker").concurrency)
        assertFailsWith<ConfigurationException> { doc.decode<Root>() }
    }

    @Test fun `file parsing failures retain their type and are not wrapped as read failures`() {
        val file = File(directory, "invalid.toml").apply { writeText("value = [") }
        val failure = assertFailsWith<ConfigurationException.ParseFailed> {
            TomlConfiguration.read(file, "config-file")
        }
        assertEquals("config-file", failure.source)
        assertNotNull(failure.cause)
        assertFalse(failure.cause is ConfigurationException)
    }

    @Test fun `typed document decode does not wrap an existing configuration failure twice`() {
        val document: ConfigurationDocument = TomlConfiguration.parse("[worker]\nconcurrency = 'invalid'")
        val failure = assertFailsWith<ConfigurationException.DecodeFailed> {
            document.decode<Worker>("worker")
        }
        assertEquals(listOf("worker", "concurrency"), failure.path)
        assertNotNull(failure.cause)
        assertFalse(failure.cause is ConfigurationException)
    }

    @Test fun `arrays of tables maps and inline values follow the declared schema`() {
        val doc = TomlConfiguration.parse("""
            [[endpoints]]
            name = "first"
            [[endpoints]]
            name = "second"
            [limits]
            requests = 3
        """.trimIndent())
        val value = doc.decode<Complex>()
        assertEquals(listOf(Endpoint("first"), Endpoint("second")), value.endpoints)
        assertEquals(Count(3), value.limits["requests"])
        assertFailsWith<ConfigurationException> {
            TomlConfiguration.parse("[limits]\nrequests = \"3\"").decode<Complex>()
        }
    }

    @Test fun `invalid UTF8 is rejected and reader cancellation remains cancellation`() {
        assertFailsWith<ConfigurationException.ReadFailed> {
            TomlConfiguration.read(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28)))
        }
        val cancelled = CancellationException("cancel read")
        val reader = object : Reader() {
            override fun read(buffer: CharArray, offset: Int, length: Int): Int = throw cancelled
            override fun close() = Unit
        }
        assertSame(cancelled, assertFailsWith<CancellationException> { TomlConfiguration.read(reader) })
    }

    @Test fun `a TOML integer reads as a double and a date literal reads as a string`() {
        assertEquals(
            Stamp(2.0, "1979-05-27", "1979-05-27T07:32:00"),
            TomlConfiguration.parse("ratio = 2\nday = 1979-05-27\nat = 1979-05-27 07:32:00").decode<Stamp>(),
        )
    }

    @Serializable data class Root(val worker: Worker)
    @Serializable data class Worker(
        val concurrency: Int,
        val queues: List<String> = emptyList(),
        val enabled: Boolean = true,
        @SerialName("log-level") val level: Level = Level.INFO,
        val pool: Pool = Pool(),
    )
    @Serializable data class Stamp(val ratio: Double, val day: String, val at: String)
    @Serializable data class Pool(val size: Int = 2, val enabled: Boolean = true)
    @Serializable data class Defaults(val concurrency: Int = 4, val pool: Pool = Pool(8)) {
        init { require(concurrency > 0) }
    }
    @Serializable data class RequiredNested(val pool: Pool)
    @Serializable enum class Level { DEBUG, INFO }
    @Serializable data class Complex(val endpoints: List<Endpoint> = emptyList(), val limits: Map<String, Count>)
    @Serializable data class Endpoint(val name: String)
    @Serializable @JvmInline value class Count(val value: Int)
}
