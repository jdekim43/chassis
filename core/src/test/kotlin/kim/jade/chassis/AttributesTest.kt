package kim.jade.chassis

import kim.jade.chassis.hook.hook
import kim.jade.chassis.hook.onStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.koin.dsl.module
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AttributesTest {
    private val name = AttributeKey<String>("name")

    @Test fun `a value is read back with the key it was stored under`() {
        val attributes = Attributes()
        assertFalse(name in attributes)
        attributes[name] = "catalog"
        assertEquals("catalog", attributes[name])
        assertEquals("catalog", attributes.getOrNull(name))
        assertTrue(name in attributes)
    }

    @Test fun `a missing key is null or an error naming the key`() {
        val attributes = Attributes()
        assertNull(attributes.getOrNull(name))
        val thrown = assertFailsWith<IllegalStateException> { attributes[name] }
        assertContains(thrown.message!!, "name")
    }

    @Test fun `keys with the same name are different keys`() {
        val attributes = Attributes()
        attributes[name] = "catalog"
        assertNull(attributes.getOrNull(AttributeKey<String>("name")))
    }

    @Test fun `setting a key again replaces its value`() {
        val attributes = Attributes()
        attributes[name] = "first"
        attributes[name] = "second"
        assertEquals("second", attributes[name])
    }

    @Test fun `getOrPut returns the stored value or stores what create makes`() {
        val attributes = Attributes()
        assertEquals("made", attributes.getOrPut(name) { "made" })
        assertEquals("made", attributes[name])
        assertEquals("made", attributes.getOrPut(name) { error("not called") })
    }

    @Test fun `getOrPut calls create once when many threads ask at the same time`() {
        val attributes = Attributes()
        val key = AttributeKey<Any>("shared")
        val calls = AtomicInteger()
        val start = CountDownLatch(1)
        val seen = Collections.synchronizedList(mutableListOf<Any>())
        val threads = List(16) { thread { start.await(); seen += attributes.getOrPut(key) { calls.incrementAndGet(); Any() } } }
        start.countDown()
        threads.forEach { it.join() }
        assertEquals(1, calls.get())
        assertEquals(16, seen.size)
        assertTrue(seen.all { it === seen[0] })
    }

    @Test fun `create may read and write other keys of the same attributes`() {
        // 키의 해시가 매번 달라야 같은 칸에 놓이는 경우까지 지난다.
        repeat(2_000) {
            val outer = AttributeKey<String>("outer")
            val inner = AttributeKey<String>("inner")
            val other = AttributeKey<String>("other")
            val attributes = Attributes()
            assertEquals("outer", attributes.getOrPut(outer) {
                attributes.getOrPut(inner) { "inner" }
                attributes[other] = "other"
                "outer"
            })
            assertEquals("inner", attributes[inner])
            assertEquals("other", attributes[other])
        }
    }

    @Test fun `a nested getOrPut of the same key leaves the inner value for both calls`() {
        val attributes = Attributes()
        var inner: String? = null
        val outer = attributes.getOrPut(name) { inner = attributes.getOrPut(name) { "inner" }; "outer" }
        assertEquals("inner", inner)
        assertEquals("inner", outer)
        assertEquals("inner", attributes[name])
    }

    @Test fun `a hook reads an attribute through the application`() = runTest {
        var seen: String? = null
        val app = application(module {
            hook { val self = get<Application>(); onStart { seen = self.attributes.getOrNull(name) } }
        })
        app.attributes[name] = "catalog"
        app.start()
        app.stop()
        assertEquals("catalog", seen)
    }
}
