package example.catalog

import io.ktor.server.cio.CIO
import io.ktor.server.engine.connector
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kim.jade.chassis.config.configuration
import kim.jade.chassis.health.Health
import kim.jade.chassis.health.readiness
import kim.jade.chassis.hook.hook
import kim.jade.chassis.hook.onStart
import kim.jade.chassis.ktor.healthEndpoints
import kim.jade.chassis.ktor.ktor
import kim.jade.chassis.ktor.ktorServer
import kim.jade.chassis.worker.worker
import org.koin.dsl.module
import java.io.File

val catalogModule = module {
    configuration<CatalogConfig>()
    configuration<HealthConfig>()
    configuration<ServerConfig>()
    single { ProductSource(File(get<CatalogConfig>().source)) }
    single { CatalogStore(get()) }
    hook {
        val store = get<CatalogStore>()
        onStart("load catalog") {
            store.refresh()
            store.failure?.let { throw it }
        }
    }
    worker { CatalogRefresh(get(), get(), get()) }
    worker { HealthMonitor(get(), get()) }
    readiness("catalog") {
        when (val failure = get<CatalogStore>().failure) {
            null -> Health.healthy()
            else -> Health.unhealthy("cannot read ${get<CatalogConfig>().source}", failure)
        }
    }
    ktorServer(CIO) { engine ->
        val config = get<ServerConfig>()
        engine.connector {
            host = config.host
            port = config.port
        }
    }
    healthEndpoints()
    ktor { server ->
        val store = get<CatalogStore>()
        server.routing {
            get("/products") { call.respondText(store.names.joinToString("\n")) }
        }
    }
}
