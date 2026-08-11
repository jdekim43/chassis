package kim.jade.chassis.config

import kim.jade.chassis.Application
import kim.jade.chassis.ApplicationStatus
import kim.jade.chassis.config.json.JsonConfigurationFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

@OptIn(ExperimentalCoroutinesApi::class)
class ConfigurationTest {
    @Configuration("catalog")
    data class CatalogConfig(val name: String = "default", val limit: Int = 10) : ValidatableConfiguration {
        override fun validate() = if (limit < 0) listOf(ConfigurationIssue("limit", "NEGATIVE")) else emptyList()
    }
    @Configuration("required")
    data class RequiredConfig(val url: String)
    data class Unannotated(val name: String = "x")

    private fun TestScope.app(vararg modules: Module) =
        Application(modules.toList(), context = StandardTestDispatcher(testScheduler))
    private fun catalog() = module { configuration<CatalogConfig>() }

    @Test fun `a configuration object is resolved from a document added to the application`() = runTest {
        val app = app(catalog())
        app.configurations.add(JsonConfigurationFormat.parse("""{"catalog":{"name":"books"}}"""))
        app.start()
        assertEquals(CatalogConfig("books", 10), app.koin.get<CatalogConfig>())
        app.stop()
    }

    @Test fun `an application has one resolver of its own`() = runTest {
        val first = app()
        val second = app()
        assertSame(first.configurations, first.configurations)
        assertNotSame(first.configurations, second.configurations)
        first.stop()
        second.stop()
    }

    @Test fun `the first reads from many threads see one resolver`() = runTest {
        val application = app()
        val start = CountDownLatch(1)
        val seen = ConcurrentHashMap.newKeySet<ConfigurationResolver>()
        val threads = List(16) { thread { start.await(); seen += application.configurations } }
        start.countDown()
        threads.forEach { it.join() }
        assertEquals(1, seen.size)
        application.stop()
    }

    @Test fun `documents added before the start are layered`() = runTest {
        val application = app(catalog())
        application.configurations.add(JsonConfigurationFormat.parse("""{"catalog":{"name":"base","limit":1}}""", "base"))
        application.configurations.add(JsonConfigurationFormat.parse("""{"catalog":{"name":"local"}}""", "local"))
        application.start()
        assertEquals(CatalogConfig("local", 1), application.koin.get<CatalogConfig>())
        application.stop()
    }

    @Test fun `without a document the defaults are used`() = runTest {
        val app = app(catalog())
        app.start()
        assertEquals(CatalogConfig(), app.koin.get<CatalogConfig>())
        app.stop()
    }

    @Test fun `load replaces the document`() = runTest {
        val app = app(module { configuration<CatalogConfig> { CatalogConfig("coded", 1) } })
        app.configurations.add(JsonConfigurationFormat.parse("""{"catalog":{"name":"books"}}"""))
        app.start()
        assertEquals(CatalogConfig("coded", 1), app.koin.get<CatalogConfig>())
        app.stop()
    }

    @Test fun `registrations of one class under different qualifiers resolve separately`() = runTest {
        val app = app(module {
            configuration<CatalogConfig>(named("books")) { CatalogConfig("books", 1) }
            configuration<CatalogConfig>(named("music")) { CatalogConfig("music", 2) }
        })
        app.start()
        assertEquals(CatalogConfig("books", 1), app.koin.get<CatalogConfig>(named("books")))
        assertEquals(CatalogConfig("music", 2), app.koin.get<CatalogConfig>(named("music")))
        app.stop()
    }

    @Test fun `a second application does not see the first one's document`() = runTest {
        val module = catalog()
        val first = app(module)
        first.configurations.add(JsonConfigurationFormat.parse("""{"catalog":{"name":"first"}}"""))
        first.start()
        assertEquals("first", first.koin.get<CatalogConfig>().name)
        first.stop()
        val second = app(module)
        second.start()
        assertEquals("default", second.koin.get<CatalogConfig>().name)
        second.stop()
    }

    @Test fun `a validation failure fails the start and names the issues`() = runTest {
        val app = app(catalog())
        app.configurations.add(JsonConfigurationFormat.parse("""{"catalog":{"limit":-1}}"""))
        val failure = assertFailsWith<ConfigurationException.ValidationFailed> { app.start() }
        assertEquals(listOf(ConfigurationIssue("catalog.limit", "NEGATIVE")), failure.issues)
        assertContains(failure.message!!, "catalog.limit=NEGATIVE")
        assertEquals(ApplicationStatus.FAILED, app.status)
    }

    @Test fun `a missing required value fails the start with its key and the reason`() = runTest {
        val app = app(module { configuration<RequiredConfig>() })
        val failure = assertFailsWith<ConfigurationException.DecodeFailed> { app.start() }
        assertEquals(listOf("required", "url"), failure.path)
        assertEquals(
            "Configuration decoding failed (source=defaults, section=required.url): Required value is missing",
            failure.message,
        )
        assertNotNull(failure.cause)
    }

    @Test fun `a class without the annotation is rejected`() = runTest {
        val app = app(module { configuration<Unannotated>() })
        assertFailsWith<ConfigurationException.MissingAnnotation> { app.start() }
    }
}
