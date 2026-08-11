package kim.jade.chassis.config.json

import kim.jade.chassis.config.Configuration
import kim.jade.chassis.config.ConfigurationException
import kim.jade.chassis.config.ConfigurationIssue
import kim.jade.chassis.config.ConfigurationResolver
import kim.jade.chassis.config.ValidatableConfiguration
import kim.jade.chassis.config.document.ConfigurationDocument
import kim.jade.chassis.config.document.ConfigurationReader
import kim.jade.chassis.config.document.decode
import kim.jade.chassis.config.resolve
import java.io.*
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.junit.jupiter.api.io.TempDir

class JsonConfigurationFormatTest {
    @TempDir lateinit var directory: File

    @Test fun `the reader is registered for its extension`() {
        assertIs<JsonConfigurationReader>(ConfigurationReader.loadAll()["json"])
    }

    @Test fun `a document loaded from a file is labelled with the file's path`() {
        val file = File(directory, "app.json").apply { writeText("{}") }
        assertEquals(file.path, ConfigurationDocument.load(file).source)
    }

    @Test fun `sections defaults nested values and nullable values decode through the resolver`() {
        val document = JsonConfigurationFormat.parse("""{"company":{"worker":{"count":2,"names":["a"],"pool":{"size":3}}}}""")
        val value = ConfigurationResolver().apply { add(document) }.resolve<Settings>()
        assertEquals(2, value.count)
        assertEquals(listOf("a"), value.names)
        assertEquals(Pool(3, true), value.pool)
        assertEquals(Settings(), ConfigurationResolver().apply { add(JsonConfigurationFormat.parse("""{}""")) }.resolve<Settings>())
        assertFailsWith<ConfigurationException.DecodeFailed> {
            JsonConfigurationFormat.parse("""{}""").decode<Required>("absent")
        }
    }

    @Test fun `wrong scalar types and invalid objects remain errors`() {
        assertFailsWith<ConfigurationException.DecodeFailed> {
            ConfigurationResolver().apply { add(JsonConfigurationFormat.parse("""{"company":{"worker":{"count":"2"}}}""")) }.resolve<Settings>()
        }
        val negative = JsonConfigurationFormat.parse("""{"company":{"worker":{"count":0,"names":["a"],"pool":{"size":3}}}}""")
        val failure = assertFailsWith<ConfigurationException.ValidationFailed> { ConfigurationResolver().apply { add(negative) }.resolve<Settings>() }
        assertEquals(listOf(ConfigurationIssue("company.worker.count", "MUST_BE_POSITIVE")), failure.issues)
    }

    @Test fun `reader loads files by extension and a later file wins key by key`() {
        val baseFile = File(directory, "base.json").apply { writeText("""{"company":{"worker":{"count":2,"names":["a"],"pool":{"size":3}}}}""") }
        val overrideFile = File(directory, "override.json").apply { writeText("""{"company":{"worker":{"names":["b"],"label":null,"pool":{"enabled":false}}}}""") }
        val base = JsonConfigurationReader().read(baseFile)
        val resolver = ConfigurationResolver().apply {
            add(base)
            add(JsonConfigurationReader().read(overrideFile))
        }
        val value = resolver.resolve<Settings>()
        assertEquals(2, value.count)
        assertEquals(listOf("b"), value.names)
        assertEquals(Pool(3, false), value.pool)
        assertEquals("default", value.label)
        assertEquals(listOf("a"), ConfigurationResolver().apply { add(base) }.resolve<Settings>().names)

    }

    @Test fun `IO errors parsing errors and caller resource ownership are preserved`() {
        val missing = assertFailsWith<ConfigurationException.ReadFailed> {
            JsonConfigurationReader().read(File(directory, "missing.json"))
        }
        assertIs<FileNotFoundException>(missing.cause)
        val invalid = File(directory, "invalid.json").apply { writeText("{broken") }
        assertFailsWith<ConfigurationException.ParseFailed> { JsonConfigurationReader().read(invalid) }
        assertFailsWith<ConfigurationException.ReadFailed> {
            JsonConfigurationFormat.read(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28)))
        }
        var closed = false
        val reader = object : StringReader("""{"company":{"worker":{"count":2,"names":["a"],"pool":{"size":3}}}}""") { override fun close() { closed = true; super.close() } }
        JsonConfigurationFormat.read(reader)
        assertFalse(closed)
        reader.close()
        val cancelled = CancellationException("cancelled read")
        val failing = object : Reader() {
            override fun read(buffer: CharArray, offset: Int, length: Int): Int = throw cancelled
            override fun close() = Unit
        }
        assertSame(cancelled, assertFailsWith<CancellationException> { JsonConfigurationFormat.read(failing) })
    }

    @Test fun `serializer cancellation is propagated unchanged`() {
        val cancelled = CancellationException("cancelled decode")
        val serializer = object : KSerializer<Settings> {
            override val descriptor = Settings.serializer().descriptor
            override fun deserialize(decoder: Decoder): Settings = throw cancelled
            override fun serialize(encoder: Encoder, value: Settings) = error("not used")
        }
        assertSame(cancelled, assertFailsWith<CancellationException> {
            JsonConfigurationFormat.parse("""{}""").decode(serializer, listOf("missing"))
        })
    }

    @Configuration("company", "worker")
    data class Settings(
        val count: Int = 4,
        val names: List<String> = emptyList(),
        val label: String? = "default",
        val pool: Pool = Pool(),
    ) : ValidatableConfiguration {
        override fun validate() = if (count > 0) emptyList() else listOf(ConfigurationIssue("count", "MUST_BE_POSITIVE"))
    }
    @Serializable data class Pool(val size: Int = 1, val enabled: Boolean = true)
    @Serializable data class Required(val value: String)
}
