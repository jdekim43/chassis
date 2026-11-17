package kim.jade.chassis.hook

import kim.jade.chassis.recordAll
import kotlinx.coroutines.test.runTest
import org.koin.core.Koin
import org.koin.core.KoinApplication
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class HookDslTest {
    private class Leaf
    private class Root(val leaf: Leaf)

    private val calls = mutableListOf<String>()

    private fun collect(vararg modules: Module): Pair<Koin, HookRegistry> {
        val registry = HookRegistry()
        val application = KoinApplication.init().apply {
            modules(module { single { registry } })
            modules(*modules)
        }
        application.koin.createEagerInstances()
        application.koin.runHookBlocks()
        registry.seal()
        return application.koin to registry
    }

    @Test fun `a dependency registers its hooks before its dependent`() = runTest {
        val (_, registry) = collect(module {
            single(createdAtStart = true) {
                Root(get()).also { onStart { calls += "root" }; onStop { calls += "root" } }
            }
            single { Leaf().also { onStart { calls += "leaf" }; onStop { calls += "leaf" } } }
        })
        registry.run(HookPhase.Start)
        registry.run(HookPhase.Stop)
        assertEquals(listOf("leaf", "root", "root", "leaf"), calls)
    }

    @Test fun `module level hooks keep their declaration order`() = runTest {
        val (_, registry) = collect(module {
            hook { onStart { calls += "a" } }
            onStart { calls += "b" }
            hook { onStart { calls += "c" } }
        })
        registry.run(HookPhase.Start)
        assertEquals(listOf("a", "b", "c"), calls)
    }

    @Test fun `a hook block runs after the hooks of what it resolves`() = runTest {
        val (_, registry) = collect(module {
            hook { get<Leaf>(); onStart { calls += "block" } }
            single { Leaf().also { onStart { calls += "leaf" } } }
        })
        registry.run(HookPhase.Start)
        assertEquals(listOf("leaf", "block"), calls)
    }

    @Test fun `onStart resolves to the instance inside a definition and to the module outside`() = runTest {
        val (_, registry) = collect(module {
            single(createdAtStart = true) { Leaf().also { onStart { calls += "definition" } } }
            onStart { calls += "module" }
        })
        registry.run(HookPhase.Start)
        assertEquals(listOf("definition", "module"), calls.sorted())
    }

    @Test fun `every phase has a definition form and a module form`() = runTest {
        val (_, registry) = collect(module {
            hook { recordAll(calls, "scope") }
            onInitialize { calls += "Initialize/module" }
            onStart { calls += "Start/module" }
            onStarted { calls += "Started/module" }
            onRequestStop { calls += "RequestStop/module" }
            onStop { calls += "Stop/module" }
            onRelease { calls += "Release/module" }
        })
        for (phase in HookPhase.entries) registry.run(phase)
        assertEquals(
            HookPhase.entries.flatMap { phase ->
                listOf("scope", "module").let { if (phase.isLaunchPhase) it else it.reversed() }.map { "$phase/$it" }
            },
            calls,
        )
    }

    @Test fun `a module included twice registers its hooks once`() = runTest {
        val shared = module { onStart { calls += "shared" } }
        val (_, registry) = collect(module { includes(shared) }, module { includes(shared) })
        registry.run(HookPhase.Start)
        assertEquals(listOf("shared"), calls)
    }

    @Test fun `a definition that is never created registers nothing`() = runTest {
        val (_, registry) = collect(module { single { Leaf().also { onStart { calls += "leaf" } } } })
        registry.run(HookPhase.Start)
        assertEquals(emptyList(), calls)
    }

    @Test fun `registering after collection fails with guidance`() {
        val (koin, _) = collect(module { single { Leaf().also { onStart { } } } })
        val failure = assertFails { koin.get<Leaf>() }
        val messages = generateSequence(failure) { it.cause }.mapNotNull { it.message }.toList()
        assertTrue(messages.any { "createdAtStart" in it }, messages.toString())
    }
}
