package kim.jade.chassis.ktor

import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.ApplicationEngineFactory
import io.ktor.server.engine.embeddedServer
import kim.jade.chassis.Application
import kim.jade.chassis.hook.hook
import kim.jade.chassis.hook.onStart
import kim.jade.chassis.hook.onStop
import org.koin.core.module.Module
import org.koin.core.scope.Scope
import io.ktor.server.application.Application as KtorApplication

fun <TEngine : ApplicationEngine, TConfiguration : ApplicationEngine.Configuration> Module.ktorServer(
    engine: ApplicationEngineFactory<TEngine, TConfiguration>,
    autoStart: Boolean = false,
    configure: Scope.(TConfiguration) -> Unit,
) {
    single {
        val scope = this
        KtorServer(get()) { blocks ->
            embeddedServer(engine, configure = { scope.configure(this) }, module = blocks)
        }
    }
    hook {
        val server = get<KtorServer>()
        server.arm()
        if (autoStart) {
            onStart("ktor server") { server.start() }
        }
        onStop("ktor server") { server.stop() }
    }
}

fun Module.ktor(block: Scope.(KtorApplication) -> Unit) {
    hook { get<Application>().ktorBlocks.add(KtorBlock(this, block)) }
}
