package kim.jade.chassis

import kim.jade.chassis.hook.onInitialize
import kim.jade.chassis.hook.onRelease
import kim.jade.chassis.hook.onRequestStop
import kim.jade.chassis.hook.onStart
import kim.jade.chassis.hook.onStarted
import kim.jade.chassis.hook.onStop
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.koin.core.module.Module
import org.koin.core.scope.Scope
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal fun Scope.recordAll(calls: MutableList<String>, label: String) {
    onInitialize { calls += "Initialize/$label" }
    onStart { calls += "Start/$label" }
    onStarted { calls += "Started/$label" }
    onRequestStop { calls += "RequestStop/$label" }
    onStop { calls += "Stop/$label" }
    onRelease { calls += "Release/$label" }
}

/** 코루틴 스택 복원은 예외를 복사하고 원본을 cause로 남길 수 있다. */
internal fun assertFailurePreserved(expected: Throwable, actual: Throwable?) {
    var current = assertNotNull(actual)
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    while (current !== expected) {
        assertTrue(seen.add(current), "Cyclic cause chain does not contain the original failure")
        assertEquals(expected::class, current::class)
        assertEquals(expected.message, current.message)
        current = assertNotNull(current.cause, "Original failure must be retained in the cause chain")
    }
}

internal fun suppressedMessages(failure: Throwable): List<String?> =
    generateSequence(failure) { it.cause }.flatMap { it.suppressed.asSequence() }.map { it.message }.toList()

internal fun TestScope.application(vararg modules: Module, shutdownTimeout: Duration = 30.seconds): Application =
    Application(modules.toList(), shutdownTimeout = shutdownTimeout, context = StandardTestDispatcher(testScheduler))
