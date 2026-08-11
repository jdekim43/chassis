package kim.jade.chassis.config.document

import kim.jade.chassis.config.json.JsonConfigurationFormat
import kim.jade.chassis.config.json.JsonConfigurationReader
import java.io.File
import java.net.URL
import java.util.Collections
import java.util.Enumeration
import java.util.ServiceConfigurationError
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir

class ConfigurationReaderDiscoveryTest {
    @TempDir lateinit var directory: File

    @Test fun `omitted and null loader arguments discover context loader providers`() {
        val thread = Thread.currentThread()
        val previous = thread.contextClassLoader
        try {
            thread.contextClassLoader = loader(AliasedReader::class.java.name)
            assertEquals(setOf("yaml", "yml"), ConfigurationReader.loadAll().keys)
            assertEquals(setOf("yaml", "yml"), ConfigurationReader.loadAll(null).keys)
            assertTrue(ConfigurationReader.loadAll(loader()).isEmpty())
        } finally {
            thread.contextClassLoader = previous
        }
    }

    @Test fun `missing context loader falls back to the service loader`() {
        val thread = Thread.currentThread()
        val previous = thread.contextClassLoader
        try {
            thread.contextClassLoader = null
            assertIs<JsonConfigurationReader>(ConfigurationReader.loadAll()["json"])
        } finally {
            thread.contextClassLoader = previous
        }
    }

    @Test fun `service descriptor discovers providers and normalizes extension aliases`() {
        val readers = ConfigurationReader.loadAll(loader(AliasedReader::class.java.name))
        assertEquals(setOf("yaml", "yml"), readers.keys)
        assertIs<AliasedReader>(readers["yaml"])
        assertSame(readers["yaml"], readers["yml"])
    }

    @Test fun `different providers claiming the same extension fail in either order`() {
        val first = AliasedReader::class.java.name
        val second = ConflictingReader::class.java.name
        for (providers in listOf(arrayOf(first, second), arrayOf(second, first))) {
            val failure = assertFailsWith<IllegalStateException> {
                ConfigurationReader.loadAll(loader(*providers))
            }
            assertContains(failure.message.orEmpty(), "yaml")
            assertContains(failure.message.orEmpty(), first)
            assertContains(failure.message.orEmpty(), second)
        }
    }

    @Test fun `missing providers produce an empty index and invalid providers are not ignored`() {
        assertTrue(ConfigurationReader.loadAll(loader()).isEmpty())
        assertFailsWith<ServiceConfigurationError> {
            ConfigurationReader.loadAll(loader("missing.ConfigurationReader"))
        }
        for (provider in listOf(EmptyExtensions::class.java, InvalidExtension::class.java)) {
            assertFailsWith<IllegalArgumentException> { ConfigurationReader.loadAll(loader(provider.name)) }
        }
    }

    private fun loader(vararg providers: String): ClassLoader {
        val service = "META-INF/services/${ConfigurationReader::class.java.name}"
        val descriptor = File.createTempFile("readers-", ".txt", directory).apply {
            writeText(providers.joinToString("\n"))
        }.toURI().toURL()
        return object : ClassLoader(ConfigurationReader::class.java.classLoader) {
            override fun getResources(name: String): Enumeration<URL> =
                if (name == service) Collections.enumeration(listOf(descriptor)) else super.getResources(name)
        }
    }

    open class Reader : ConfigurationReader {
        override val extensions = emptySet<String>()
        override fun read(file: File): ConfigurationDocument = JsonConfigurationFormat.parse("{}")
    }
    class AliasedReader : Reader() { override val extensions = setOf("YAML", "yaml", "yml") }
    class ConflictingReader : Reader() { override val extensions = setOf("yaml") }
    class EmptyExtensions : Reader()
    class InvalidExtension : Reader() { override val extensions = setOf(".yaml") }
}
