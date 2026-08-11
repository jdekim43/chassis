package kim.jade.chassis.hook

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HookRegistryTest {
    private val calls = mutableListOf<String>()
    private val registry = HookRegistry()

    @Test fun `startup phase runs hooks in registration order`() = runTest {
        registry.register(HookPhase.Start, null) { calls += "a" }
        registry.register(HookPhase.Start, null) { calls += "b" }
        registry.register(HookPhase.Stop, null) { calls += "other phase" }
        registry.run(HookPhase.Start)
        assertEquals(listOf("a", "b"), calls)
    }

    @Test fun `shutdown phase runs hooks in reverse registration order`() = runTest {
        registry.register(HookPhase.Stop, null) { calls += "a" }
        registry.register(HookPhase.Stop, null) { calls += "b" }
        registry.run(HookPhase.Stop)
        assertEquals(listOf("b", "a"), calls)
    }

    @Test fun `startup phase stops at the first failure and rethrows it`() = runTest {
        registry.register(HookPhase.Initialize, null) { calls += "a" }
        registry.register(HookPhase.Initialize, null) { throw IllegalStateException("boom") }
        registry.register(HookPhase.Initialize, null) { calls += "c" }
        val thrown = assertFailsWith<IllegalStateException> { registry.run(HookPhase.Initialize) }
        assertEquals("boom", thrown.message)
        assertEquals(listOf("a"), calls)
    }

    @Test fun `shutdown phase reports every failure and keeps going`() = runTest {
        val failures = mutableListOf<Throwable>()
        registry.register(HookPhase.Stop, null) { calls += "last" }
        registry.register(HookPhase.Stop, null) { throw IllegalStateException("second") }
        registry.register(HookPhase.Stop, null) { throw IllegalStateException("first") }
        registry.run(HookPhase.Stop) { failures += it }
        assertEquals(listOf("first", "second"), failures.map { it.message })
        assertEquals(listOf("last"), calls)
    }

    @Test fun `register after seal throws with guidance`() {
        registry.seal()
        registry.seal()
        val thrown = assertFailsWith<IllegalStateException> { registry.register(HookPhase.Start, "late") {} }
        assertContains(thrown.message!!, "Start/late")
        assertContains(thrown.message!!, "createdAtStart")
    }

    @Test fun `a hook that cancels itself is a failure named after its phase and index`() = runTest {
        val failures = mutableListOf<Throwable>()
        registry.register(HookPhase.Stop, null) { throw CancellationException("self") }
        registry.run(HookPhase.Stop) { failures += it }
        val failure = assertIs<IllegalStateException>(failures.single())
        assertContains(failure.message!!, "Stop/Stop#0")
        assertIs<CancellationException>(failure.cause)
    }

    @Test fun `cancelling the caller propagates instead of recording a failure`() = runTest {
        val failures = mutableListOf<Throwable>()
        registry.register(HookPhase.Stop, null) { awaitCancellation() }
        val job = launch { registry.run(HookPhase.Stop) { failures += it } }
        runCurrent()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(emptyList(), failures)
    }

    @Test fun `a hook waits for coroutines launched in its context`() = runTest {
        registry.register(HookPhase.Start, null) {
            CoroutineScope(currentCoroutineContext()).launch { delay(10); calls += "child" }
        }
        registry.run(HookPhase.Start)
        assertEquals(listOf("child"), calls)
    }

    @Test fun `a failing child coroutine fails its hook`() = runTest {
        registry.register(HookPhase.Start, null) {
            CoroutineScope(currentCoroutineContext()).launch { throw IllegalArgumentException("child") }
        }
        val thrown = assertFailsWith<IllegalArgumentException> { registry.run(HookPhase.Start) }
        assertEquals("child", thrown.message)
    }
}
